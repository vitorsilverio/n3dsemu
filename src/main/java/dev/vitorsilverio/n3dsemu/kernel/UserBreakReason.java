package dev.vitorsilverio.n3dsemu.kernel;

/// `UserBreakType` — motivo passado a `svcBreak` (`libctru/include/3ds/svc.h`, conferido
/// localmente contra o header instalado, libctru 2.7.0).
public final class UserBreakReason {
    private UserBreakReason() {
    }

    public static final int PANIC = 0;
    public static final int ASSERT = 1;
    public static final int USER = 2;
    public static final int LOAD_RO = 3;
    public static final int UNLOAD_RO = 4;

    /// Nome legível de `reason`, ou o valor bruto em hex se desconhecido.
    public static String nameOf(int reason) {
        return switch (reason) {
            case PANIC -> "PANIC";
            case ASSERT -> "ASSERT";
            case USER -> "USER";
            case LOAD_RO -> "LOAD_RO";
            case UNLOAD_RO -> "UNLOAD_RO";
            default -> "0x" + Integer.toHexString(reason);
        };
    }
}
