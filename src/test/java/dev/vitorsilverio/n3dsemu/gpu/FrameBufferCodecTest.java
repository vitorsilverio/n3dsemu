package dev.vitorsilverio.n3dsemu.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// RFC-N3DSEMU G4: único jeito de validar a conversão de formato de pixel sem GPU/olho humano
/// (ver Javadoc de {@link FrameBufferCodec}) — framebuffer sintético 4×4 com uma cor conhecida
/// em cada canto, um teste por {@link PixelFormat}.
class FrameBufferCodecTest {
    private static final int COLUMNS = 4;
    private static final int ROWS = 4;

    @Test
    void rgba8PreservesExactBytesInReverseOrder() {
        PixelFormat format = PixelFormat.RGBA8;
        int stride = ROWS * format.bytesPerPixel();
        byte[] raw = new byte[COLUMNS * stride];
        // canto (col=0,row=0): vermelho opaco; memória = [A,B,G,R] (ver Javadoc de PixelFormat).
        writePixel(raw, 0, format, stride, 0xFF, 0x00, 0x00, 0xFF);
        byte[] rgba8 = FrameBufferCodec.decodeToRgba8(raw, COLUMNS, ROWS, format, stride);
        assertCorner(rgba8, 0, 0, 0xFF, 0x00, 0x00, 0xFF);
    }

    @Test
    void rgb8PreservesExactBytesInReverseOrderAndForcesOpaqueAlpha() {
        PixelFormat format = PixelFormat.RGB8;
        int stride = ROWS * format.bytesPerPixel();
        byte[] raw = new byte[COLUMNS * stride];
        // memória = [B,G,R].
        writePixel(raw, 0, format, stride, 0x00, 0xFF, 0x00);
        byte[] rgba8 = FrameBufferCodec.decodeToRgba8(raw, COLUMNS, ROWS, format, stride);
        assertCorner(rgba8, 0, 0, 0x00, 0xFF, 0x00, 0xFF);
    }

    @Test
    void rgb565ExpandsFullWhiteToFullRgba8White() {
        PixelFormat format = PixelFormat.RGB565;
        int stride = ROWS * format.bytesPerPixel();
        byte[] raw = new byte[COLUMNS * stride];
        writeU16LE(raw, 0, 0xFFFF); // R=0x1F,G=0x3F,B=0x1F
        byte[] rgba8 = FrameBufferCodec.decodeToRgba8(raw, COLUMNS, ROWS, format, stride);
        assertCorner(rgba8, 0, 0, 0xFF, 0xFF, 0xFF, 0xFF);
    }

    @Test
    void rgb5a1DecodesAlphaBitAndFivebitChannels() {
        PixelFormat format = PixelFormat.RGB5A1;
        int stride = ROWS * format.bytesPerPixel();
        byte[] raw = new byte[COLUMNS * stride];
        int value = (0x1F << 11) | (0x00 << 6) | (0x00 << 1) | 0x1; // vermelho puro, alpha=1
        writeU16LE(raw, 0, value);
        byte[] rgba8 = FrameBufferCodec.decodeToRgba8(raw, COLUMNS, ROWS, format, stride);
        assertCorner(rgba8, 0, 0, 0xFF, 0x00, 0x00, 0xFF);
    }

    @Test
    void rgba4DecodesFourBitChannels() {
        PixelFormat format = PixelFormat.RGBA4;
        int stride = ROWS * format.bytesPerPixel();
        byte[] raw = new byte[COLUMNS * stride];
        int value = (0x0 << 12) | (0xF << 8) | (0x0 << 4) | 0xF; // verde, alpha máximo
        writeU16LE(raw, 0, value);
        byte[] rgba8 = FrameBufferCodec.decodeToRgba8(raw, COLUMNS, ROWS, format, stride);
        assertCorner(rgba8, 0, 0, 0x00, 0xFF, 0x00, 0xFF);
    }

