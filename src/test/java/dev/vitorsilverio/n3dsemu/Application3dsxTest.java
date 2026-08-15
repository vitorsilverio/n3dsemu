package dev.vitorsilverio.n3dsemu;

import dev.vitorsilverio.n3dsemu.loader.Image3dsx;
import dev.vitorsilverio.n3dsemu.loader.Loader3dsx;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Teste de integração do marco M1 (RFC-N3DSEMU): carrega o `.3dsx` real do
/// `templates/application` (`testdata/`) e roda até a primeira `svc`.
///
/// **G2 PR2 implementou `svcCreateAddressArbiter` (`0x21`, achado real — ver Javadoc de
/// `SvcTable`), então o boot progride além do que a G2 PR1 alcançava.** O código avançado é o
/// `crt0`/newlib configurando a VFP antes de `main()` — e ele bate num limite ARQUITETURAL do
/// próprio `arm-jitter`, não desta task: `FpscrRegister#setValue` (decisão nº 3 do épico B3,
/// documentada na própria classe) só suporta o modo IEEE round-to-nearest da VFP, e o `crt0`
/// aqui grava um FPSCR com campo `LEN`/`STRIDE` (aritmética vetorial VFPv2, herança de
/// binários ARM antigos) diferente de zero, o que é rejeitado de propósito. **Corrigir isso
/// exigiria revisitar uma decisão de arquitetura de OUTRO épico do arm-jitter — fica fora do
/// escopo desta task (G2, kernel HLE) e é reportado aqui, não corrigido.** Consequência: o
/// aceite "M2: roda até `svcExitProcess`" desta sessão não é alcançável para
/// `templates/application` enquanto essa decisão do arm-jitter não for revisitada (mesmo
/// padrão de sessões anteriores com aceite parcial, ex. `B4.1.5` sessão 1).
class Application3dsxTest {
    private static final Path APPLICATION_3DSX = Path.of("testdata/application.3dsx");

    @ParameterizedTest
    @EnumSource(N3dsMachine.Backend.class)
    void rodaAlemDaPrimeiraSvcEEsbarraNoLimiteDeFpscrDoArmJitter(N3dsMachine.Backend backend) throws IOException {
        Image3dsx image = new Loader3dsx().load(Files.readAllBytes(APPLICATION_3DSX));
        N3dsMachine machine = N3dsMachine.create(image, backend, silentLog(), false);

        // Ver Javadoc da classe: não é mais UnsupportedSvcException (G2 PR1 parava em
        // svcCreateAddressArbiter) — agora é um limite do próprio arm-jitter, mais adiante.
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class, () -> {
            for (int i = 0; i < 100; i++) {
                machine.runSlice();
            }
        });

        assertTrue(thrown.getMessage().contains("FPSCR"));
    }

    private static PrintStream silentLog() {
        return new PrintStream(new ByteArrayOutputStream());
    }
}
