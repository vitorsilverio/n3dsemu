package dev.vitorsilverio.n3dsemu.gpu.vulkan;

import dev.vitorsilverio.n3dsemu.gpu.PixelFormat;
import dev.vitorsilverio.n3dsemu.gpu.Screen;
import dev.vitorsilverio.n3dsemu.gpu.ShadedVertex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

/// RFC-N3DSEMU G4/D4: "nenhuma task da trilha G pode ter como aceite automatizado 'o triângulo
/// apareceu'" — este teste NÃO valida a imagem (impossível sem olho humano), só que a pilha
/// Vulkan inicializa/apresenta/fecha sem lançar em uma máquina COM driver. Pula via
/// `Assumptions.assumeTrue` quando não há GPU/driver Vulkan (CI, RFC/task) — inclusive quando
/// simulado por `-Dn3dsemu.vulkan.force-unavailable=true` (Aceite: "simule... numa máquina sem
/// Vulkan").
///
/// **Janela oculta (achado real, 2026-08-21)**: sem `n3dsemu.vulkan.hidden-window=true`, cada
/// `mvn test` abria uma janela real na tela desenhando o triângulo SINTÉTICO deste teste (RGB
/// hardcoded, nada a ver com nenhuma ROM) — confundiu mais de uma sessão de depuração do bug real
/// de `simple_tri.3dsx` (G5.1/`tasks/trilha-g-3ds/`), fazendo parecer que o EMULADOR tinha
/// funcionado quando era só este teste de fumaça passando. `VulkanRenderer` continua exercitando a
/// pilha real (instância/swapchain/*render pass*/apresentação); só a visibilidade da janela muda.
class VulkanRendererSmokeTest {
    private VulkanRenderer renderer;

    @BeforeEach
    void hideWindow() {
        System.setProperty("n3dsemu.vulkan.hidden-window", "true");
    }

    @AfterEach
    void closeRenderer() {
        if (renderer != null) {
            renderer.close();
        }
        System.clearProperty("n3dsemu.vulkan.hidden-window");
    }

    @Test
    void inicializaApresentaUmQuadroEFechaSemLancar() {
        try {
            renderer = new VulkanRenderer();
        } catch (VulkanUnavailableException e) {
            Assumptions.abort("Vulkan indisponível neste host: " + e.getMessage());
            return;
        }

        byte[] top = new byte[Screen.TOP.columns() * Screen.TOP.rows() * PixelFormat.RGB8.bytesPerPixel()];
        byte[] bottom = new byte[Screen.BOTTOM.columns() * Screen.BOTTOM.rows() * PixelFormat.RGB8.bytesPerPixel()];
        renderer.presentScreen(Screen.TOP, top, PixelFormat.RGB8, Screen.TOP.rows() * PixelFormat.RGB8.bytesPerPixel());
        renderer.presentScreen(Screen.BOTTOM, bottom, PixelFormat.RGB8, Screen.BOTTOM.rows() * PixelFormat.RGB8.bytesPerPixel());
        renderer.endFrame();
        renderer.pollEvents();
    }

    /// RFC-N3DSEMU G5/PR2: fumaça do pipeline de GEOMETRIA (novo nesta PR) contra o driver Vulkan
    /// real desta máquina — não valida a imagem (idem acima), só que
    /// `drawTriangles`/`createGeometryPipeline`/o *render pass* dedicado e o *vertex buffer*
    /// descartável por quadro não lançam em alguns quadros seguidos (para exercitar o
    /// `frameCleanup` de cada *frame in flight*).
    @Test
    void drawTrianglesRendersSeveralFramesWithoutThrowing() {
        try {
            renderer = new VulkanRenderer();
        } catch (VulkanUnavailableException e) {
            Assumptions.abort("Vulkan indisponível neste host: " + e.getMessage());
            return;
        }

        List<ShadedVertex> triangle = List.of(
                new ShadedVertex(-0.5f, -0.5f, 1f, 0f, 0f, 1f),
                new ShadedVertex(0.5f, -0.5f, 0f, 1f, 0f, 1f),
                new ShadedVertex(0f, 0.5f, 0f, 0f, 1f, 1f));
        // BOTTOM nunca recebe drawTriangles neste teste — precisa de um presentScreen próprio
        // (como o `Main` real sempre faz para as duas telas todo quadro, via FrameBufferState)
        // para que sua textura saia de VK_IMAGE_LAYOUT_UNDEFINED antes do render pass de
        // apresentação tentar amostrá-la.
        byte[] bottom = new byte[Screen.BOTTOM.columns() * Screen.BOTTOM.rows() * PixelFormat.RGB8.bytesPerPixel()];

        for (int frame = 0; frame < 4; frame++) {
            renderer.drawTriangles(Screen.TOP, triangle);
            renderer.presentScreen(Screen.BOTTOM, bottom, PixelFormat.RGB8,
                    Screen.BOTTOM.rows() * PixelFormat.RGB8.bytesPerPixel());
            renderer.endFrame();
            renderer.pollEvents();
        }
    }

    @Test
    void forceUnavailablePropertySimulaAusenciaDeVulkan() {
        System.setProperty("n3dsemu.vulkan.force-unavailable", "true");
        try {
            renderer = new VulkanRenderer();
            Assumptions.abort("esperava VulkanUnavailableException com force-unavailable=true");
        } catch (VulkanUnavailableException expected) {
            renderer = null;
        } finally {
            System.clearProperty("n3dsemu.vulkan.force-unavailable");
        }
    }
}
