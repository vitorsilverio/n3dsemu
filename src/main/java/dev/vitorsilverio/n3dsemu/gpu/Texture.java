package dev.vitorsilverio.n3dsemu.gpu;

/// Decodifica texturas do PICA200 (RFC-N3DSEMU G5/PR3) — só os formatos **não comprimidos**
/// (`Inclui`/task: "implemente os não comprimidos primeiro"; `ETC1`/`ETC1A4` ficam para task
/// futura, nenhum exemplo do marco M5 os usa).
///
/// **Armadilha grande (task/Armadilhas):** os texels NÃO ficam em ordem linear — são organizados
/// em blocos de 8×8 em curva de Morton (*Z-order*), transcrito da rotina de referência amplamente
/// documentada para o PICA200 (`MortonInterleave`/`GetMortonOffset`, usada por emuladores de 3DS
/// publicamente conhecidos): dentro de cada bloco 8×8, os 3 bits baixos de X e de Y são
/// entrelaçados bit a bit (X nos bits pares, Y nos ímpares) para formar o índice do texel dentro
/// do bloco; os blocos em si ficam em ordem linha-maior. Descompactar como se fosse linear produz
/// a imagem característica "embaralhada em quadradinhos" citada na task.
public final class Texture {
    private static final int TILE_SIZE = 8;
    private static final int TEXELS_PER_TILE = TILE_SIZE * TILE_SIZE;

    /// `xlut`/`ylut`: espalha os 3 bits de X (bits pares do índice de 6 bits) e de Y (bits
    /// ímpares) — mesma tabela usada pela rotina de referência `MortonInterleave` (transcrita, não
    /// derivada por fórmula, para casar bit a bit com o hardware/outros decoders).
    private static final int[] MORTON_X_LUT = {0x00, 0x01, 0x04, 0x05, 0x10, 0x11, 0x14, 0x15};
    private static final int[] MORTON_Y_LUT = {0x00, 0x02, 0x08, 0x0A, 0x20, 0x22, 0x28, 0x2A};

    /// Formatos de textura do PICA200 (3dbrew: "Texture Formats") — só os não comprimidos
    /// (RFC/task).
    public enum Format {
        RGBA8(32), RGB8(24), RGBA5551(16), RGB565(16), RGBA4(16), IA8(16), RG8(16), I8(8), A8(8), IA4(8), I4(4), A4(4);

        private final int bitsPerPixel;

        Format(int bitsPerPixel) {
            this.bitsPerPixel = bitsPerPixel;
        }

        public int bitsPerPixel() {
            return bitsPerPixel;
        }
    }

    private Texture() {
    }

    /// Índice do texel (0-63) dentro do bloco 8×8 ao qual `(localX, localY)` pertence (0-7 cada) —
    /// ver Javadoc da classe.
    public static int mortonIndexInTile(int localX, int localY) {
        return MORTON_X_LUT[localX & 0x7] + MORTON_Y_LUT[localY & 0x7];
    }

    /// Decodifica `data` (formato `format`, dimensões `width`×`height`, ambas múltiplas de 8 — o
    /// PICA200 exige textura em blocos completos) para `RGBA8` linear (linha a linha,
    /// `width*height*4` bytes, mesma convenção de {@link FrameBufferCodec}).
    public static byte[] decodeToRgba8(byte[] data, int width, int height, Format format) {
        byte[] out = new byte[width * height * 4];
        int tilesPerRow = width / TILE_SIZE;
        for (int y = 0; y < height; y++) {
            int tileRow = y / TILE_SIZE;
            int localY = y % TILE_SIZE;
            for (int x = 0; x < width; x++) {
                int tileCol = x / TILE_SIZE;
                int localX = x % TILE_SIZE;
                int tileIndex = tileRow * tilesPerRow + tileCol;
                long bitOffset = (long) tileIndex * TEXELS_PER_TILE * format.bitsPerPixel()
                        + (long) mortonIndexInTile(localX, localY) * format.bitsPerPixel();
                int raw = readBits(data, bitOffset, format.bitsPerPixel());
                int rgba = decodeTexel(raw, format);
                int outIndex = (y * width + x) * 4;
                out[outIndex] = (byte) ((rgba >>> 24) & 0xFF); // R
                out[outIndex + 1] = (byte) ((rgba >>> 16) & 0xFF); // G
                out[outIndex + 2] = (byte) ((rgba >>> 8) & 0xFF); // B
                out[outIndex + 3] = (byte) (rgba & 0xFF); // A
            }
        }
        return out;
    }

