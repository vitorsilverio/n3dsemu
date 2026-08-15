package dev.vitorsilverio.n3dsemu.memory;

import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.n3dsemu.loader.Image3dsx;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Confere que cada região do mapa (RFC §3) responde no endereço certo e que o executável é
/// carregado em {@link MemoryMap#EXECUTABLE_BASE}.
class N3dsAddressSpaceTest {
    private static Image3dsx tinyImage() {
        byte[] code = {0x11, 0x22, 0x33, 0x44};
        byte[] rodata = {0x55, 0x66, 0x77, (byte) 0x88};
        byte[] dataWithBss = {(byte) 0x99, (byte) 0xAA, 0, 0};
        return new Image3dsx(MemoryMap.EXECUTABLE_BASE, MemoryMap.EXECUTABLE_BASE, code, rodata, dataWithBss);
    }

    @Test
    void carregaExecutavelEmExecutableBase() {
        PagedAddressSpace memory = N3dsAddressSpace.create(tinyImage(), System.out);
        assertEquals(0x44332211, memory.read32(MemoryMap.EXECUTABLE_BASE));
        assertEquals(0x88776655, memory.read32(MemoryMap.EXECUTABLE_BASE + 4));
        assertEquals(0x0000AA99, memory.read32(MemoryMap.EXECUTABLE_BASE + 8));
    }

    @Test
    void ramComumLeEEscreve() {
        PagedAddressSpace memory = N3dsAddressSpace.create(tinyImage(), System.out);
        for (int base : new int[]{
                MemoryMap.LINEAR_HEAP_BASE, MemoryMap.NEW_HEAP_BASE,
                MemoryMap.VRAM_BASE, MemoryMap.DSP_RAM_BASE, MemoryMap.FCRAM_BASE}) {
            memory.write32(base, 0xDEADBEEF);
            assertEquals(0xDEADBEEF, memory.read32(base));
        }
    }

    @Test
    void configMemoryTemCamposLegiveis() {
        PagedAddressSpace memory = N3dsAddressSpace.create(tinyImage(), System.out);
        int kernelVersionWord = memory.read32(MemoryMap.CONFIG_MEMORY_BASE);
        // major=2 no byte +0x3, minor=0 em +0x2, revision=0 em +0x1 (little-endian: major é o
        // byte mais significativo dos 4 lidos a partir de +0x0).
        assertEquals(2, (kernelVersionWord >>> 24) & 0xFF);
        int appMemAlloc = memory.read32(MemoryMap.CONFIG_MEMORY_BASE + 0x40);
        assertEquals(64 * 1024 * 1024, appMemAlloc);
    }

    @Test
    void configMemoryIgnoraEscritas() {
        PagedAddressSpace memory = N3dsAddressSpace.create(tinyImage(), System.out);
        int before = memory.read32(MemoryMap.CONFIG_MEMORY_BASE + 0x40);
        memory.write32(MemoryMap.CONFIG_MEMORY_BASE + 0x40, 0);
        assertEquals(before, memory.read32(MemoryMap.CONFIG_MEMORY_BASE + 0x40));
    }

    @Test
    void sharedPageEstaMapeadaEZerada() {
        PagedAddressSpace memory = N3dsAddressSpace.create(tinyImage(), System.out);
        assertEquals(0, memory.read32(MemoryMap.SHARED_PAGE_BASE));
    }

    @Test
    void enderecoForaDoMapaCaiNoOpenBusELoga() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PagedAddressSpace memory = N3dsAddressSpace.create(tinyImage(), new PrintStream(captured));
        int unmapped = 0x40000000; // fora de qualquer região do mapa
        assertEquals(0, memory.read32(unmapped));
        assertTrue(captured.toString().contains("openbus"));
    }
}
