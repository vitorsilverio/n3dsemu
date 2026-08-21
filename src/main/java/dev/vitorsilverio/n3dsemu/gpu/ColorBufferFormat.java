package dev.vitorsilverio.n3dsemu.gpu;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

/// Formato do *color buffer* da PICA200 (`GPUREG_COLORBUFFER_FORMAT`, bits 16-18) — RFC-N3DSEMU
/// G5.3. **Não confundir com {@link PixelFormat}**, que é o formato do framebuffer de
/// APRESENTAÇÃO (`GSPGPU_FramebufferFormat`): as duas enumerações têm os mesmos 5 formatos mas em
/// ORDEM DIFERENTE (os códigos `2` e `3` são trocados entre elas), então reaproveitar uma no lugar
/// da outra decodifica a cor errada.
///
/// A ordem dos componentes na memória é a inversa da do nome (Citra, `regs_framebuffer.h`:
/// "components are laid out in reverse byte order, most significant bits first") — lendo a palavra
/// como *little-endian*, o vermelho fica nos bits ALTOS.
public enum ColorBufferFormat {
    RGBA8(4),
    RGB8(3),
    RGB5A1(2),
    RGB565(2),
    RGBA4(2);

    /// Bits 16-18 de `GPUREG_COLORBUFFER_FORMAT`.
    public static final int REGISTER = 0x117;
    /// `GPUREG_COLORBUFFER_LOC` — endereço FÍSICO do *color buffer*, deslocado 3 bits à direita.
    public static final int REGISTER_LOCATION = 0x11D;
    private static final int FORMAT_SHIFT = 16;
    private static final int FORMAT_MASK = 0x7;
    private static final int LOCATION_SHIFT = 3;

    private static final float MAX_5_BIT = 31f;
    private static final float MAX_6_BIT = 63f;
    private static final float MAX_4_BIT = 15f;
    private static final float MAX_8_BIT = 255f;

    private final int bytesPerPixel;

    ColorBufferFormat(int bytesPerPixel) {
        this.bytesPerPixel = bytesPerPixel;
    }

    public int bytesPerPixel() {
        return bytesPerPixel;
    }

    public static ColorBufferFormat fromRegister(int colorBufferFormatRegister) {
        int code = (colorBufferFormatRegister >>> FORMAT_SHIFT) & FORMAT_MASK;
        ColorBufferFormat[] byCode = values();
        return code < byCode.length ? byCode[code] : RGBA8;
    }

    /// Endereço do *color buffer* na memória do guest (o registrador guarda o endereço FÍSICO
    /// dividido por 8 — as janelas física e virtual da VRAM espelham as mesmas páginas, ver
    /// `N3dsAddressSpace`).
    public static int locationFromRegister(int colorBufferLocationRegister) {
        return colorBufferLocationRegister << LOCATION_SHIFT;
    }

    /// Lê UM pixel do *color buffer* e devolve `{r,g,b,a}` normalizado em `[0,1]`.
    ///
    /// Usado para descobrir com que cor o app limpou a tela: depois de um `GX_MemoryFill` o buffer
    /// inteiro tem o mesmo valor, então o pixel do começo já É a cor de fundo do quadro — mais
    /// simples e mais fiel do que tentar casar o endereço do preenchimento com o da tela.
    public float[] readPixel(AddressSpace memory, int address) {
        return switch (this) {
            case RGBA8 -> {
                int word = memory.read32(address);
                yield new float[]{
                        ((word >>> 24) & 0xFF) / MAX_8_BIT,
                        ((word >>> 16) & 0xFF) / MAX_8_BIT,
                        ((word >>> 8) & 0xFF) / MAX_8_BIT,
                        (word & 0xFF) / MAX_8_BIT};
            }
            case RGB8 -> new float[]{
                    (memory.read8(address + 2) & 0xFF) / MAX_8_BIT,
                    (memory.read8(address + 1) & 0xFF) / MAX_8_BIT,
                    (memory.read8(address) & 0xFF) / MAX_8_BIT,
                    1f};
            case RGB5A1 -> {
                int half = memory.read16(address) & 0xFFFF;
                yield new float[]{
                        ((half >>> 11) & 0x1F) / MAX_5_BIT,
                        ((half >>> 6) & 0x1F) / MAX_5_BIT,
                        ((half >>> 1) & 0x1F) / MAX_5_BIT,
                        half & 1};
            }
            case RGB565 -> {
                int half = memory.read16(address) & 0xFFFF;
                yield new float[]{
                        ((half >>> 11) & 0x1F) / MAX_5_BIT,
                        ((half >>> 5) & 0x3F) / MAX_6_BIT,
                        (half & 0x1F) / MAX_5_BIT,
                        1f};
            }
            case RGBA4 -> {
                int half = memory.read16(address) & 0xFFFF;
                yield new float[]{
                        ((half >>> 12) & 0xF) / MAX_4_BIT,
                        ((half >>> 8) & 0xF) / MAX_4_BIT,
                        ((half >>> 4) & 0xF) / MAX_4_BIT,
                        (half & 0xF) / MAX_4_BIT};
            }
        };
    }
}
