package dev.vitorsilverio.n3dsemu.kernel;

/// `svcCreateSemaphore`/`svcReleaseSemaphore` (RFC-N3DSEMU G2 PR2) — contador clássico: espera
/// decrementa quando `count > 0`, liberar incrementa até `maxCount`.
public final class SemaphoreObject implements Waitable {
    private int count;
    private final int maxCount;

    public SemaphoreObject(int initialCount, int maxCount) {
        this.count = initialCount;
        this.maxCount = maxCount;
    }

    @Override
    public boolean isAvailableFor(ThreadObject waiter) {
        return count > 0;
    }

    @Override
    public void acquire(ThreadObject waiter) {
        count--;
    }

    /// `svcReleaseSemaphore`: resultado + contagem ANTERIOR à liberação (`r1` de saída real,
    /// ver Javadoc de {@code SvcTable#handleReleaseSemaphore}).
    ///
    /// @param result        resultado da operação
    /// @param previousCount contagem antes de somar `releaseCount` (só significativa em sucesso)
    public record ReleaseResult(Result result, int previousCount) {
        static ReleaseResult failure(Result result) {
            return new ReleaseResult(result, 0);
        }
    }

    public ReleaseResult release(int releaseCount) {
        if (releaseCount <= 0 || count + releaseCount > maxCount) {
            return ReleaseResult.failure(Result.OUT_OF_RANGE);
        }
        int previous = count;
        count += releaseCount;
        return new ReleaseResult(Result.SUCCESS, previous);
    }

    @Override
    public String debugName() {
        return "Semaphore(count=" + count + "/" + maxCount + ")";
    }
}
