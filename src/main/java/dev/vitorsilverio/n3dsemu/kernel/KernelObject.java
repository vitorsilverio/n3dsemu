package dev.vitorsilverio.n3dsemu.kernel;

/// Um objeto de kernel referenciável por um handle na {@link HandleTable}. {@link ThreadObject}/
/// {@link MutexObject}/{@link SemaphoreObject}/{@link EventObject} implementam este contrato
/// indiretamente via {@link Waitable} (G2 PR2: podem ser esperados por
/// `svcWaitSynchronization1`/`N`) — por isso não aparecem diretamente na lista de `permits`
/// abaixo, só {@link Waitable} aparece.
public sealed interface KernelObject
        permits ProcessObject, ResourceLimitObject, SessionObject, AddressArbiterObject, MemoryBlockObject, Waitable {
    /// Nome legível para diagnóstico (log de handle inválido, trace).
    String debugName();
}
