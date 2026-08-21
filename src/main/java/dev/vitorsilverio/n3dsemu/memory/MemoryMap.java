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

    /// Tamanho de cada um dos dois pools de `svcControlMemory` (heap geral e heap linear, ver
    /// abaixo) — **os dois são iguais de propósito** (G2, achado real da sessão de investigação
    /// 2026-08-16 — ver `tasks/FILA-EXECUCAO.md`): `__system_allocateHeaps` do libctru consulta
    /// `svcGetResourceLimitLimitValues(COMMIT)` e reparte o teto retornado **meio a meio** entre
    /// os dois heaps quando não há `.smdh`/exheader dizendo o contrário (caso do homebrew
    /// `.3dsx` desta HLE) — como `ResourceLimitValues` devolve exatamente
    /// `GENERAL_HEAP_SIZE + LINEAR_HEAP_SIZE` como teto de `COMMIT` (o máximo que esta HLE sem
    /// MMU pode honrar de verdade, ver Javadoc daquela classe), a metade pedida só cabe nos dois
    /// pools se os dois tiverem o MESMO tamanho. Um valor menor fazia o primeiro
    /// `svcControlMemory(MEMOP_ALLOC)` do crt0 pedir mais do que o pool suporta e falhar com
    /// `OUT_OF_RANGE`/`MISALIGNED_SIZE`, encerrando o guest em `svcBreak(PANIC)` antes de
    /// `main()`.
    private static final int HEAP_POOL_SIZE = 16 * 1024 * 1024;

    /// Heap "geral" (`svcControlMemory` `MEMOP_ALLOC` **sem** a flag `LINEAR`) — 3dbrew:
    /// Memory_layout, "Heap mapped by ControlMemory", base `0x08000000` no Old3DS. **Achado real
    /// (G2, sessão de investigação 2026-08-16, ver `tasks/FILA-EXECUCAO.md`): esta base e a de
    /// {@link #LINEAR_HEAP_BASE} estavam TROCADAS** — o nome "heap linear" tinha sido colado no
    /// endereço errado (`0x08000000`, que na verdade é o heap GERAL/não-linear real), e
    /// `MemoryManager#controlMemory` roteava `MEMOP_ALLOC_LINEAR` para o pool errado como
    /// consequência direta. Confirmado contra a tabela de `Memory_layout` do 3dbrew antes de
    /// corrigir, não um palpite.
    public static final int GENERAL_HEAP_BASE = 0x0800_0000;
    public static final int GENERAL_HEAP_SIZE = HEAP_POOL_SIZE;

    /// Heap linear (`linearAlloc` do libctru, `svcControlMemory` `MEMOP_ALLOC_LINEAR`) —
    /// "mapeado sobre a FCRAM para acesso fisicamente contíguo" no hardware real (3dbrew:
    /// Memory_layout, mapeamento LINEAR, base `0x14000000` no Old3DS); aqui, por simplicidade da
    /// task de esqueleto (sem MMU), tem backing próprio. Ver Javadoc de {@link #GENERAL_HEAP_BASE}
    /// para o achado de troca de endereços corrigido nesta sessão.
    public static final int LINEAR_HEAP_BASE = 0x1400_0000;
    public static final int LINEAR_HEAP_SIZE = HEAP_POOL_SIZE;

    /// VRAM: 2 bancos de 3 MiB (RFC §3/D6).
    public static final int VRAM_BASE = 0x1800_0000;
    /// Endereço VIRTUAL da mesma VRAM (`OS_VRAM_VADDR` do libctru real, `os.h`) — é por aqui que
    /// a CPU do guest escreve nos framebuffers; {@link #VRAM_BASE} é o endereço FÍSICO que os
    /// registradores da PICA200 carregam. As duas janelas espelham as MESMAS páginas (ver
    /// `N3dsAddressSpace#create`).
    public static final int VRAM_VIRTUAL_BASE = 0x1F00_0000;
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
