package dev.vitorsilverio.n3dsemu.kernel;

/// A única thread do guest que esta sessão (G2 PR1) sustenta — a principal, já em execução
/// desde antes da primeira `svc` (RFC-N3DSEMU M1). `svcCreateThread` real (múltiplas threads,
/// escalonador cooperativo) é G2 PR2; até lá, {@code svcGetThreadId} sempre devolve o mesmo
/// {@link #threadId} fixo, e é isso que a task deste PR1 pede explicitamente ("parcial").
///
/// Alcançável pelo handle pseudo-reservado {@link HandleTable#CURRENT_THREAD_HANDLE}.
///
/// @param threadId identificador plausível fixo (ver Javadoc acima)
public record ThreadObject(int threadId) implements KernelObject {
    @Override
    public String debugName() {
        return "Thread#" + threadId;
    }
}
