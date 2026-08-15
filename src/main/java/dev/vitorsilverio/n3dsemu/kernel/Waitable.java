package dev.vitorsilverio.n3dsemu.kernel;

/// Um objeto de kernel que `svcWaitSynchronization1`/`svcWaitSynchronizationN` conseguem
/// esperar (RFC-N3DSEMU G2 PR2). Cada implementação decide o que "disponível" e "adquirir"
/// significam: {@link MutexObject} (adquirir = virar dono), {@link SemaphoreObject} (adquirir =
/// decrementar a contagem), {@link EventObject} (adquirir = zerar se `RESET_ONESHOT`), e
/// {@link ThreadObject} (esperar uma handle de thread é esperar ela terminar — "adquirir" não
/// consome nada, um handle de thread morta fica permanentemente sinalizado, igual a um evento
/// `RESET_STICKY`).
public sealed interface Waitable extends KernelObject permits ThreadObject, MutexObject, SemaphoreObject, EventObject {
    /// `true` se `waiter` conseguiria sincronizar neste objeto agora, sem bloquear.
    boolean isAvailableFor(ThreadObject waiter);

    /// Efeito colateral de `waiter` ter sincronizado com sucesso neste objeto (mutex: vira
    /// dono; semáforo: decrementa; evento oneshot: limpa). Só é chamado depois de
    /// {@link #isAvailableFor} confirmar `true` para o mesmo `waiter`.
    void acquire(ThreadObject waiter);
}
