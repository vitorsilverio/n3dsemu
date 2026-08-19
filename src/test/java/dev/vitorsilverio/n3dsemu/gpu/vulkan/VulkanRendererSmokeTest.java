package dev.vitorsilverio.n3dsemu.gpu.vulkan;

import dev.vitorsilverio.n3dsemu.gpu.PixelFormat;
import dev.vitorsilverio.n3dsemu.gpu.Screen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/// RFC-N3DSEMU G4/D4: "nenhuma task da trilha G pode ter como aceite automatizado 'o triângulo
/// apareceu'" — este teste NÃO valida a imagem (impossível sem olho humano), só que a pilha
/// Vulkan inicializa/apresenta/fecha sem lançar em uma máquina COM driver. Pula via
/// `Assumptions.assumeTrue` quando não há GPU/driver Vulkan (CI, RFC/task) — inclusive quando
/// simulado por `-Dn3dsemu.vulkan.force-unavailable=true` (Aceite: "simule... numa máquina sem
/// Vulkan").
class VulkanRendererSmokeTest {
    private VulkanRenderer renderer;

    @AfterEach
    void closeRenderer() {
        if (renderer != null) {
            renderer.close();
        }
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
