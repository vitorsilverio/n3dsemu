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

        assertArrayEquals(new float[]{1.0f, 2.0f, 3.0f, 4.0f}, upload.floatConstants()[5]);
    }

    @Test
    void float24ModeThrowsUnsupported() {
        PicaRegisters registers = new PicaRegisters();
        ShaderUpload upload = new ShaderUpload();
        int config = 0; // bit31=0 -> modo float24, não implementado
        int[] words = {
                config, header(ShaderUpload.REG_FLOAT_UNIFORM_CONFIG, 0, false),
                0x1, header(0x2C1, 0, false),
        };

        assertThrows(UnsupportedOperationException.class, () -> CommandListParser.parse(words, registers, upload));
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
