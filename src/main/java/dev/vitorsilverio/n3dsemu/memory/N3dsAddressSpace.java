package dev.vitorsilverio.n3dsemu.memory;

import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.n3dsemu.loader.Image3dsx;
import dev.vitorsilverio.n3dsemu.loader.Loader3dsx;

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

    /// Tamanho da região CODE+RODATA+DATA como ela é MONTADA na memória (não a soma bruta dos
    /// 3 tamanhos do arquivo `.3dsx`) — código e rodata terminam alinhados a
    /// {@link Loader3dsx#SEGMENT_ALIGNMENT} (mesmo achado real documentado em
    /// {@link Loader3dsx}, "Achado real G3": os ponteiros relocados pressupõem esse
    /// espaçamento), então a região é maior que `image.totalSize()` sempre que code/rodata não
    /// caem exatamente numa fronteira de 0x1000. Usado tanto por {@link #buildExecutableImage}
    /// quanto por `N3dsMachine` (bookkeeping do `MemoryManager` para `svcQueryMemory`) — as
    /// duas contas TÊM que bater, senão o `MemoryManager` reporta um tamanho de região CODE
    /// menor que o que está de fato mapeado.
    public static int executableRegionSize(Image3dsx image) {
        return Loader3dsx.segmentOffset(image.code().length)
                + Loader3dsx.segmentOffset(image.rodata().length)
                + image.dataWithBss().length;
    }

    // PagedAddressSpace.mapRam exige um backing múltiplo de pageSize; o tamanho real do
    // executável (com o espaçamento entre segmentos acima) quase nunca é — completa com zeros
    // até a fronteira de página seguinte (o resto vira BSS/folga, nunca lido pelo guest se o
    // linker gerou o .3dsx corretamente).
    private static byte[] buildExecutableImage(Image3dsx image) {
        int rawSize = executableRegionSize(image);
        int paddedSize = (rawSize + MemoryMap.PAGE_SIZE - 1) & ~(MemoryMap.PAGE_SIZE - 1);
        byte[] combined = new byte[paddedSize];
        int codeOffset = 0;
        int rodataOffset = Loader3dsx.segmentOffset(image.code().length);
        int dataOffset = rodataOffset + Loader3dsx.segmentOffset(image.rodata().length);
        System.arraycopy(image.code(), 0, combined, codeOffset, image.code().length);
        System.arraycopy(image.rodata(), 0, combined, rodataOffset, image.rodata().length);
        System.arraycopy(image.dataWithBss(), 0, combined, dataOffset, image.dataWithBss().length);
        return combined;
    }
}
