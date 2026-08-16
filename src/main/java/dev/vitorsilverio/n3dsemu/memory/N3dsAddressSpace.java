package dev.vitorsilverio.n3dsemu.memory;

import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.n3dsemu.loader.Image3dsx;

import java.io.PrintStream;

/// Monta o espaço de endereçamento completo do ARM11 em modo aplicação (RFC §3) sobre
/// {@link PagedAddressSpace} (utilitário C3 do arm-jitter — mesmo usado por gbaemu e
/// `virtual-arm-box`), com o executável `.3dsx` já carregado.
public final class N3dsAddressSpace {
    private N3dsAddressSpace() {
    }

    /// Cria o barramento e carrega `image` em {@link MemoryMap#EXECUTABLE_BASE}.
    ///
    /// @param image  executável já relocado (ver {@link dev.vitorsilverio.n3dsemu.loader.Loader3dsx})
    /// @param diagnosticLog destino do log de {@link LoggingOpenBus} (acessos fora do mapa)
    public static PagedAddressSpace create(Image3dsx image, PrintStream diagnosticLog) {
        PagedAddressSpace memory = new PagedAddressSpace(MemoryMap.PAGE_SHIFT, new LoggingOpenBus(diagnosticLog));

        memory.mapRam(MemoryMap.EXECUTABLE_BASE, buildExecutableImage(image));
        memory.mapRam(MemoryMap.GENERAL_HEAP_BASE, new byte[MemoryMap.GENERAL_HEAP_SIZE]);
        memory.mapRam(MemoryMap.LINEAR_HEAP_BASE, new byte[MemoryMap.LINEAR_HEAP_SIZE]);
        memory.mapRam(MemoryMap.VRAM_BASE, new byte[MemoryMap.VRAM_SIZE]);
        memory.mapRam(MemoryMap.DSP_RAM_BASE, new byte[MemoryMap.DSP_RAM_SIZE]);
        memory.mapHandler(MemoryMap.CONFIG_MEMORY_BASE, MemoryMap.CONFIG_MEMORY_SIZE, ConfigMemory.create());
        memory.mapHandler(MemoryMap.SHARED_PAGE_BASE, MemoryMap.SHARED_PAGE_SIZE, SharedPage.create());
        memory.mapRam(MemoryMap.TLS_BASE, new byte[MemoryMap.TLS_REGION_SIZE]);
        memory.mapRam(MemoryMap.FCRAM_BASE, new byte[MemoryMap.FCRAM_SIZE]);

        return memory;
    }

    // PagedAddressSpace.mapRam exige um backing múltiplo de pageSize; o tamanho real do
    // executável (soma dos 3 segmentos) quase nunca é — completa com zeros até a fronteira
    // de página seguinte (o resto vira BSS/folga, nunca lido pelo guest se o linker gerou o
    // .3dsx corretamente).
    private static byte[] buildExecutableImage(Image3dsx image) {
        int rawSize = image.totalSize();
        int paddedSize = (rawSize + MemoryMap.PAGE_SIZE - 1) & ~(MemoryMap.PAGE_SIZE - 1);
        byte[] combined = new byte[paddedSize];
        int cursor = 0;
        System.arraycopy(image.code(), 0, combined, cursor, image.code().length);
        cursor += image.code().length;
        System.arraycopy(image.rodata(), 0, combined, cursor, image.rodata().length);
        cursor += image.rodata().length;
        System.arraycopy(image.dataWithBss(), 0, combined, cursor, image.dataWithBss().length);
        return combined;
    }
}
