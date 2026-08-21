package dev.vitorsilverio.n3dsemu.gpu;

import java.util.EnumMap;
import java.util.Map;

/// {@link PicaRenderer} de teste (RFC-N3DSEMU G4/D4): não fala com GPU nenhuma. Roda o MESMO
/// {@link FrameBufferCodec} que o {@link dev.vitorsilverio.n3dsemu.gpu.vulkan.VulkanRenderer}
/// usaria para montar a textura de upload e grava o resultado — é o que permite testar a
/// conversão de formato de pixel (a parte determinística/sem GPU da apresentação, ver Javadoc de
/// {@link FrameBufferCodec}) sem olho humano nem driver Vulkan.
public final class RecordingRenderer implements PicaRenderer {
    private final Map<Screen, byte[]> lastRgba8 = new EnumMap<>(Screen.class);
    private final Map<Screen, java.util.List<ShadedVertex>> lastTriangles = new EnumMap<>(Screen.class);
    private final Map<Screen, float[]> lastClearColor = new EnumMap<>(Screen.class);
    private final PicaTexture[] textures = new PicaTexture[TextureUnits.UNIT_COUNT];
    private dev.vitorsilverio.n3dsemu.gpu.tev.TevConfig tevConfig;
    private int frameCount;
    private int drawCallCount;
    private int vertexCount;
    private boolean closed;

    @Override
    public void drawTriangles(Screen screen, java.util.List<ShadedVertex> vertices) {
        lastTriangles.put(screen, java.util.List.copyOf(vertices));
        drawCallCount++;
        vertexCount += vertices.size();
    }

    /// Resumo do que a GPU emulada produziu — usado pelo `--report` do `Main` para levantar, sem
    /// GPU nem olho humano, quão longe cada exemplo chega (RFC/task G5: a tabela dos 20 exemplos
    /// de `graphics/gpu` é item de aceite).
    public String report() {
        StringBuilder summary = new StringBuilder();
        summary.append("desenhos=").append(drawCallCount)
                .append(" vertices=").append(vertexCount)
                .append(" quadros=").append(frameCount);
        for (Screen screen : Screen.values()) {
            float[] clear = lastClearColor.get(screen);
            if (clear != null) {
                summary.append(' ').append(screen).append("-fundo=#")
                        .append(String.format("%02X%02X%02X", (int) (clear[0] * 255), (int) (clear[1] * 255),
                                (int) (clear[2] * 255)));
            }
        }
        for (int unit = 0; unit < textures.length; unit++) {
            if (textures[unit] != null) {
                summary.append(" tex").append(unit).append('=')
                        .append(textures[unit].width()).append('x').append(textures[unit].height());
            }
        }
        return summary.toString();
    }

    /// Última lista de triângulos recebida para `screen` (RFC G5/PR2), ou `null` se
    /// {@link #drawTriangles} nunca foi chamado para essa tela.
    public java.util.List<ShadedVertex> lastTriangles(Screen screen) {
        return lastTriangles.get(screen);
    }

    @Override
    public void setTevConfig(dev.vitorsilverio.n3dsemu.gpu.tev.TevConfig config) {
        this.tevConfig = config;
    }

    /// Última {@link dev.vitorsilverio.n3dsemu.gpu.tev.TevConfig} recebida, ou `null`.
    public dev.vitorsilverio.n3dsemu.gpu.tev.TevConfig tevConfig() {
        return tevConfig;
    }

    @Override
    public void setTexture(int unit, PicaTexture texture) {
        textures[unit] = texture;
    }

    /// Última textura recebida na unidade `unit`, ou `null`.
    public PicaTexture texture(int unit) {
        return textures[unit];
    }

    @Override
    public void setClearColor(Screen screen, float[] rgba) {
        lastClearColor.put(screen, rgba.clone());
    }

    /// Última cor de limpeza recebida para `screen` (RFC G5.3), ou `null` se nenhuma.
    public float[] lastClearColor(Screen screen) {
        return lastClearColor.get(screen);
    }

    @Override
    public void presentScreen(Screen screen, byte[] pixels, PixelFormat format, int stride) {
        lastRgba8.put(screen, FrameBufferCodec.decodeToRgba8(pixels, screen, format, stride));
    }

    @Override
    public void endFrame() {
        frameCount++;
    }

    @Override
    public void close() {
        closed = true;
    }

    /// Último framebuffer recebido para `screen`, já decodificado para `RGBA8` (coluna-a-coluna,
    /// sem transpor — ver Javadoc da classe), ou `null` se nenhum foi entregue ainda.
    public byte[] lastRgba8(Screen screen) {
        return lastRgba8.get(screen);
    }

    public int frameCount() {
        return frameCount;
    }

    public boolean closed() {
        return closed;
    }
}
