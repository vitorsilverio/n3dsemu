package dev.vitorsilverio.n3dsemu.gpu;

import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.n3dsemu.memory.LoggingOpenBus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// `GX_MemoryFill` de verdade (RFC-N3DSEMU G5.3) — é ele que pinta o fundo do quadro. Até a G5.2 o
/// comando era dado como concluído sem efeito de memória e a tela ficava preta em vez da cor que o
/// app pediu.
class GxMemoryFillTest {
    private static final int PAGE_SHIFT = 12;
    private static final int RAM_BASE = 0x1800_0000;
    private static final int RAM_SIZE = 0x2_0000;
    private static final int QUEUE_BASE = 0x1000;
    private static final int ENTRIES_OFFSET = 0x20;

    /// `C3D_RenderTargetClear(target, C3D_CLEAR_ALL, 0x68B0D8FF, 0)` do `simple_tri`: um
    /// preenchimento de 32 bits no *color buffer* e outro, zerado, no *depth buffer*.
    private static final int CLEAR_COLOR = 0x68B0D8FF;
    private static final int FILL_32_BIT_TRIGGER = 0x0201;

    @Test
    void preencheOsDoisBuffersNaLarguraPedidaESinalizaPsc0() {
        PagedAddressSpace memory = newMemory();
        int colorBuffer = RAM_BASE;
        int depthBuffer = RAM_BASE + 0x1000;
        writeMemoryFillEntry(memory, colorBuffer, CLEAR_COLOR, colorBuffer + 0x40, FILL_32_BIT_TRIGGER,
                depthBuffer, 0, depthBuffer + 0x40, FILL_32_BIT_TRIGGER);

        List<Integer> events = GxCommandQueue.processPending(memory, QUEUE_BASE, words -> { });

        assertEquals(List.of(GxCommandQueue.EVENT_PSC0), events);
        for (int offset = 0; offset < 0x40; offset += 4) {
            assertEquals(CLEAR_COLOR, memory.read32(colorBuffer + offset));
            assertEquals(0, memory.read32(depthBuffer + offset));
        }
        // ...e nada além do fim pedido.
        assertEquals(0, memory.read32(colorBuffer + 0x40));
    }

    @Test
    void semOBitDeTriggerOBufferNaoEMexido() {
        PagedAddressSpace memory = newMemory();
        // `C3D_CLEAR_COLOR` sozinho: o segundo buffer (profundidade) vem sem `GX_FILL_TRIGGER`.
        writeMemoryFillEntry(memory, RAM_BASE, CLEAR_COLOR, RAM_BASE + 0x10, FILL_32_BIT_TRIGGER,
                RAM_BASE + 0x1000, -1, RAM_BASE + 0x1010, 0x0200);

        GxCommandQueue.processPending(memory, QUEUE_BASE, words -> { });

        assertEquals(CLEAR_COLOR, memory.read32(RAM_BASE));
        assertEquals(0, memory.read32(RAM_BASE + 0x1000));
    }

    @Test
    void preenchimentoDe16BitsRepeteMeiaPalavra() {
        PagedAddressSpace memory = newMemory();
        int fill16BitTrigger = 0x0001;
        writeMemoryFillEntry(memory, RAM_BASE, 0xAAAA_1234, RAM_BASE + 0x8, fill16BitTrigger, 0, 0, 0, 0);

        GxCommandQueue.processPending(memory, QUEUE_BASE, words -> { });

        assertEquals(0x1234_1234, memory.read32(RAM_BASE));
        assertEquals(0x1234_1234, memory.read32(RAM_BASE + 4));
    }

    @Test
    void aCorDeLimpezaLidaDeVoltaDoColorBufferEExatamenteAQueOAppPediu() {
        PagedAddressSpace memory = newMemory();
        writeMemoryFillEntry(memory, RAM_BASE, CLEAR_COLOR, RAM_BASE + 0x10, FILL_32_BIT_TRIGGER, 0, 0, 0, 0);
        GxCommandQueue.processPending(memory, QUEUE_BASE, words -> { });

        // `GPUREG_COLORBUFFER_FORMAT` com os bits 16-18 zerados = RGBA8 (o formato que
        // `C3D_RenderTargetCreate(..., GPU_RB_RGBA8, ...)` programa).
        ColorBufferFormat format = ColorBufferFormat.fromRegister(0x2);
        assertEquals(ColorBufferFormat.RGBA8, format);
        assertArrayEquals(new float[]{0x68 / 255f, 0xB0 / 255f, 0xD8 / 255f, 1f},
                format.readPixel(memory, RAM_BASE), 1e-6f);
    }

    private static PagedAddressSpace newMemory() {
        PagedAddressSpace memory = new PagedAddressSpace(PAGE_SHIFT, new LoggingOpenBus(new java.io.PrintStream(java.io.OutputStream.nullOutputStream())));
        memory.mapRam(0, new byte[RAM_SIZE]);
        memory.mapRam(RAM_BASE, new byte[RAM_SIZE]);
        return memory;
    }

    private static void writeMemoryFillEntry(PagedAddressSpace memory, int buffer0Start, int buffer0Value,
                                              int buffer0End, int control0, int buffer1Start, int buffer1Value,
                                              int buffer1End, int control1) {
        memory.write8(QUEUE_BASE, 0); // commandIndex
        memory.write8(QUEUE_BASE + 1, 1); // 1 comando pendente
        int entryBase = QUEUE_BASE + ENTRIES_OFFSET;
        memory.write32(entryBase, 0x0100_0102); // tipo 2 = MemoryFill (cabeçalho real de GX_MemoryFill)
        memory.write32(entryBase + 0x4, buffer0Start);
        memory.write32(entryBase + 0x8, buffer0Value);
        memory.write32(entryBase + 0xC, buffer0End);
        memory.write32(entryBase + 0x10, buffer1Start);
        memory.write32(entryBase + 0x14, buffer1Value);
        memory.write32(entryBase + 0x18, buffer1End);
        memory.write32(entryBase + 0x1C, (control0 & 0xFFFF) | (control1 << 16));
    }
}
