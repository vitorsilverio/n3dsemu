package dev.vitorsilverio.n3dsemu.gpu.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// RFC-N3DSEMU G5/PR2: os 3 valores abaixo (`0x3f0000`, `0xbf0000`, `0x3b9999`) são os bytes
/// REAIS decodificados do `.shbin` do `simple_tri` (`.constf myconst(0.0, 1.0, -1.0, 0.1)`,
/// compilado pelo `picasso.exe`) — não são vetores inventados, ver `ShaderBinaryTest`.
class Float24Test {
    @Test
    void decodesZero() {
        assertEquals(0.0f, Float24.decode(0x000000));
    }

    @Test
    void decodesOnePointZeroFromRealShbinConstant() {
        assertEquals(1.0f, Float24.decode(0x3f0000));
    }

    @Test
    void decodesNegativeOneFromRealShbinConstant() {
        assertEquals(-1.0f, Float24.decode(0xbf0000));
    }

    @Test
    void decodesZeroPointOneFromRealShbinConstant() {
        assertEquals(0.1f, Float24.decode(0x3b9999), 1e-3f);
    }
}
