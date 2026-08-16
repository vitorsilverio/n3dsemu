package dev.vitorsilverio.n3dsemu;

import dev.vitorsilverio.n3dsemu.kernel.KernelHaltException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
/// **Novo limite alcançado (não corrigido nesta sessão — fora do escopo de uma investigação)**:
/// com o bug de handle corrigido, o boot avança até `svc 0x39`
/// (`svcGetResourceLimitLimitValues`), que NÃO está na lista de SVCs da task G2
/// (`tasks/trilha-g-3ds/g2-kernel-hle-svc.md` só lista `0x38`/`0x3A`) — outro caso do mesmo
/// padrão já documentado em `SvcTable` para `svcCreateAddressArbiter`: um SVC vizinho, chamado
/// pelo mesmo `__system_allocateHeaps` do crt0, que a lista original não previu. Como o `Main`
/// (CLI real) já finge sucesso e segue (ver Javadoc de {@link Main}), o guest continua até um
/// `svcControlMemory(MEMOP_ALLOC)` de verdade — que hoje devolve falha para os parâmetros que o
/// `crt0` usa — e então o próprio guest chama `svcBreak(PANIC)` de novo. Modelar `0x39` com
/// valores de limite plausíveis e investigar por que o `ALLOC` falha é trabalho de continuação
/// de G2 (ou já dentro do escopo real de G3), não desta investigação — fica registrado aqui e
/// na `FILA-EXECUCAO.md` do arm-jitter para a próxima sessão.
class Application3dsxTest {
    private static final Path APPLICATION_3DSX = Path.of("testdata/application.3dsx");
    /// Orçamento generoso de fatias — o mesmo padrão do `Main` real: cada `UnsupportedSvcException`
    /// é engolida (o guest segue como se a SVC tivesse "sumido", mesma convenção documentada em
    /// {@link Main}) até um `KernelHaltException` de verdade (aqui, o `svcBreak(PANIC)` do guest).
    private static final int SLICE_BUDGET = 200;

    @ParameterizedTest
    @EnumSource(N3dsMachine.Backend.class)
    void rodaAlemDoFpscrEDoBugDeHandlePseudoReservadaEEsbarraNoProximoGapDeG2(N3dsMachine.Backend backend) throws IOException {
        Image3dsx image = new Loader3dsx().load(Files.readAllBytes(APPLICATION_3DSX));
        N3dsMachine machine = N3dsMachine.create(image, backend, silentLog(), false);

        KernelHaltException thrown = assertThrows(KernelHaltException.class, () -> {
            for (int i = 0; i < SLICE_BUDGET; i++) {
                try {
                    machine.runSlice();
                } catch (UnsupportedSvcException ignored) {
                    // Ver Javadoc da classe: mesma convenção do Main real — segue para a
                    // próxima fatia em vez de travar na primeira SVC não implementada.
                }
            }
        });

        assertEquals(KernelHaltException.Reason.GUEST_BREAK, thrown.reason());
        assertTrue(thrown.getMessage().contains("PANIC"));
    }

    private static PrintStream silentLog() {
        return new PrintStream(new ByteArrayOutputStream());
    }
}
