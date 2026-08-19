package dev.vitorsilverio.n3dsemu.gpu;

/// Os 5 formatos de framebuffer do 3DS
/// (<a href="https://www.3dbrew.org/wiki/GPU/External_Registers">3dbrew: GPU External
/// Registers</a>, campo de 3 bits do registro `0x1EF00470`) — o `consoleInit()` do libctru usa
/// `RGB565` ou `RGB8` dependendo da inicialização (RFC-N3DSEMU G4).
///
/// **Ordem de bytes na memória (3dbrew, citado literalmente): "color components are laid out in
/// reverse byte order, with the most significant bits used first (i.e. non-24-bit pixels are
/// stored as little-endian values)."** Ou seja, o nome do formato dá a ordem de bits do MAIS para
/// o MENOS significativo, e a codificação em bytes é essa mesma sequência invertida — para
/// `RGBA8`, memória `[A,B,G,R]`; para `RGB8`, memória `[B,G,R]`; para os formatos de 16 bits, o
/// valor `u16` little-endian de bits `RRRR..AAAA` (MSB→LSB na ordem do nome).
public enum PixelFormat {
    RGBA8(0, 4),
    RGB8(1, 3),
    RGB565(2, 2),
    RGB5A1(3, 2),
    RGBA4(4, 2);

    private static final int CODE_MASK = 0x7;

    private final int code;
    private final int bytesPerPixel;

    PixelFormat(int code, int bytesPerPixel) {
        this.code = code;
        this.bytesPerPixel = bytesPerPixel;
    }

    public int code() {
        return code;
    }

    public int bytesPerPixel() {
        return bytesPerPixel;
    }

    /// Decodifica os 3 bits baixos do campo "format" do `GSPGPU_FramebufferInfo`
    /// (`SetBufferSwap`/GSP shared memory) ou do registro `0x1EF00470`.
    public static PixelFormat fromCode(int rawFormat) {
        int code = rawFormat & CODE_MASK;
        for (PixelFormat format : values()) {
            if (format.code == code) {
                return format;
            }
        }
        throw new IllegalArgumentException("formato de pixel desconhecido: " + code);
    }
}
