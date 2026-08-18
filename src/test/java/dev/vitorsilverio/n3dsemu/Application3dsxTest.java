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
/// **Investigação G2.2 (2026-08-18, `tasks/trilha-g-3ds/g2.2-address-arbiter-loop.md` do
/// `arm-jitter`) — causa raiz do "laço" achada e corrigida.** O relato acima ("laço que chama
/// `svcCreateAddressArbiter` repetidamente no MESMO PC") estava certo na observação, mas errado na
/// interpretação: não era uma SVC retentando. Trace de duas fases (fast-forward via
/// `--trace-svc` até a vizinhança do "laço" + `ArmCore#step()` registrador-a-registrador a partir
/// dali, replicado com {@code core.step()} puro — SEM `runBlocks`/JIT nenhum, então JIT e
/// INTERPRETED divergem só em VELOCIDADE, não em comportamento, o achado colateral da sessão
/// anterior sobre essa divergência fica então esclarecido: é só ritmo de execução, não bug de
/// paridade JIT×interpretado) mostrou: o histórico de PC imediatamente antes de CADA ocorrência de
/// `svcCreateAddressArbiter` era byte-a-byte IDÊNTICO — `_start`→`startup`→`initSystem`→
/// `__libctru_init`→`__sync_init`→`svcCreateAddressArbiter` — sempre com `lr`/`r0` congelados nos
/// mesmos valores. Ou seja: o boot inteiro estava REINICIANDO do zero a cada volta (período fixo
/// de 271.058 instruções reais), não retentando uma SVC. `svcCreateAddressArbiter` só aparecia
/// repetido porque é a primeira SVC de cada tentativa de boot, não porque algo dependia dela.
///
/// A causa raiz real: `srvInit` do libctru abre com `MCR p15, 0, r0, c7, c10, {5}` — `DMB` (Data
/// Memory Barrier) no encoding ARMv6K via CP15 (o mnemônico dedicado `dmb` só existe a partir do
/// ARMv7; confirmado via `objdump` no `.3dsx` real, não por analogia — grep por `mcr.*15,` no
/// disassembly mostrou só dois opcodes de barreira usados, `c7,c10,{4}` `DSB` e `c7,c10,{5}`
/// `DMB`). {@link dev.vitorsilverio.n3dsemu.core.N3dsCp15} só reconhecia `TPIDRURO`
/// (`c13,c0,3`) — para qualquer outro registrador, `handles(...)` devolve `false`, e o arm-jitter
/// entrega `ArmException.UNDEFINED` ao guest (`AsmRuntimeHelpers#executeCoprocessor`, o mesmo
/// comportamento do hardware real para um coprocessador ausente). Como este HLE não configura
/// NENHUM vetor de exceção (RFC-N3DSEMU D2: sem MMU/LLE, sem BIOS real), o PC caía no endereço 4
/// (a "tabela de vetores" ARM de baixa memória, nunca escrita — lida como zero, que decodifica
/// como `andeq r0,r0,r0`, um no-op de fato) e andava sequencialmente, incrementando de 4 em 4,
/// até tropeçar de volta no executável carregado em `0x00100000` — reiniciando `_start` do zero.
/// **Corrigido** ({@link dev.vitorsilverio.n3dsemu.core.N3dsCp15Test}, o mesmo padrão de "registro
/// vizinho não reconhecido" já visto 2x nesta trilha para SVCs, agora para CP15): `DSB`/`DMB`
/// tratados como no-op — este HLE não reordena acessos à memória do guest nem mantém cache de
/// instrução separado que precise de invalidação, então a barreira não tem efeito observável
/// aqui. Com o fix, o boot passa de `srvInit` e alcança `svcConnectToPort`/`svcSendSyncRequest`
/// (`0x2D`/`0x32`) — a fronteira real e esperada com a G3 (serviços IPC), não mais o boot
/// reiniciando.
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

    private static final int SVC_SEND_SYNC_REQUEST = 0x32;

    /// Regressão da causa raiz de G2.2 (ver Javadoc da classe): antes do fix de
    /// {@link dev.vitorsilverio.n3dsemu.core.N3dsCp15}, NENHUM orçamento de fatias alcançava
    /// `svcSendSyncRequest` — o boot reiniciava do zero ao tropeçar em `srvInit`. Com o fix, os
    /// três backends alcançam essa SVC (a fronteira real com a G3, não implementada de propósito —
    /// {@link UnsupportedSvcException} é esperada e não deve escapar do teste) dentro do mesmo
    /// orçamento já usado pelo teste acima.
    @ParameterizedTest
    @EnumSource(N3dsMachine.Backend.class)
    void passaDeSrvInitEAlcancaSvcSendSyncRequestNosTresBackends(N3dsMachine.Backend backend) throws IOException {
        Image3dsx image = new Loader3dsx().load(Files.readAllBytes(APPLICATION_3DSX));
        N3dsMachine machine = N3dsMachine.create(image, backend, silentLog(), false);

        for (int i = 0; i < SLICE_BUDGET; i++) {
            try {
                machine.runSlice();
            } catch (UnsupportedSvcException ignored) {
                // Esperado: svcSendSyncRequest (G3, IPC de verdade) não é implementada de
                // propósito — ver Javadoc da classe.
            }
        }

        var recentCalls = machine.svcTable().recentCalls();
        assertTrue(recentCalls.stream().anyMatch(call -> call.number() == SVC_SEND_SYNC_REQUEST));
    }

    private static PrintStream silentLog() {
        return new PrintStream(new ByteArrayOutputStream());
    }
}
