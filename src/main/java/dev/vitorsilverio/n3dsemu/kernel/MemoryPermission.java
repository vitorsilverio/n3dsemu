package dev.vitorsilverio.n3dsemu.kernel;

/// Constantes do parâmetro/campo `MemPerm` (`svcControlMemory`/`svcQueryMemory`) — `MemPerm`
/// do `libctru/include/3ds/svc.h`, conferido localmente (libctru 2.7.0).
public final class MemoryPermission {
    private MemoryPermission() {
    }

    public static final int NONE = 0;
    public static final int READ = 1;
    public static final int WRITE = 2;
    public static final int EXECUTE = 4;
    public static final int READ_WRITE = READ | WRITE;
    public static final int READ_EXECUTE = READ | EXECUTE;
    /// Pede ao kernel para manter a permissão já vigente na região — usado por `svcControlMemory`
    /// quando o chamador não quer alterá-la.
    public static final int DONT_CARE = 0x1000_0000;
}