    @Test
    void preservesColumnMajorOrderWithoutTransposing() {
        PixelFormat format = PixelFormat.RGB8;
        int stride = ROWS * format.bytesPerPixel();
        byte[] raw = new byte[COLUMNS * stride];
        // 4 cantos do framebuffer em RETRATO: (col,row) = (0,0)/(0,3)/(3,0)/(3,3).
        // memória RGB8 = [B,G,R] (ver Javadoc de PixelFormat) — bytes abaixo já nessa ordem.
        writePixel(raw, columnRowOffset(0, 0, stride, format), format, stride, 0x00, 0x00, 0xFF); // topo-esq: vermelho
        writePixel(raw, columnRowOffset(0, 3, stride, format), format, stride, 0x00, 0xFF, 0x00); // baixo-esq: verde
        writePixel(raw, columnRowOffset(3, 0, stride, format), format, stride, 0xFF, 0x00, 0x00); // topo-dir: azul
        writePixel(raw, columnRowOffset(3, 3, stride, format), format, stride, 0x00, 0xFF, 0xFF); // baixo-dir: amarelo

        byte[] rgba8 = FrameBufferCodec.decodeToRgba8(raw, COLUMNS, ROWS, format, stride);

        assertCorner(rgba8, 0, 0, 0xFF, 0x00, 0x00, 0xFF);
        assertCorner(rgba8, 0, 3, 0x00, 0xFF, 0x00, 0xFF);
        assertCorner(rgba8, 3, 0, 0x00, 0x00, 0xFF, 0xFF);
        assertCorner(rgba8, 3, 3, 0xFF, 0xFF, 0x00, 0xFF);
    }

    @Test
    void rejectsStrideSmallerThanNeeded() {
        PixelFormat format = PixelFormat.RGB8;
        byte[] raw = new byte[COLUMNS * ROWS * format.bytesPerPixel()];
        int tooSmallStride = ROWS * format.bytesPerPixel() - 1;
        assertThrowsIllegalArgument(() -> FrameBufferCodec.decodeToRgba8(raw, COLUMNS, ROWS, format, tooSmallStride));
    }

    @Test
    void recordingRendererDecodesThroughTheSameCodec() {
        PixelFormat format = PixelFormat.RGB565;
        Screen screen = Screen.TOP;
        int stride = screen.rows() * format.bytesPerPixel();
        byte[] raw = new byte[screen.columns() * stride];
        writeU16LE(raw, 0, 0xFFFF);

        RecordingRenderer renderer = new RecordingRenderer();
        renderer.presentScreen(screen, raw, format, stride);
        renderer.endFrame();

        byte[] expected = FrameBufferCodec.decodeToRgba8(raw, screen, format, stride);
        assertArrayEquals(expected, renderer.lastRgba8(screen));
        assertEquals(1, renderer.frameCount());
    }

    private static int columnRowOffset(int col, int row, int stride, PixelFormat format) {
        return col * stride + row * format.bytesPerPixel();
    }

    private static void writePixel(byte[] raw, int offset, PixelFormat format, int stride, int... memoryOrderBytes) {
        for (int i = 0; i < memoryOrderBytes.length; i++) {
            raw[offset + i] = (byte) memoryOrderBytes[i];
        }
    }

    private static void writeU16LE(byte[] raw, int offset, int value) {
        raw[offset] = (byte) value;
        raw[offset + 1] = (byte) (value >>> 8);
    }

    private static void assertCorner(byte[] rgba8, int col, int row, int r, int g, int b, int a) {
        int offset = (col * ROWS + row) * 4;
        assertEquals(r, rgba8[offset] & 0xFF, "R");
        assertEquals(g, rgba8[offset + 1] & 0xFF, "G");
        assertEquals(b, rgba8[offset + 2] & 0xFF, "B");
        assertEquals(a, rgba8[offset + 3] & 0xFF, "A");
    }

    private static void assertThrowsIllegalArgument(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("esperava IllegalArgumentException");
    }
}
