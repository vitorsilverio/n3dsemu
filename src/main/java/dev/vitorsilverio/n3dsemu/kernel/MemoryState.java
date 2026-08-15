package dev.vitorsilverio.n3dsemu.kernel;

/// Constantes do campo `state` de `MemInfo` (`svcQueryMemory`) — `MemState` do
/// `libctru/include/3ds/svc.h`, conferido localmente (libctru 2.7.0).
public final class MemoryState {
    private MemoryState() {
    }

    public static final int FREE = 0;
    public static final int RESERVED = 1;
    public static final int IO = 2;
    public static final int STATIC = 3;
    public static final int CODE = 4;
    public static final int PRIVATE = 5;
    public static final int SHARED = 6;
    public static final int CONTINUOUS = 7;
    public static final int ALIASED = 8;
    public static final int ALIAS = 9;
    public static final int ALIAS_CODE = 10;
    public static final int LOCKED = 11;
}
