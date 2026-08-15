package dev.vitorsilverio.n3dsemu.kernel;

/// Constantes do parâmetro `MemOp` de `svcControlMemory` (3dbrew: `Memory_layout`/`SVC` +
/// `libctru/include/3ds/svc.h` `MemOp`, conferido localmente contra o header instalado em
/// `C:\devkitPro\libctru\include\3ds\svc.h`, libctru 2.7.0).
public final class MemoryOperation {
    private MemoryOperation() {
    }

    /// Máscara do sub-código de operação (bits baixos de `operation`).
    public static final int OPERATION_MASK = 0xFF;
    /// Máscara da região preferida para `MEMOP_ALLOC` sem endereço fixo.
    public static final int REGION_MASK = 0xF00;
    /// Bit que pede alocação no heap linear (`MemoryMap#LINEAR_HEAP_BASE`) em vez do heap novo.
    public static final int LINEAR_FLAG = 0x10000;

    public static final int FREE = 1;
    public static final int RESERVE = 2;
    public static final int ALLOC = 3;
    public static final int MAP = 4;
    public static final int UNMAP = 5;
    public static final int PROTECT = 6;

    public static int opCode(int rawOperation) {
        return rawOperation & OPERATION_MASK;
    }

    public static boolean isLinear(int rawOperation) {
        return (rawOperation & LINEAR_FLAG) != 0;
    }
}
