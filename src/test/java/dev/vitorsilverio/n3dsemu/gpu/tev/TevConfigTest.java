package dev.vitorsilverio.n3dsemu.gpu.tev;

import dev.vitorsilverio.n3dsemu.gpu.PicaRegisters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Decodifica registradores TEV montados à mão (offsets 3dbrew, RFC-N3DSEMU G5/PR3) e afirma o
/// {@link TevConfig} resultante — mesma técnica de teste de `CommandListParserTest` (RFC D4: sem
/// GPU nenhuma).
class TevConfigTest {
    @Test
    void decodesStage0SourceOperandCombinerAndScale() {
        PicaRegisters registers = new PicaRegisters();
        // fonte: colorSrc0=PRIMARY_COLOR(0), colorSrc1=TEXTURE0(3), alphaSrc0=CONSTANT(14)
        registers.write(0x0C0, (3 << 4) | 0 | (14 << 16), 0xF);
        // operando: colorOperand0=1
        registers.write(0x0C1, 1, 0xF);
        // combinador: colorCombine=MODULATE(1), alphaCombine=ADD(2)
        registers.write(0x0C2, 1 | (2 << 16), 0xF);
        // escala: colorScale=2 (4x)
        registers.write(0x0C4, 2, 0xF);

        TevConfig config = TevConfig.decode(registers);
        TevConfig.Stage stage0 = config.stages()[0];

        assertEquals(TevConfig.Source.PRIMARY_COLOR, stage0.colorSource()[0]);
        assertEquals(TevConfig.Source.TEXTURE0, stage0.colorSource()[1]);
        assertEquals(TevConfig.Source.CONSTANT, stage0.alphaSource()[0]);
        assertEquals(1, stage0.colorOperand()[0]);
        assertEquals(TevConfig.CombinerOp.MODULATE, stage0.colorCombine());
        assertEquals(TevConfig.CombinerOp.ADD, stage0.alphaCombine());
        assertEquals(2, stage0.colorScaleShift());
    }

    @Test
    void decodesSharedBufferColorRegister() {
        PicaRegisters registers = new PicaRegisters();
        registers.write(0x0FD, 0x11223344, 0xF);

        TevConfig config = TevConfig.decode(registers);

        assertEquals(0x11223344, config.bufferColorRgba8());
    }

    @Test
    void passthroughPrimaryColorHasSixStagesAllReplace() {
        TevConfig config = TevConfig.passthroughPrimaryColor();

        assertEquals(6, config.stages().length);
        assertEquals(TevConfig.CombinerOp.REPLACE, config.stages()[0].colorCombine());
        assertEquals(TevConfig.Source.PRIMARY_COLOR, config.stages()[0].colorSource()[0]);
    }
}
