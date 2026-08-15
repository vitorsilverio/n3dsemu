package dev.vitorsilverio.n3dsemu;

import dev.vitorsilverio.n3dsemu.kernel.SvcCall;
import dev.vitorsilverio.n3dsemu.kernel.UnsupportedSvcException;
import dev.vitorsilverio.n3dsemu.loader.Image3dsx;
import dev.vitorsilverio.n3dsemu.loader.Loader3dsx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Teste de integração do marco M1 (RFC-N3DSEMU): carrega o `.3dsx` real do
/// `templates/application` (`testdata/`) e roda até a primeira `svc`.
///
/// **A SVC observada é `0x21 svcCreateAddressArbiter`** (não `svcConnectToPort("srv:")`, que a
/// task especulou como possibilidade mais provável) — descoberto rodando o `Main` real com
/// `--trace-svc` e confirmado contra o `.elf` (`arm-none-eabi-objdump -d`, símbolo
/// `svcCreateAddressArbiter` em `0x105b10`, `svc 0x21` em `0x105b14`): é o primeiro lock
/// interno que o `crt0`/newlib do libctru cria antes de qualquer coisa relacionada a
/// serviços IPC. A asserção abaixo é fixada nesse valor observado, não em uma suposição.
class Application3dsxTest {
    private static final Path APPLICATION_3DSX = Path.of("testdata/application.3dsx");
    private static final int EXPECTED_FIRST_SVC_NUMBER = 0x21;
    private static final String EXPECTED_FIRST_SVC_NAME = "svcCreateAddressArbiter";
    private static final int EXPECTED_FIRST_SVC_PC = 0x00105B18;

    @ParameterizedTest
    @EnumSource(N3dsMachine.Backend.class)
    void rodaAteAPrimeiraSvcNosTresBackends(N3dsMachine.Backend backend) throws IOException {
        Image3dsx image = new Loader3dsx().load(Files.readAllBytes(APPLICATION_3DSX));
        N3dsMachine machine = N3dsMachine.create(image, backend, silentLog(), false);

        UnsupportedSvcException thrown = assertThrows(UnsupportedSvcException.class, () -> {
            for (int i = 0; i < 100; i++) {
                machine.runSlice();
            }
        });

        SvcCall call = thrown.call();
        assertEquals(EXPECTED_FIRST_SVC_NUMBER, call.number());
        assertEquals(EXPECTED_FIRST_SVC_NAME, call.name());
        assertEquals(EXPECTED_FIRST_SVC_PC, call.pc());
    }

    /// O aceite mais importante da task (RFC-N3DSEMU G1): os três backends decodificam e
    /// executam o mesmo código ARMv6K real do preset `ARM11_MPCORE` até a MESMA primeira
    /// `svc` — prova de consistência entre JIT, interpretador e o motor de checagem.
    @Test
    void jitEInterpretadoChegamNaMesmaSvc() throws IOException {
        byte[] file = Files.readAllBytes(APPLICATION_3DSX);

        SvcCall jitCall = firstSvcOf(N3dsMachine.Backend.JIT, file);
        SvcCall interpretedCall = firstSvcOf(N3dsMachine.Backend.INTERPRETED, file);

        assertEquals(jitCall.number(), interpretedCall.number());
        assertEquals(jitCall.name(), interpretedCall.name());
        assertEquals(jitCall.pc(), interpretedCall.pc());
    }

    private static SvcCall firstSvcOf(N3dsMachine.Backend backend, byte[] file) {
        Image3dsx image = new Loader3dsx().load(file);
        N3dsMachine machine = N3dsMachine.create(image, backend, silentLog(), false);
        UnsupportedSvcException thrown = assertThrows(UnsupportedSvcException.class, () -> {
            for (int i = 0; i < 100; i++) {
                machine.runSlice();
            }
        });
        return thrown.call();
    }

    private static PrintStream silentLog() {
        return new PrintStream(new ByteArrayOutputStream());
    }
}
