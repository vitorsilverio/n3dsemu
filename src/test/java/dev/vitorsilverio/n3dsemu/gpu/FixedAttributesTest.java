package dev.vitorsilverio.n3dsemu.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/// Atributos de vértice FIXOS (`GPUREG_FIXEDATTRIB_*`) — o caminho que dá cor ao triângulo do
/// `simple_tri` (RFC-N3DSEMU G5, achado real da G5.2).
class FixedAttributesTest {

    @Test
    void atributoNuncaProgramadoTemODefaultDoHardware() {
        assertArrayEquals(new float[]{0f, 0f, 0f, 1f}, new FixedAttributes().value(0));
    }

    @Test
    void decodificaOsQuatroComponentesFloat24EmposteTresPalavras() {
        FixedAttributes fixed = new FixedAttributes();
        fixed.onWrite(FixedAttributes.REG_INDEX, 1, 0xF);
        // (1,1,1,1) — o branco sólido que `C3D_FixedAttribSet(1, 1,1,1,1)` do `simple_tri` envia.
        int one = float24Bits(1.0f);
        fixed.onWrite(FixedAttributes.REG_DATA0, (one << 8) | (one >>> 16), 0xF);
        fixed.onWrite(FixedAttributes.REG_DATA0 + 1, (one << 16) | (one >>> 8), 0xF);
        fixed.onWrite(FixedAttributes.REG_DATA2, (one << 24) | one, 0xF);

        assertArrayEquals(new float[]{1f, 1f, 1f, 1f}, fixed.value(1));
    }

    @Test
    void oIndiceDeModoImediatoNaoProgramaAtributoNenhum() {
        FixedAttributes fixed = new FixedAttributes();
        fixed.onWrite(FixedAttributes.REG_INDEX, 0xF, 0xF);
        fixed.onWrite(FixedAttributes.REG_DATA0, -1, 0xF);
        fixed.onWrite(FixedAttributes.REG_DATA0 + 1, -1, 0xF);
        fixed.onWrite(FixedAttributes.REG_DATA2, -1, 0xF);

        for (int attributeId = 0; attributeId < VertexAttributeLoader.NUM_ATTRIBUTES; attributeId++) {
            assertArrayEquals(new float[]{0f, 0f, 0f, 1f}, fixed.value(attributeId));
        }
    }

    private static int float24Bits(float value) {
        int bits = Float.floatToIntBits(value);
        int sign = (bits >>> 31) & 1;
        int exponent = ((bits >>> 23) & 0xFF) - 127 + 63;
        int mantissa = (bits >>> 7) & 0xFFFF;
        return (sign << 23) | (exponent << 16) | mantissa;
    }
}
