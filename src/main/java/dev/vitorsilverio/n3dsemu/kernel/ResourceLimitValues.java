package dev.vitorsilverio.n3dsemu.kernel;

import dev.vitorsilverio.n3dsemu.memory.MemoryMap;

/// Valores plausíveis fixos para `svcGetResourceLimitCurrentValues`/`svcGetResourceLimitLimitValues`
/// — a task desta sessão (G2 PR1) pede exatamente isso, não contadores reais (threads/eventos/etc.
/// de verdade só existem na G2 PR2). Cada "uso atual" é para um {@link LimitableResource}: com uma
/// única thread (a principal) e nenhum objeto de sincronização ainda criado, a maioria é `0`.
public final class ResourceLimitValues {
    private ResourceLimitValues() {
    }

    /// Bytes de `MEMOP_ALLOC` computados como "em uso" — placeholder até a G2 PR2 contabilizar
    /// alocações de verdade; `0` porque nenhuma foi feita ainda neste ponto do boot.
    private static final long COMMIT_BYTES_PLACEHOLDER = 0L;
    /// A única thread sustentada por esta sessão (RFC-N3DSEMU M2 PR1: ver {@link ThreadObject}).
    private static final long THREAD_COUNT = 1L;

    /// Teto de `COMMIT` (`svcGetResourceLimitLimitValues`) — o `__system_allocateHeaps` do
    /// libctru lê este valor ANTES do primeiro `svcControlMemory(MEMOP_ALLOC)` para decidir
    /// quanto heap linear/novo pedir (achado real, sessão de investigação 2026-08-16 do
    /// `tasks/FILA-EXECUCAO.md`: sem este teto, o tamanho calculado do heap fica `0` e o `ALLOC`
    /// falha com `MISALIGNED_SIZE`). Em vez de um número inventado, o teto é exatamente o que
    /// {@link MemoryManager} de fato consegue satisfazer nesta HLE sem MMU (RFC D2): a soma dos
    /// dois pools que `svcControlMemory` administra — nenhum pedido dentro deste teto pode
    /// falhar por falta de espaço no pool errado, e nenhum teto maior seria honesto (a HLE não
    /// tem mais memória para dar).
    private static final long COMMIT_LIMIT_BYTES = MemoryMap.GENERAL_HEAP_SIZE + MemoryMap.LINEAR_HEAP_SIZE;
    /// Teto de threads simultâneas — mesmo valor de {@link MemoryMap#TLS_MAX_THREADS}, já que
    /// é o que de fato limita `svcCreateThread` nesta HLE (ver Javadoc de `MemoryMap`).
    private static final long THREAD_LIMIT = MemoryMap.TLS_MAX_THREADS;

    /// "Uso atual" plausível para `resource`, ou `0` para qualquer categoria sem contador real
    /// nesta sessão (eventos/mutex/semáforos/timers/memória compartilhada/`AddressArbiter`/tempo
    /// de CPU — todos G2 PR2).
    public static long currentValueOf(int resource) {
        return switch (resource) {
            case LimitableResource.COMMIT -> COMMIT_BYTES_PLACEHOLDER;
            case LimitableResource.THREAD -> THREAD_COUNT;
            default -> 0L;
        };
    }

    /// Teto plausível para `resource` (`svcGetResourceLimitLimitValues`) — `0` só seria honesto
    /// para uma categoria genuinamente sem limite modelado nesta HLE, mas devolver `0` faz
    /// qualquer consumidor real interpretar como "teto zero" (nega qualquer alocação), não
    /// "sem limite". Por isso as categorias fora de {@code COMMIT}/{@code THREAD} recebem um teto
    /// generoso fixo em vez de `0` — nenhum objeto de sincronização desta HLE tem um teto real
    /// diferente disso hoje.
    private static final long GENEROUS_LIMIT_PLACEHOLDER = 32L;

    public static long limitValueOf(int resource) {
        return switch (resource) {
            case LimitableResource.COMMIT -> COMMIT_LIMIT_BYTES;
            case LimitableResource.THREAD -> THREAD_LIMIT;
            default -> GENEROUS_LIMIT_PLACEHOLDER;
        };
    }
}
