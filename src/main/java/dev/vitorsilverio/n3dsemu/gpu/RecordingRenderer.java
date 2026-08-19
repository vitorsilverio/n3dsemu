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
    private int frameCount;
    private boolean closed;

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
