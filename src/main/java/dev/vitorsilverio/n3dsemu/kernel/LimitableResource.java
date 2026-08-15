package dev.vitorsilverio.n3dsemu.kernel;

/// `LimitableResource`/`ResourceLimitType` — categorias consultáveis por
/// `svcGetResourceLimitCurrentValues` (`libctru/include/3ds/svc.h`, conferido localmente contra
/// o header instalado, libctru 2.7.0).
public final class LimitableResource {
    private LimitableResource() {
    }

    public static final int PRIORITY = 0;
    public static final int COMMIT = 1;
    public static final int THREAD = 2;
    public static final int EVENT = 3;
    public static final int MUTEX = 4;
    public static final int SEMAPHORE = 5;
    public static final int TIMER = 6;
    public static final int SHARED_MEMORY = 7;
    public static final int ADDRESS_ARBITER = 8;
    public static final int CPU_TIME = 9;
}
