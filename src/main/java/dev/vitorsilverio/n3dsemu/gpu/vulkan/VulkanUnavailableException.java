package dev.vitorsilverio.n3dsemu.gpu.vulkan;

/// Lançada quando a janela/instância Vulkan não pode ser criada (sem GPU/driver — o caso do
/// runner do GitHub Actions, RFC-N3DSEMU G4: "o runner do GitHub não tem GPU/driver Vulkan").
/// Quem constrói {@link VulkanRenderer} em teste automatizado deve capturar isto e pular via
/// `Assumptions.assumeTrue` — nunca falhar a suíte por falta de GPU.
public final class VulkanUnavailableException extends RuntimeException {
    public VulkanUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public VulkanUnavailableException(String message) {
        super(message);
    }
}
