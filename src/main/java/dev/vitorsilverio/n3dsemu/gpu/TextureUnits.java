package dev.vitorsilverio.n3dsemu.gpu;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

/// Decodifica as **unidades de textura** do PICA200 (`GPUREG_TEXUNIT*`) e lê a textura de cada uma
/// da memória do guest — RFC-N3DSEMU G5/PR4.
///
/// São 3 unidades, com blocos de registradores em offsets irregulares (3dbrew "GPU/Internal
/// Registers"; layout conferido contra o `TexturingRegs` do Citra, `video_core/pica/regs_texturing.h`).
/// O endereço é FÍSICO deslocado 3 bits (as janelas física e virtual espelham as mesmas páginas,
/// ver `N3dsAddressSpace`), e os dados estão em blocos 8×8 na ordem de Morton — o desembaralho é
/// {@link Texture#decodeToRgba8}.
public final class TextureUnits {
    public static final int UNIT_COUNT = 3;

    /// `GPUREG_TEXUNIT_CONFIG` — bits 0/1/2 habilitam as unidades 0/1/2.
    private static final int REG_CONFIG = 0x080;
    /// Offsets, por unidade, de `dimensão`/`parâmetros`/`endereço`/`formato`. Não são regulares:
    /// a unidade 0 tem 4 endereços extras (faces de *cubemap*) entre `address` e `format`.
    private static final int[] REG_DIMENSION = {0x082, 0x092, 0x09A};
    private static final int[] REG_ADDRESS = {0x085, 0x095, 0x09D};
    private static final int[] REG_FORMAT = {0x08E, 0x096, 0x09E};

    private static final int DIMENSION_MASK = 0xFFFF;
    private static final int HEIGHT_SHIFT = 0;
    private static final int WIDTH_SHIFT = 16;
    private static final int ADDRESS_SHIFT = 3;
    private static final int FORMAT_MASK = 0xF;
    private static final int TILE_SIZE = 8;
    private static final int BITS_PER_BYTE = 8;

    private TextureUnits() {
    }

    /// `true` se a unidade `unit` está habilitada em `GPUREG_TEXUNIT_CONFIG`.
    public static boolean isEnabled(PicaRegisters registers, int unit) {
        return ((registers.read(REG_CONFIG) >>> unit) & 1) != 0;
    }

    /// Lê a textura da unidade `unit` já em `RGBA8` linear, ou `null` se a unidade está desligada,
    /// tem dimensão inválida, ou usa um formato comprimido (`ETC1`/`ETC1A4`, fora do escopo da
    /// G5 — RFC/task: "implemente os não comprimidos primeiro").
    public static PicaTexture read(PicaRegisters registers, AddressSpace memory, int unit) {
        if (!isEnabled(registers, unit)) {
            return null;
        }
        int dimension = registers.read(REG_DIMENSION[unit]);
        int width = (dimension >>> WIDTH_SHIFT) & DIMENSION_MASK;
        int height = (dimension >>> HEIGHT_SHIFT) & DIMENSION_MASK;
        if (width <= 0 || height <= 0 || width % TILE_SIZE != 0 || height % TILE_SIZE != 0) {
            return null;
        }
        Texture.Format format = decodeFormat(registers.read(REG_FORMAT[unit]));
        if (format == null) {
            return null;
        }
        int address = registers.read(REG_ADDRESS[unit]) << ADDRESS_SHIFT;
        if (address == 0) {
            return null;
        }
        byte[] raw = new byte[width * height * format.bitsPerPixel() / BITS_PER_BYTE];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) memory.read8(address + i);
        }
        return new PicaTexture(width, height, Texture.decodeToRgba8(raw, width, height, format));
    }

    /// Códigos `0`-`11` batem 1:1 com a ordem de {@link Texture.Format}; `12`/`13` são
    /// `ETC1`/`ETC1A4` (comprimidos, ainda não suportados) e devolvem `null`.
    private static Texture.Format decodeFormat(int formatRegister) {
        int code = formatRegister & FORMAT_MASK;
        Texture.Format[] formats = Texture.Format.values();
        return code < formats.length ? formats[code] : null;
    }
}