    /// Lê `bitCount` bits crus a partir de `bitOffset` (little-endian dentro de cada byte,
    /// byte menos significativo primeiro) — genérico o bastante para formatos de 4 a 32 bits sem
    /// duplicar a lógica de alinhamento por formato (RFC/task: "correção primeiro", não
    /// desempenho).
    private static int readBits(byte[] data, long bitOffset, int bitCount) {
        int value = 0;
        for (int i = 0; i < bitCount; i++) {
            long bit = bitOffset + i;
            int byteIndex = (int) (bit / 8);
            int bitInByte = (int) (bit % 8);
            int b = byteIndex < data.length ? (data[byteIndex] >> bitInByte) & 1 : 0;
            value |= b << i;
        }
        return value;
    }

    /// Retorna a cor decodificada empacotada como `R<<24 | G<<16 | B<<8 | A`. Ordem de bytes na
    /// memória por formato (3dbrew "Texture Formats" + convenção amplamente documentada de
    /// decoders de PICA200 públicos — canais na ordem inversa do nome, ex.: `RGBA8` grava
    /// `A,B,G,R`): confirmado nesta sessão só para os formatos abaixo, sem GPU real disponível
    /// para validar visualmente (RFC D4) — a armadilha citada na task ("`RGB565`/`RGBA5551` erram
    /// fácil o canal alpha") foi tratada dando 1 bit inteiro a alpha em `RGBA5551`, nunca
    /// arredondando para 0.
    private static int decodeTexel(int raw, Format format) {
        return switch (format) {
            case RGBA8 -> ((raw >>> 24 & 0xFF) << 24) | ((raw >>> 16 & 0xFF) << 16) | ((raw >>> 8 & 0xFF) << 8) | (raw & 0xFF);
            case RGB8 -> ((raw >>> 16 & 0xFF) << 24) | ((raw >>> 8 & 0xFF) << 16) | ((raw & 0xFF) << 8) | 0xFF;
            case RGBA5551 -> {
                int r = expand5((raw >>> 11) & 0x1F);
                int g = expand5((raw >>> 6) & 0x1F);
                int b = expand5((raw >>> 1) & 0x1F);
                int a = (raw & 0x1) != 0 ? 0xFF : 0x00;
                yield (r << 24) | (g << 16) | (b << 8) | a;
            }
            case RGB565 -> {
                int r = expand5((raw >>> 11) & 0x1F);
                int g = expand6((raw >>> 5) & 0x3F);
                int b = expand5(raw & 0x1F);
                yield (r << 24) | (g << 16) | (b << 8) | 0xFF;
            }
            case RGBA4 -> {
                int r = expand4((raw >>> 12) & 0xF);
                int g = expand4((raw >>> 8) & 0xF);
                int b = expand4((raw >>> 4) & 0xF);
                int a = expand4(raw & 0xF);
                yield (r << 24) | (g << 16) | (b << 8) | a;
            }
            case IA8 -> {
                int intensity = (raw >>> 8) & 0xFF;
                int alpha = raw & 0xFF;
                yield (intensity << 24) | (intensity << 16) | (intensity << 8) | alpha;
            }
            case RG8 -> {
                int r = (raw >>> 8) & 0xFF;
                int g = raw & 0xFF;
                yield (r << 24) | (g << 16) | (0 << 8) | 0xFF;
            }
            case I8 -> {
                int intensity = raw & 0xFF;
                yield (intensity << 24) | (intensity << 16) | (intensity << 8) | 0xFF;
            }
            case A8 -> (0xFF << 24) | (0xFF << 16) | (0xFF << 8) | (raw & 0xFF);
            case IA4 -> {
                int intensity = expand4((raw >>> 4) & 0xF);
                int alpha = expand4(raw & 0xF);
                yield (intensity << 24) | (intensity << 16) | (intensity << 8) | alpha;
            }
            case I4 -> {
                int intensity = expand4(raw & 0xF);
                yield (intensity << 24) | (intensity << 16) | (intensity << 8) | 0xFF;
            }
            case A4 -> (0xFF << 24) | (0xFF << 16) | (0xFF << 8) | expand4(raw & 0xF);
        };
    }

    private static int expand4(int nibble) {
        return (nibble << 4) | nibble;
    }

    private static int expand5(int v5) {
        return (v5 << 3) | (v5 >>> 2);
    }

    private static int expand6(int v6) {
        return (v6 << 2) | (v6 >>> 4);
    }
}
