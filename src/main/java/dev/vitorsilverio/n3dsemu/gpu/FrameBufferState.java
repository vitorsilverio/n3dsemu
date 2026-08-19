package dev.vitorsilverio.n3dsemu.gpu;

import java.util.EnumMap;
import java.util.Map;

/// Buffer ativo mais recente de cada {@link Screen} (endereço no guest + stride + formato),
/// tal como o guest configurou por `GSPGPU_SetBufferSwap` (RFC-N3DSEMU G4 —
/// {@link dev.vitorsilverio.n3dsemu.service.GspGpuService}). O laço de apresentação (`Main`) lê
/// isto a cada VBlank para saber ONDE na memória do guest está o framebuffer de cada tela e em
/// que formato, antes de repassar para {@link PicaRenderer#presentScreen}.
public final class FrameBufferState {
    /// Um buffer ativo: endereço (no guest), bytes por coluna e formato de pixel.
    public record Buffer(int address, int stride, PixelFormat format) {
    }

    private final Map<Screen, Buffer> active = new EnumMap<>(Screen.class);

    public void update(Screen screen, int address, int stride, PixelFormat format) {
        active.put(screen, new Buffer(address, stride, format));
    }

    /// O buffer ativo de `screen`, ou `null` se o guest nunca chamou `SetBufferSwap` para essa
    /// tela ainda (comum logo após o boot, antes do primeiro `gfxSwapBuffers`).
    public Buffer active(Screen screen) {
        return active.get(screen);
    }
}
