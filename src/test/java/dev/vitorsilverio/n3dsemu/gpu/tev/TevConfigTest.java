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
        TevConfig.Stage stage0 = config.stages().get(0);

        assertEquals(TevConfig.Source.PRIMARY_COLOR, stage0.colorSource().get(0));
        assertEquals(TevConfig.Source.TEXTURE0, stage0.colorSource().get(1));
        assertEquals(TevConfig.Source.CONSTANT, stage0.alphaSource().get(0));
        assertEquals(1, stage0.colorOperand().get(0));
        assertEquals(TevConfig.CombinerOp.MODULATE, stage0.colorCombine());
        assertEquals(TevConfig.CombinerOp.ADD, stage0.alphaCombine());
        assertEquals(2, stage0.colorScaleShift());
    }

    @Test
    void decodesPerStageConstantColorAndAlphaTest() {
        PicaRegisters registers = new PicaRegisters();
        registers.write(0x0C3, 0x8899AABB, 0xF); // GPUREG_TEXENV0_COLOR
        // habilitado(bit0) + função GREATER(6, bits 4-6) + referência 0x7F (bits 8-15)
        registers.write(0x104, 1 | (6 << 4) | (0x7F << 8), 0xF);

        TevConfig config = TevConfig.decode(registers);

        assertEquals(0x8899AABB, config.stages().get(0).constantColorRgba8());
        assertEquals(true, config.alphaTest().enabled());
        assertEquals(TevConfig.CompareFunc.GREATER, config.alphaTest().function());
        assertEquals(0x7F, config.alphaTest().reference());
    }

    @Test
    void decodesQuaisEstagiosAlimentamOBufferCombinado() {
        PicaRegisters registers = new PicaRegisters();
        // GPUREG_TEXENV_UPDATE_BUFFER: cor pelos estágios 0 e 2, alpha só pelo 1.
        registers.write(0x0E0, (0b0101 << 8) | (0b0010 << 12), 0xF);

        TevConfig config = TevConfig.decode(registers);

        assertEquals(true, config.stageUpdatesBufferColor(0));
        assertEquals(false, config.stageUpdatesBufferColor(1));
        assertEquals(true, config.stageUpdatesBufferColor(2));
        assertEquals(true, config.stageUpdatesBufferAlpha(1));
        assertEquals(false, config.stageUpdatesBufferAlpha(0));
        // Só os 4 primeiros estágios podem alimentar o buffer (3dbrew/Citra).
        assertEquals(false, config.stageUpdatesBufferColor(4));
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

        assertEquals(6, config.stages().size());
        assertEquals(TevConfig.CombinerOp.REPLACE, config.stages().get(0).colorCombine());
        assertEquals(TevConfig.Source.PRIMARY_COLOR, config.stages().get(0).colorSource().get(0));
    }
}
