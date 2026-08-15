package dev.vitorsilverio.n3dsemu.kernel;

/// `ResetType` — comportamento de sinalização de {@link EventObject} (`svcCreateEvent`),
/// `libctru/include/3ds/svc.h`, conferido localmente (libctru 2.7.0).
public final class ResetType {
    private ResetType() {
    }

    /// Acorda exatamente uma thread esperando e se limpa sozinho.
    public static final int ONESHOT = 0;
    /// Acorda todas as threads esperando e permanece sinalizado até `svcClearEvent`.
    public static final int STICKY = 1;
    /// Só tem significado para timers (não para {@link EventObject}, fora do escopo desta
    /// task — `svcCreateEvent` só aceita {@link #ONESHOT}/{@link #STICKY}).
    public static final int PULSE = 2;
}
