package dev.vitorsilverio.n3dsemu;

import dev.vitorsilverio.n3dsemu.kernel.UnsupportedSvcException;
import dev.vitorsilverio.n3dsemu.loader.Image3dsx;
import dev.vitorsilverio.n3dsemu.loader.Loader3dsx;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Teste de integração do marco M1 (RFC-N3DSEMU): carrega o `.3dsx` real do
/// `templates/application` (`testdata/`) e roda até onde o kernel HLE atual sustenta.
///
/// **Histórico do bloqueio de FPSCR (RESOLVIDO):** o `crt0`/newlib configurava a VFP com um
/// FPSCR (`RMode`/`FZ`/`LEN`/`STRIDE`) que `FpscrRegister#setValue` do arm-jitter rejeitava de
/// propósito (decisão nº 3 do épico B3 de OUTRO repo: só IEEE round-to-nearest). A task B3.8 do
/// arm-jitter (2026-08-15, `1b5bd0a`) revisitou essa decisão: `RMode`/`FZ` agora têm semântica
/// real, `LEN`/`STRIDE` são aceitos-mas-ignorados (decisão de escopo explícita, ver Javadoc de
/// `FpscrRegister`). Esta sessão confirmou que o boot progride de verdade além do `crt0` com o
/// arm-jitter atualizado (`mvn -o install` local já rodado naquele repo).
///
/// **Achado real desta sessão**: passar da VFP revelou um segundo bloqueio, este sim um bug do
/// `n3dsemu` — {@link dev.vitorsilverio.n3dsemu.kernel.HandleTable#CURRENT_PROCESS_HANDLE} e
/// {@link dev.vitorsilverio.n3dsemu.kernel.HandleTable#CURRENT_THREAD_HANDLE} estavam com os
/// valores TROCADOS (`0xFFFF8000`/`0xFFFF8001` invertidos em relação a `CUR_PROCESS_HANDLE`/
/// `CUR_THREAD_HANDLE` do header real `libctru/include/3ds/svc.h`, confirmado também pela
/// montagem real do `.3dsx` — `__system_allocateHeaps` passa literalmente `0xFFFF8001` para
/// `svcGetResourceLimit`, que só aceita uma handle de PROCESSO). Corrigido nesta sessão — sem
/// a correção, `svcGetResourceLimit(CUR_PROCESS_HANDLE)` resolvia para a thread atual (não um
/// `ProcessObject`), devolvia {@code Result#INVALID_HANDLE}, e o guest reagia chamando
/// `svcBreak(PANIC)` sozinho.
///
/// **Segundo bloqueio (RESOLVIDO nesta sessão de continuação, 2026-08-16, ver
/// `tasks/FILA-EXECUCAO.md` do `arm-jitter`)**: com o bug de handle corrigido, o boot avançava
/// até `svc 0x39` (`svcGetResourceLimitLimitValues`), que NÃO estava na lista de SVCs da task G2
/// (`tasks/trilha-g-3ds/g2-kernel-hle-svc.md` só listava `0x38`/`0x3A`) — outro caso do mesmo
/// padrão já documentado em `SvcTable` para `svcCreateAddressArbiter`: um SVC vizinho, chamado
/// pelo mesmo `__system_allocateHeaps` do crt0, que a lista original não previu. Sem ela, o
/// array de saída ficava com o que já estava na pilha do guest (zero), o tamanho de heap
/// calculado virava `0` e o `svcControlMemory(MEMOP_ALLOC)` seguinte falhava com
/// `MISALIGNED_SIZE` — e o guest reagia chamando `svcBreak(PANIC)`. **Dois bugs reais corrigidos
/// para destravar isso**: (1) `SvcTable#handleGetResourceLimitLimitValues`/
/// {@link dev.vitorsilverio.n3dsemu.kernel.ResourceLimitValues#limitValueOf} implementados; (2)
/// achado arquitetural mais profundo — `MemoryMap#LINEAR_HEAP_BASE`/`GENERAL_HEAP_BASE` estavam
/// com os endereços TROCADOS em relação ao 3dbrew real (`Memory_layout`: o heap "geral" mapeado
/// por `ControlMemory` fica em `0x08000000`, o heap LINEAR fica em `0x14000000` via
/// `MEMOP_ALLOC_LINEAR` — o oposto do que este projeto tinha) — sem a correção, o segundo
/// `svcControlMemory` do crt0 (que usa a flag `LINEAR`) caía no pool errado e falhava com
/// `OUT_OF_RANGE`.
///
/// **Novo limite alcançado (NÃO corrigido nesta sessão — fora do escopo de uma continuação
/// cirúrgica de G2, provavelmente escalonador/sincronização de verdade, G3)**: com os dois
/// heaps commitados, o backend JIT entra num laço que chama `svcCreateAddressArbiter` (`0x21`)
/// repetida e indefinidamente no MESMO PC — nenhum progresso observável além disso dentro de
/// qualquer orçamento de fatias razoável (confirmado manualmente até 200 mil fatias). Não é mais
/// um `svcBreak(PANIC)`; é um spin/retry que este esqueleto de kernel (sem preempção real, RFC
/// D1) não sustenta. **Backends divergem em quantas fatias levam para chegar lá** (INTERPRETED
/// executa MUITO menos instruções por fatia que o JIT compilado/encadeado — dentro do orçamento
/// desta suíte, INTERPRETED/CHECK ainda não alcançam o laço, só o JIT alcança), então este teste
/// verifica só o marco comum aos três backends (os dois `svcControlMemory` de heap bem-sucedidos),
/// não o laço em si. Fica registrado aqui e na `FILA-EXECUCAO.md` do arm-jitter para a próxima
/// sessão (G2 continuação ou G3) — incluindo a própria divergência de progresso entre backends,
/// que o aceite original da G2 pede para bater byte a byte e hoje não bate dentro de orçamentos
/// de fatias iguais.
class Application3dsxTest {
    private static final Path APPLICATION_3DSX = Path.of("testdata/application.3dsx");
    /// Orçamento de fatias suficiente para os três backends completarem os dois
    /// `svcControlMemory` de heap (marco comum) — ver Javadoc da classe para a divergência de
    /// progresso entre backends além deste ponto.
    private static final int SLICE_BUDGET = 200;
    private static final int SVC_CONTROL_MEMORY = 0x01;
    /// `MemOp` bruto (`ALLOC | LINEAR_FLAG`, ver `MemoryOperation`) do segundo
    /// `svcControlMemory` do crt0 — só é alcançado se os dois heaps (geral e linear) commitarem
    /// com sucesso, o que exercita os dois bugs corrigidos nesta sessão de uma vez.
    private static final int ALLOC_LINEAR_RAW_OPERATION = 0x10003;

