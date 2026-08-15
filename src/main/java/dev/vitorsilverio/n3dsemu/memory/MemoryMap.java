package dev.vitorsilverio.n3dsemu.memory;

/// Bases e tamanhos do espaço de endereçamento do ARM11 em modo aplicação (Old3DS, HLE do
/// Horizon — RFC-N3DSEMU §3, transcrito de
/// <a href="https://www.3dbrew.org/wiki/Memory_layout">3dbrew: Memory layout</a>).
///
/// **Não são endereços físicos** — no 3DS real cada região é mapeada pela MMU do Horizon a
/// partir de FCRAM/VRAM física; como esta task não emula MMU (RFC D2: kernel em HLE), o mapa
/// é montado plano pelo host nesses mesmos endereços virtuais.
public final class MemoryMap {
    private MemoryMap() {
    }

    /// Base de carga do executável `.3dsx` — igual a {@link
    /// dev.vitorsilverio.n3dsemu.loader.Loader3dsx#LOAD_BASE}.
    public static final int EXECUTABLE_BASE = 0x0010_0000;

    /// Heap linear (`linearAlloc` do libctru) — "na prática mapeado sobre a FCRAM" no
    /// hardware real; aqui, por simplicidade da task de esqueleto (sem MMU), tem backing
    /// próprio. Tamanho fixado em 2 MiB (RFC §3); o alocador real (G2/G3) fica livre para
    /// crescer isso quando `svcControlMemory`/`linearAlloc` precisarem de mais.
    public static final int LINEAR_HEAP_BASE = 0x0800_0000;
    public static final int LINEAR_HEAP_SIZE = 2 * 1024 * 1024;

    /// Heap "novo" (`svcControlMemory` `MEMOP_ALLOC`) — RFC §3 não fixa um tamanho ("—");
    /// 16 MiB é um placeholder razoável para o esqueleto (G1 não implementa
    /// `svcControlMemory` de verdade — isso é G2), documentado para não ser confundido com
    /// um valor hardware-preciso.
    public static final int NEW_HEAP_BASE = 0x1400_0000;
    public static final int NEW_HEAP_SIZE = 16 * 1024 * 1024;

    /// VRAM: 2 bancos de 3 MiB (RFC §3/D6).
    public static final int VRAM_BASE = 0x1800_0000;
    public static final int VRAM_SIZE = 6 * 1024 * 1024;

    /// DSP RAM (fora de escopo funcional até D7, mas mapeada como RAM comum).
    public static final int DSP_RAM_BASE = 0x1FF0_0000;
    public static final int DSP_RAM_SIZE = 512 * 1024;

    /// Config memory: página de 4 KiB, só leitura, com campos plausíveis de Old3DS (ver
    /// {@link ConfigMemory}).
    public static final int CONFIG_MEMORY_BASE = 0x1FF8_0000;
    public static final int CONFIG_MEMORY_SIZE = 4 * 1024;

    /// Shared page: página de 4 KiB, só leitura (ver {@link SharedPage}).
    public static final int SHARED_PAGE_BASE = 0x1FF8_1000;
    public static final int SHARED_PAGE_SIZE = 4 * 1024;

    /// FCRAM (memória principal) — 128 MiB no Old3DS.
    public static final int FCRAM_BASE = 0x2000_0000;
    public static final int FCRAM_SIZE = 128 * 1024 * 1024;

    /// Granularidade de página do {@code PagedAddressSpace} (C3) usado para montar o mapa —
    /// 4 KiB, a mesma granularidade real da MMU do 3DS (mesmo sem MMU emulada, todas as
    /// regiões acima já nascem alinhadas a isso).
    public static final int PAGE_SHIFT = 12;
    public static final int PAGE_SIZE = 1 << PAGE_SHIFT;
}
