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

    /// Desenha uma lista de triângulos já sombreados (RFC-N3DSEMU G5/PR2: saída do vertex shader
    /// interpretado, já com a divisão de perspectiva aplicada — ver {@link ShadedVertex}) na
    /// tela indicada. `vertices.size()` é múltiplo de 3 (lista de triângulos — sem *strip*/*fan*
    /// nesta PR, formato de primitiva mais comum e o único que o marco M5 usa). **Ainda sem
    /// TEV/texturas (PR3)**: a cor final é só a cor interpolada do vértice, sem combinador de
    /// nenhum estágio.
    void drawTriangles(Screen screen, java.util.List<ShadedVertex> vertices);

    /// Cor com que o *color buffer* daquela tela foi limpo pelo app (`GX_MemoryFill`, disparado
    /// por `C3D_RenderTargetClear`) — é o FUNDO sobre o qual {@link #drawTriangles} desenha.
    /// Componentes normalizados em `[0,1]`.
    void setClearColor(Screen screen, float[] rgba);

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
