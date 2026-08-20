package dev.vitorsilverio.n3dsemu.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// RFC-N3DSEMU G5/PR3: monta bytes de textura à mão (ordem Morton conhecida) e afirma o `RGBA8`
/// decodificado — mesma técnica de teste do resto da G5 (RFC D4: sem GPU).
class TextureTest {
    @Test
    void mortonIndexMatchesCornersOfAnEightByEightTile() {
        // (0,0) é sempre o primeiro texel do bloco; (7,7) é sempre o último (3dbrew/rotina de
        // referência: os 6 bits do índice ficam todos em 1 quando x=y=7).
        assertEquals(0, Texture.mortonIndexInTile(0, 0));
        assertEquals(63, Texture.mortonIndexInTile(7, 7));
        assertEquals(1, Texture.mortonIndexInTile(1, 0));
        assertEquals(2, Texture.mortonIndexInTile(0, 1));
    }

    @Test
    void decodesI8AsGrayscaleRespectingMortonOrder() {
        // Textura 8x8 I8 (1 byte/texel): só o texel morton-índice 0 (canto (0,0)) tem valor
        // 0x80 — se o deswizzle estivesse errado (linear), esse valor apareceria noutro pixel.
        byte[] data = new byte[64];
        data[0] = (byte) 0x80;

        byte[] rgba = Texture.decodeToRgba8(data, 8, 8, Texture.Format.I8);

        assertEquals((byte) 0x80, rgba[0]); // R do pixel (0,0)
        assertEquals((byte) 0x80, rgba[1]); // G
        assertEquals((byte) 0x80, rgba[2]); // B
        assertEquals((byte) 0xFF, rgba[3]); // A sempre opaco em I8
        // pixel (1,0): morton index 1 -> segundo byte da textura, ainda 0.
        assertEquals(0, rgba[4]);
    }

    @Test
    void decodesRgba8WithReversedByteOrderAndFullAlphaPrecision() {
        // RGBA8 grava A,B,G,R (ordem invertida do nome) no texel de índice morton 0.
        byte[] data = new byte[4 * 64];
        data[0] = (byte) 0xFF; // A
        data[1] = 0x02; // B
        data[2] = 0x03; // G
        data[3] = 0x04; // R

        byte[] rgba = Texture.decodeToRgba8(data, 8, 8, Texture.Format.RGBA8);

        assertEquals(0x04, rgba[0] & 0xFF); // R
        assertEquals(0x03, rgba[1] & 0xFF); // G
        assertEquals(0x02, rgba[2] & 0xFF); // B
        assertEquals(0xFF, rgba[3] & 0xFF); // A
    }

    @Test
    void decodesRgba5551WithFullBitAlphaNeverRoundedToZero() {
        // Armadilha citada na task: alpha de 1 bit não pode virar 0 por engano. Valor
        // 0b11111_11111_11111_1 = R=G=B=31(max),A=1(opaco).
        byte[] data = new byte[2 * 64];
        int value = 0b1111111111111111;
        data[0] = (byte) (value & 0xFF);
        data[1] = (byte) ((value >>> 8) & 0xFF);

        byte[] rgba = Texture.decodeToRgba8(data, 8, 8, Texture.Format.RGBA5551);

        assertEquals(0xFF, rgba[0] & 0xFF);
        assertEquals(0xFF, rgba[3] & 0xFF); // alpha opaco, não zerado
    }

    @Test
    void decodesFourBitFormatsPackedTwoTexelsPerByte() {
        // I4: 2 texels por byte (nibble baixo = primeiro texel morton, alto = segundo).
        byte[] data = new byte[32]; // 64 texels * 4 bits / 8 = 32 bytes
        data[0] = 0x1F; // nibble baixo = 0xF (texel morton 0), nibble alto = 0x1 (texel morton 1)

        byte[] rgba = Texture.decodeToRgba8(data, 8, 8, Texture.Format.I4);

        assertEquals(0xFF, rgba[0] & 0xFF); // intensidade do texel (0,0): nibble 0xF -> 255
        assertEquals(0x11, rgba[4] & 0xFF); // intensidade do texel (1,0): nibble 0x1 -> 0x11
    }
}
