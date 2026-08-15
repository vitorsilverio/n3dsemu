package dev.vitorsilverio.n3dsemu.kernel;

/// Valores plausíveis fixos para `svcGetResourceLimitCurrentValues` — a task desta sessão
/// (G2 PR1) pede exatamente isso, não contadores reais (threads/eventos/etc. de verdade só
/// existem na G2 PR2). Cada valor é o "uso atual" de um {@link LimitableResource}: com uma única
/// thread (a principal) e nenhum objeto de sincronização ainda criado, a maioria é `0`.
public final class ResourceLimitValues {
    private ResourceLimitValues() {
    }

    /// Bytes de `MEMOP_ALLOC` computados como "em uso" — placeholder até a G2 PR2 contabilizar
    /// alocações de verdade; `0` porque nenhuma foi feita ainda neste ponto do boot.
    private static final long COMMIT_BYTES_PLACEHOLDER = 0L;
    /// A única thread sustentada por esta sessão (RFC-N3DSEMU M2 PR1: ver {@link ThreadObject}).
    private static final long THREAD_COUNT = 1L;

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
}
