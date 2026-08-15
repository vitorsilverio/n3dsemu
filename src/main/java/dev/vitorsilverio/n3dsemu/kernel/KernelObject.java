package dev.vitorsilverio.n3dsemu.kernel;

/// Um objeto de kernel referenciável por um handle na {@link HandleTable}. PR1 da G2 só
/// precisa de {@link ProcessObject}, {@link ThreadObject} e {@link ResourceLimitObject} —
/// evento/mutex/semáforo/sessão entram na G2 PR2 (threads + sincronização + IPC), quando este
/// `sealed` ganha os novos `permits`.
public sealed interface KernelObject permits ProcessObject, ThreadObject, ResourceLimitObject {
    /// Nome legível para diagnóstico (log de handle inválido, trace).
    String debugName();
}
