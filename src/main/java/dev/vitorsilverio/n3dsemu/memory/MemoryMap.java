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

    /// TLS (Thread Local Storage) por thread — RFC §3: "TLS de cada thread... fica em páginas
    /// alocadas pelo host". Sem endereço fixado pela RFC/3dbrew para esta HLE (no Horizon real
    /// fica embutido no espaço "static" que a MMU aloca dinamicamente); posicionado no vão
    /// livre entre a Shared Page e a FCRAM (RFC-N3DSEMU G2 PR2). O primeiro slot é sempre da
    /// thread principal (ver {@link dev.vitorsilverio.n3dsemu.kernel.ThreadObject#mainThread}).
    public static final int TLS_BASE = 0x1FF9_0000;
    /// Tamanho de uma página de TLS — inclui o buffer de comando IPC em
    /// {@link #TLS_COMMAND_BUFFER_OFFSET}.
    public static final int TLS_SLOT_SIZE = PAGE_SIZE;
    /// Quantas threads simultâneas esta HLE sustenta (`svcCreateThread` além disso falha com
    /// {@code Result.OUT_OF_MEMORY}) — generoso para qualquer homebrew do corpus desta task.
    public static final int TLS_MAX_THREADS = 64;
    public static final int TLS_REGION_SIZE = TLS_SLOT_SIZE * TLS_MAX_THREADS;
    /// Deslocamento do buffer de comando IPC dentro da página de TLS de uma thread (RFC §3).
    public static final int TLS_COMMAND_BUFFER_OFFSET = 0x80;
}
