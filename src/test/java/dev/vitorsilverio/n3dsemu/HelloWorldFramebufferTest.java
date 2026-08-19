package dev.vitorsilverio.n3dsemu;

import dev.vitorsilverio.n3dsemu.gpu.FrameBufferState;
import dev.vitorsilverio.n3dsemu.gpu.GuestFrameBufferReader;
import dev.vitorsilverio.n3dsemu.gpu.Screen;
import dev.vitorsilverio.n3dsemu.kernel.KernelHaltException;
import dev.vitorsilverio.n3dsemu.kernel.UnsupportedSvcException;
import dev.vitorsilverio.n3dsemu.loader.Image3dsx;
import dev.vitorsilverio.n3dsemu.loader.Loader3dsx;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Teste de regressão da G4.2 (RFC-N3DSEMU trilha G — "investigar por que o framebuffer fica
/// zerado mesmo sem depender de fonte"). **Achado real desta sessão: a hipótese da própria task
/// estava errada.** O framebuffer de {@link Screen#TOP} NÃO fica 100% zero — a G4.1 já tinha
/// refutado `APT:GetSharedFont` como causa; esta sessão refuta a premissa seguinte ("o conteúdo
/// nunca é desenhado" / "presentFrame lê zero"). Dump periódico via
/// {@link GuestFrameBufferReader#read} (a MESMA função que {@code Main#presentFrame} usa) mostrou
/// que o `hello-world.3dsx` real escreve pixels não-zero na tela de cima a partir de ~slice 180
/// (endereço `0x14000000` = base do heap linear, `stride=480`, `RGB565` — bate com o que
/// `GspGpuService#readFramebufferUpdate`, G4/G4.1, já reportava) e o conteúdo permanece ESTÁVEL
/// (1040/192000 bytes não-zero, ~520 pixels) por milhares de fatias depois — sem flicker, sem
/// corromper, sem voltar a zero. O primeiro pixel não-zero fica em `col=122,row=1` (bem perto do
/// topo da tela, plausível para uma linha de texto). Ou seja: a cadeia
/// `consoleInit`→`printf`→`gfxSwapBuffers`→`GspGpuService`/`FrameBufferState` até
/// {@link GuestFrameBufferReader} está **funcionando corretamente** — o texto é desenhado e é
/// lido de volta com sucesso pelo mesmo código que o `Main` real usa. Se a tela ainda aparece
/// preta na janela, o bug (se houver) está a partir daqui: {@link
/// dev.vitorsilverio.n3dsemu.gpu.FrameBufferCodec}/{@link
/// dev.vitorsilverio.n3dsemu.gpu.vulkan.VulkanRenderer} (upload de textura/rotação
/// retrato→paisagem no shader) — fora do que dá para provar sem GPU/olho humano (RFC D4), OU o
/// texto é legitimamente pequeno/esparso (~0,5% da tela) e fácil de não notar numa janela grande.
/// Esta task NÃO mexeu em nenhum código de produção — nenhum bug real foi encontrado na cadeia
/// investigada, só a premissa da task foi refutada com evidência (mesmo padrão da G4.1).
class HelloWorldFramebufferTest {
    private static final Path HELLO_WORLD_3DSX = Path.of("testdata/hello-world.3dsx");
    /// Orçamento observado nesta sessão para o framebuffer TOP sair de zero (~slice 180) — folga
    /// generosa para não ficar frágil a pequenas mudanças de timing de boot.
    private static final int SLICE_BUDGET = 400;

    @Test
    void framebufferTopDeixaDeSerZeroEmAlgumPontoDoBootHeadless() throws IOException {
        Image3dsx image = new Loader3dsx().load(Files.readAllBytes(HELLO_WORLD_3DSX));
        N3dsMachine machine = N3dsMachine.create(image, N3dsMachine.Backend.JIT, silentLog(), false);

        boolean sawNonZero = false;
        for (int i = 0; i < SLICE_BUDGET && !sawNonZero; i++) {
            try {
                machine.runSlice();
            } catch (UnsupportedSvcException ignored) {
                // Convenção já estabelecida nesta trilha: svc não implementada não trava o boot.
            } catch (KernelHaltException e) {
                break;
            }
            FrameBufferState.Buffer buffer = machine.frameBufferState().active(Screen.TOP);
            if (buffer == null) {
                continue;
            }
            byte[] raw = GuestFrameBufferReader.read(machine.memory(), Screen.TOP, buffer);
            sawNonZero = containsNonZero(raw);
        }

        assertTrue(sawNonZero, "framebuffer de Screen.TOP deveria conter pixels não-zero em algum"
                + " ponto do boot (achado real: aparece por volta da fatia 180, permanece estável)");
    }

    private static boolean containsNonZero(byte[] raw) {
        for (byte b : raw) {
            if (b != 0) {
                return true;
            }
        }
        return false;
    }

    private static java.io.PrintStream silentLog() {
        return new java.io.PrintStream(new java.io.ByteArrayOutputStream());
    }
}
