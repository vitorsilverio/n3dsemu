package dev.vitorsilverio.n3dsemu.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// RFC-N3DSEMU G5/PR1: monta listas de comando à mão (sem GPU, sem guest real) e afirma o estado
/// resultante de {@link PicaRegisters} — a técnica de teste que a task pede para esta camada
/// (RFC D4: "a maior parte do valor de teste desta task está aqui").
class CommandListParserTest {
    private static final int REG = 0x040;

    @Test
    void singleCommandWritesFullWordWithFullMask() {
        PicaRegisters registers = new PicaRegisters();
        int header = header(REG, 0xF, 0, false);
        CommandListParser.parse(new int[]{0x12345678, header}, registers);

        assertEquals(0x12345678, registers.read(REG));
    }

    @Test
    void partialByteMaskPreservesUntouchedBytes() {
        PicaRegisters registers = new PicaRegisters();
        registers.write(REG, 0xAABBCCDD, 0xF);

        // registrador = (byte3,byte2,byte1,byte0) = (AA,BB,CC,DD). máscara 0b0101: só os bytes 0
        // e 2 são substituídos pelo valor novo (00,00,11,00) -> byte0=00, byte2=00; byte1(CC) e
        // byte3(AA) preservados.
        int header = header(REG, 0b0101, 0, false);
        CommandListParser.parse(new int[]{0x22001100, header}, registers);

        assertEquals(0xAA00CC00, registers.read(REG));
    }

    @Test
    void consecutiveModeIncrementsRegisterPerExtraWord() {
        PicaRegisters registers = new PicaRegisters();
        int header = header(REG, 0xF, 2, true);
        CommandListParser.parse(new int[]{0x1, header, 0x2, 0x3}, registers);

        assertEquals(0x1, registers.read(REG));
        assertEquals(0x2, registers.read(REG + 1));
        assertEquals(0x3, registers.read(REG + 2));
    }

    @Test
    void nonConsecutiveModeRepeatsWritesToSameRegister() {
        PicaRegisters registers = new PicaRegisters();
        int header = header(REG, 0xF, 2, false);
        CommandListParser.parse(new int[]{0x1, header, 0x2, 0x3}, registers);

        assertEquals(0x3, registers.read(REG));
        assertEquals(0, registers.read(REG + 1));
        assertEquals(0, registers.read(REG + 2));
    }

    @Test
    void multipleCommandsInSequenceApplyInOrder() {
        PicaRegisters registers = new PicaRegisters();
        int[] words = {
                0x10, header(REG, 0xF, 0, false),
                0x20, header(REG + 1, 0xF, 0, false),
        };
        CommandListParser.parse(words, registers);

        assertEquals(0x10, registers.read(REG));
        assertEquals(0x20, registers.read(REG + 1));
    }

    @Test
    void trailingIncompleteCommandIsIgnored() {
        PicaRegisters registers = new PicaRegisters();
        int[] words = {0x10, header(REG, 0xF, 0, false), 0xDEADBEEF};
        CommandListParser.parse(words, registers);

        assertEquals(0x10, registers.read(REG));
    }

    @Test
    void drawArraysCommandIncrementsTriggerCount() {
        PicaRegisters registers = new PicaRegisters();
        int header = header(PicaRegisters.DRAW_ARRAYS, 0xF, 0, false);
        CommandListParser.parse(new int[]{0x1, header}, registers);

        assertEquals(1, registers.drawArraysTriggerCount());
        assertEquals(0, registers.drawElementsTriggerCount());
        assertEquals(0, registers.finalizeCount());
    }

    @Test
    void drawElementsCommandIncrementsTriggerCount() {
        PicaRegisters registers = new PicaRegisters();
        int header = header(PicaRegisters.DRAW_ELEMENTS, 0xF, 0, false);
        CommandListParser.parse(new int[]{0x1, header}, registers);

        assertEquals(1, registers.drawElementsTriggerCount());
    }

    @Test
    void finalizeCommandIncrementsFinalizeCount() {
        PicaRegisters registers = new PicaRegisters();
        int header = header(PicaRegisters.FINALIZE, 0xF, 0, false);
        CommandListParser.parse(new int[]{0x0, header}, registers);

        assertEquals(1, registers.finalizeCount());
    }

    @Test
    void threeDBrewExampleSequenceMatchesDocumentedCommandIds() {
        // 3dbrew (GPU/Internal Registers): "0xAAAAAAAA 0x802F011C 0xBBBBBBBB 0xCCCCCCCC" —
        // header 0x802F011C = registrador 0x011C, máscara 0xF, 2 extras, consecutivo.
        PicaRegisters registers = new PicaRegisters();
        CommandListParser.parse(new int[]{0xAAAAAAAA, 0x802F011C, 0xBBBBBBBB, 0xCCCCCCCC}, registers);

        assertEquals(0xAAAAAAAA, registers.read(0x011C));
        assertEquals(0xBBBBBBBB, registers.read(0x011D));
        assertEquals(0xCCCCCCCC, registers.read(0x011E));
    }

    @Test
    void pulaAPalavraDePaddingQuandoAQuantidadeDePalavrasExtrasEImpar() {
        PicaRegisters registers = new PicaRegisters();
        // Comando 1: 1 palavra extra (total 3 palavras) -> o hardware exige padding até 8 bytes,
        // então uma 4ª palavra de enchimento segue. Sem pulá-la, o parser leria o enchimento como
        // parâmetro do comando 2 e desalinharia o resto da lista inteira.
        int[] words = {
                0x11111111, header(0x040, 0xF, 1, false),
                0x22222222,
                0xDEADBEEF, // padding
                0x33333333, header(0x041, 0xF, 0, false),
        };

        CommandListParser.parse(words, registers);

        assertEquals(0x22222222, registers.read(0x040));
        assertEquals(0x33333333, registers.read(0x041));
    }

    private static int header(int registerId, int byteMask, int extraCount, boolean consecutive) {
        int value = (registerId & 0xFFFF) | ((byteMask & 0xF) << 16) | ((extraCount & 0xFF) << 20);
        if (consecutive) {
            value |= 1 << 31;
        }
        return value;
    }
}
