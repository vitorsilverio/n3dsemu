package dev.vitorsilverio.n3dsemu.gpu;

/// Destino de desenho do subsistema gráfico do 3DS (RFC-N3DSEMU G4/D4).
///
/// Existe para que a máquina de estados da PICA200 (parser de listas de comando, registradores,
/// tradução de shader — G5) seja testável sem GPU: os testes usam
/// {@link dev.vitorsilverio.n3dsemu.gpu.RecordingRenderer}, e só o
/// {@link dev.vitorsilverio.n3dsemu.gpu.vulkan.VulkanRenderer} fala com hardware. Nenhum teste
/// automatizado valida o `VulkanRenderer` em si (RFC D4: "nenhuma task da trilha G pode ter como
/// aceite automatizado 'o triângulo apareceu'") — a validação é visual, pelo usuário.
public interface PicaRenderer {

    /// Entrega o conteúdo de uma tela para apresentação.
    ///
    /// `pixels` está no formato e na orientação do 3DS (varredura por coluna, origem no canto
    /// inferior esquerdo da tela em retrato) — a conversão para paisagem/RGBA8 é
    /// responsabilidade do renderer, não do chamador. `stride` é o número de bytes por COLUNA
    /// (pode ter padding além de `screen.rows() * format.bytesPerPixel()` — nunca presumir
    /// ausência de alinhamento).
    void presentScreen(Screen screen, byte[] pixels, PixelFormat format, int stride);

    /// Sinaliza o fim do quadro: o renderer apresenta o que recebeu desde o {@code endFrame}
    /// anterior.
    void endFrame();

    /// Libera os recursos do host.
    void close();
}
