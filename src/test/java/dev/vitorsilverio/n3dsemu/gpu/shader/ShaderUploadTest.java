package dev.vitorsilverio.n3dsemu.gpu.shader;

import dev.vitorsilverio.n3dsemu.gpu.CommandListParser;
import dev.vitorsilverio.n3dsemu.gpu.PicaRegisters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// RFC-N3DSEMU G5/PR3: monta uma lista de comandos à mão simulando o upload de shader por
/// registrador-FIFO real (o mesmo formato que `GX_ProcessCommandList` entrega) e afirma o que
/// {@link ShaderUpload} capturou — sem `.shbin`-arquivo, sem GPU (RFC D4).
class ShaderUploadTest {
    private static int header(int registerId, int extraCount, boolean consecutive) {
        int value = (registerId & 0xFFFF) | (0xF << 16) | ((extraCount & 0xFF) << 20);
        return consecutive ? value | (1 << 31) : value;
    }

    @Test
    void capturesCodeTransferFifoInOrder() {
        PicaRegisters registers = new PicaRegisters();
        ShaderUpload upload = new ShaderUpload();
        int[] words = {
                0, header(ShaderUpload.REG_CODETRANSFER_INDEX, 0, false), // index = 0
                0xAAAA0000, header(0x2CC, 0, false), // primeira palavra do programa (não-consecutivo repete o MESMO registrador FIFO)
                0xBBBB0000, header(0x2CC, 0, false), // segunda palavra
        };
        CommandListParser.parse(words, registers, upload);

        ShaderBinary shader = upload.toShaderBinary();
        assertEquals(0xAAAA0000, shader.programCode()[0]);
        assertEquals(0xBBBB0000, shader.programCode()[1]);
    }

    @Test
    void capturesOperandDescriptorFifo() {
        PicaRegisters registers = new PicaRegisters();
        ShaderUpload upload = new ShaderUpload();
        int[] words = {
                0, header(ShaderUpload.REG_OPDESCS_INDEX, 0, false),
                0x1234, header(0x2D6, 0, false),
        };
        CommandListParser.parse(words, registers, upload);

        assertEquals(0x1234, upload.toShaderBinary().operandDescriptors()[0]);
    }

    @Test
    void capturesFloat32UniformAsFourConsecutiveWords() {
        PicaRegisters registers = new PicaRegisters();
        ShaderUpload upload = new ShaderUpload();
        int config = 5 | (1 << 31); // índice=5, modo float32
        int[] words = {
                config, header(ShaderUpload.REG_FLOAT_UNIFORM_CONFIG, 0, false),
                Float.floatToIntBits(1.0f), header(0x2C1, 0, false),
                Float.floatToIntBits(2.0f), header(0x2C1, 0, false),
                Float.floatToIntBits(3.0f), header(0x2C1, 0, false),
                Float.floatToIntBits(4.0f), header(0x2C1, 0, false),
        };
        CommandListParser.parse(words, registers, upload);

        // A ORDEM É INVERTIDA: a primeira palavra do FIFO é o componente `w` (ver
        // ShaderUpload#decodeFloat32Constant — confirmado contra a matriz real do `simple_tri`).
        assertArrayEquals(new float[]{4.0f, 3.0f, 2.0f, 1.0f}, upload.floatConstants()[5]);
    }

    @Test
    void capturesFloat24UniformAsThreePackedWords() {
        PicaRegisters registers = new PicaRegisters();
        ShaderUpload upload = new ShaderUpload();
        int config = 5; // índice=5, bit31=0 -> modo float24 (empacotado), o default do citro3d
        // (1.0, 2.0, 3.0, 4.0) em float24: expoente com bias 63, mantissa de 16 bits.
        int x = float24Bits(1.0f);
        int y = float24Bits(2.0f);
        int z = float24Bits(3.0f);
        int w = float24Bits(4.0f);
        int[] words = {
                config, header(ShaderUpload.REG_FLOAT_UNIFORM_CONFIG, 0, false),
                (w << 8) | (z >>> 16), header(0x2C1, 0, false),
                (z << 16) | (y >>> 8), header(0x2C1, 0, false),
                (y << 24) | x, header(0x2C1, 0, false),
        };
        CommandListParser.parse(words, registers, upload);

        assertArrayEquals(new float[]{1.0f, 2.0f, 3.0f, 4.0f}, upload.floatConstants()[5]);
    }

    /// Codifica um `float` no formato de 24 bits do PICA200 (1 sinal + 7 expoente/bias 63 + 16
    /// mantissa) — inverso de {@link dev.vitorsilverio.n3dsemu.gpu.shader.Float24#decode}.
    private static int float24Bits(float value) {
        int bits = Float.floatToIntBits(value);
        int sign = (bits >>> 31) & 1;
        int exponent = ((bits >>> 23) & 0xFF) - 127 + 63;
        int mantissa = (bits >>> 7) & 0xFFFF;
        return (sign << 23) | (exponent << 16) | mantissa;
    }

    @Test
    void decodesAttributePermutationLowAndHigh() {
        PicaRegisters registers = new PicaRegisters();
        ShaderUpload upload = new ShaderUpload();
        // atributo 0 -> v3, atributo 1 -> v5 (low); atributo 8 -> v2 (high)
        int low = 0x3 | (0x5 << 4);
        int high = 0x2;
        int[] words = {
                low, header(ShaderUpload.REG_ATTRIBUTES_PERMUTATION_LOW, 0, false),
                high, header(ShaderUpload.REG_ATTRIBUTES_PERMUTATION_HIGH, 0, false),
        };
        CommandListParser.parse(words, registers, upload);

        int[] mapping = upload.attributeToInputRegister();
        assertEquals(3, mapping[0]);
        assertEquals(5, mapping[1]);
        assertEquals(2, mapping[8]);
    }

    @Test
    void entrypointSetsMainOffsetOfExecutable() {
        PicaRegisters registers = new PicaRegisters();
        ShaderUpload upload = new ShaderUpload();
        int[] words = {0x7, header(ShaderUpload.REG_ENTRYPOINT, 0, false)};
        CommandListParser.parse(words, registers, upload);

        assertEquals(0x7, upload.toExecutable().mainOffset());
        assertTrue(upload.toExecutable().outputRegisters().size() >= 2);
    }
}
