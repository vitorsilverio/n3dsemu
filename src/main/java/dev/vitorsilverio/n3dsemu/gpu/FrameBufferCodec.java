package dev.vitorsilverio.n3dsemu.gpu;

/// Converte um framebuffer bruto do guest (qualquer {@link PixelFormat}) para `RGBA8` — o
/// primeiro dos dois passos de apresentação da RFC-N3DSEMU G4 ("ler o framebuffer... converter o
/// formato de pixel para RGBA8..."). **Não transpõe** — a orientação (retrato→paisagem) fica só
/// no shader de apresentação do {@link dev.vitorsilverio.n3dsemu.gpu.vulkan.VulkanRenderer}
/// (RFC/task: "não em laço Java"); esta classe preserva a ordem coluna-a-coluna de entrada,
/// então é o único pedaço da conversão que dá para testar sem GPU/olho humano — usado tanto pelo
/// `VulkanRenderer` (para montar a textura de upload) quanto por
/// {@link RecordingRenderer} (para os testes exercitarem a mesma lógica de decodificação real).
public final class FrameBufferCodec {
    private static final int RGBA8_BYTES = 4;
    private static final int ALPHA_OPAQUE = 0xFF;

    private FrameBufferCodec() {
    }

    /// Como {@link #decodeToRgba8(byte[], int, int, PixelFormat, int)}, usando as dimensões reais
    /// de `screen` (uso em produção — {@link RecordingRenderer}/`VulkanRenderer`).
    public static byte[] decodeToRgba8(byte[] raw, Screen screen, PixelFormat format, int stride) {
        return decodeToRgba8(raw, screen.columns(), screen.rows(), format, stride);
    }

    /// Decodifica `raw` (`columns` colunas de `stride` bytes cada, `rows` pixels úteis por
    /// coluna, no formato `format`) para um buffer `RGBA8` do mesmo tamanho lógico
    /// (`columns * rows * 4` bytes), na MESMA ordem coluna-a-coluna — sem transpor. Dimensões
    /// explícitas (em vez de só {@link Screen}) para permitir testar com um framebuffer
    /// sintético pequeno.
    public static byte[] decodeToRgba8(byte[] raw, int columns, int rows, PixelFormat format, int stride) {
        int bpp = format.bytesPerPixel();
        if (stride < rows * bpp) {
            throw new IllegalArgumentException(
                    "stride menor que o necessário: " + stride + " < " + (rows * bpp));
        }
        if (raw.length < (long) (columns - 1) * stride + rows * bpp) {
            throw new IllegalArgumentException("buffer bruto menor que columns*stride");
        }
        byte[] rgba8 = new byte[columns * rows * RGBA8_BYTES];
        for (int col = 0; col < columns; col++) {
            int columnBase = col * stride;
            for (int row = 0; row < rows; row++) {
                int srcOffset = columnBase + row * bpp;
                int dstOffset = (col * rows + row) * RGBA8_BYTES;
                decodePixel(raw, srcOffset, format, rgba8, dstOffset);
            }
        }
        return rgba8;
    }

    private static void decodePixel(byte[] raw, int srcOffset, PixelFormat format, byte[] dst, int dstOffset) {
        switch (format) {
            case RGBA8 -> {
                // memória: [A,B,G,R] (PixelFormat: "reverse byte order" — ver Javadoc daquela classe)
                int a = raw[srcOffset] & 0xFF;
                int b = raw[srcOffset + 1] & 0xFF;
                int g = raw[srcOffset + 2] & 0xFF;
                int r = raw[srcOffset + 3] & 0xFF;
                writeRgba8(dst, dstOffset, r, g, b, a);
            }
            case RGB8 -> {
                // memória: [B,G,R]
                int b = raw[srcOffset] & 0xFF;
                int g = raw[srcOffset + 1] & 0xFF;
                int r = raw[srcOffset + 2] & 0xFF;
                writeRgba8(dst, dstOffset, r, g, b, ALPHA_OPAQUE);
            }
            case RGB565 -> {
                int value = readU16LE(raw, srcOffset);
                int r = expand(bits(value, 11, 5), 5);
                int g = expand(bits(value, 5, 6), 6);
                int b = expand(bits(value, 0, 5), 5);
                writeRgba8(dst, dstOffset, r, g, b, ALPHA_OPAQUE);
            }
            case RGB5A1 -> {
                int value = readU16LE(raw, srcOffset);
                int r = expand(bits(value, 11, 5), 5);
                int g = expand(bits(value, 6, 5), 5);
                int b = expand(bits(value, 1, 5), 5);
                int a = bits(value, 0, 1) != 0 ? 0xFF : 0x00;
                writeRgba8(dst, dstOffset, r, g, b, a);
            }
            case RGBA4 -> {
                int value = readU16LE(raw, srcOffset);
                int r = expand(bits(value, 12, 4), 4);
                int g = expand(bits(value, 8, 4), 4);
                int b = expand(bits(value, 4, 4), 4);
                int a = expand(bits(value, 0, 4), 4);
                writeRgba8(dst, dstOffset, r, g, b, a);
            }
        }
    }

    private static int bits(int value, int shift, int width) {
        return (value >>> shift) & ((1 << width) - 1);
    }

    /// Expande um campo de `width` bits para 8 bits replicando os bits mais significativos —
    /// mesma técnica padrão de decodificação de RGB565/RGB5A1/RGBA4 (evita escurecer o branco:
    /// `0x1F` de 5 bits vira `0xFF`, não `0xF8`).
    private static int expand(int value, int width) {
        return (value << (8 - width)) | (value >>> (2 * width - 8));
    }

    private static int readU16LE(byte[] raw, int offset) {
        return (raw[offset] & 0xFF) | ((raw[offset + 1] & 0xFF) << 8);
    }

    private static void writeRgba8(byte[] dst, int offset, int r, int g, int b, int a) {
        dst[offset] = (byte) r;
        dst[offset + 1] = (byte) g;
        dst[offset + 2] = (byte) b;
        dst[offset + 3] = (byte) a;
    }
}
