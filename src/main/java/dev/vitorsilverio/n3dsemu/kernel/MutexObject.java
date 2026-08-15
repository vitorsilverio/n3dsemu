package dev.vitorsilverio.n3dsemu.kernel;

/// `svcCreateMutex`/`svcReleaseMutex` (RFC-N3DSEMU G2 PR2). O mutex do 3DS é **recursivo e tem
/// dono** (armadilha documentada na task): a mesma thread pode adquirir várias vezes, e só
/// libera de verdade quando o número de `svcReleaseMutex` bate com o de aquisições.
public final class MutexObject implements Waitable {
    private ThreadObject owner;
    private int recursionCount;

    /// @param initialOwner dono inicial (`svcCreateMutex` com `initiallyLocked=true`), ou
    ///                      `null` se criado destravado.
    public MutexObject(ThreadObject initialOwner) {
        if (initialOwner != null) {
            owner = initialOwner;
            recursionCount = 1;
        }
    }

    @Override
    public boolean isAvailableFor(ThreadObject waiter) {
        return owner == null || owner == waiter;
    }

    @Override
    public void acquire(ThreadObject waiter) {
        owner = waiter;
        recursionCount++;
    }

    /// `svcReleaseMutex`: libera uma reentrada de `caller`. Liberar um mutex que não é seu
    /// (nunca foi adquirido, ou foi adquirido por outra thread) é erro — nunca destrava o
    /// mutex de outra thread.
    public Result release(ThreadObject caller) {
        if (owner != caller) {
            return Result.NOT_LOCK_OWNER;
        }
        recursionCount--;
        if (recursionCount == 0) {
            owner = null;
        }
        return Result.SUCCESS;
    }

    @Override
    public String debugName() {
        return "Mutex(owner=" + (owner == null ? "nenhum" : "Thread#" + owner.threadId()) + ")";
    }
}