    @ParameterizedTest
    @EnumSource(N3dsMachine.Backend.class)
    void alocaOsDoisHeapsComSucessoNosTresBackendsSemSvcBreak(N3dsMachine.Backend backend) throws IOException {
        Image3dsx image = new Loader3dsx().load(Files.readAllBytes(APPLICATION_3DSX));
        N3dsMachine machine = N3dsMachine.create(image, backend, silentLog(), false);

        // Nenhum KernelHaltException — nem svcBreak(PANIC) nem qualquer outro — deve ocorrer
        // mais: os dois bugs que causavam o PANIC (svc 0x39 ausente + heaps geral/linear
        // trocados) estão corrigidos nesta sessão. Se um KernelHaltException escapar daqui, é
        // uma regressão real, não o limite já documentado.
        for (int i = 0; i < SLICE_BUDGET; i++) {
            try {
                machine.runSlice();
            } catch (UnsupportedSvcException ignored) {
                // Ver Javadoc da classe: mesma convenção do Main real — segue para a
                // próxima fatia em vez de travar na primeira SVC não implementada.
            }
        }

        var recentCalls = machine.svcTable().recentCalls();
        assertTrue(recentCalls.stream().anyMatch(
                call -> call.number() == SVC_CONTROL_MEMORY && call.r0() == ALLOC_LINEAR_RAW_OPERATION));
    }

    private static PrintStream silentLog() {
        return new PrintStream(new ByteArrayOutputStream());
    }
}
