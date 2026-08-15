package dev.vitorsilverio.n3dsemu.kernel;

/// `svcCreateEvent`/`svcSignalEvent`/`svcClearEvent` (RFC-N3DSEMU G2 PR2). `RESET_ONESHOT`
/// acorda uma thread e se limpa sozinho (ver {@link #acquire}); `RESET_STICKY` permanece
/// sinalizado até `svcClearEvent` explícito — confundir os dois causa deadlock ou spin
/// infinito (armadilha documentada na task).
public final class EventObject implements Waitable {
    private final int resetType;
    private boolean signaled;

    /// @param resetType {@link ResetType#ONESHOT} ou {@link ResetType#STICKY} — validado pelo
    ///                   chamador (`SvcTable`), não aqui.
    public EventObject(int resetType) {
        this.resetType = resetType;
    }

    @Override
    public boolean isAvailableFor(ThreadObject waiter) {
        return signaled;
    }

    @Override
    public void acquire(ThreadObject waiter) {
        if (resetType == ResetType.ONESHOT) {
            signaled = false;
        }
    }

    public void signal() {
        signaled = true;
    }

    public void clear() {
        signaled = false;
    }

    @Override
    public String debugName() {
        return "Event(" + (resetType == ResetType.ONESHOT ? "ONESHOT" : "STICKY") + ", signaled=" + signaled + ")";
    }
}
