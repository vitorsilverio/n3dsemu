package dev.vitorsilverio.n3dsemu.kernel;

import java.util.HashMap;
import java.util.Map;

/// Nomes das `svc` (system calls) do kernel Horizon, indexados pelo número usado em
/// `svc #imm` (ARM: o imediato de 8 bits É o número da SVC — diferente do Linux EABI, que lê
/// o número de `r7`).
///
/// **Fonte:** <a href="https://www.3dbrew.org/wiki/SVC">3dbrew: SVC</a> lista os nomes, mas
/// não sempre os números lado a lado de forma inequívoca para todas as 99 entradas. Em vez de
/// transcrever manualmente (risco de erro de 1 dígito hex silencioso), os números foram
/// **extraídos empiricamente**: `arm-none-eabi-objdump -d` no `libctru.a` real instalado
/// (`C:\devkitPro\libctru\lib\libctru.a`, versão 2.7.0) — cada wrapper `svcXxx()` da libctru é
/// só um `push`/`svc 0xNN`/`pop`/`ret`, então o imediato da instrução `svc` é o número real
/// usado pelo binário que a G1 carrega. Ver `testdata/README.md` para a versão do toolchain.
///
/// Números sem entrada aqui (lacunas na tabela) não têm wrapper em libctru 2.7.0 ou não usam
/// mais o nome documentado no 3dbrew — {@link SvcTable} loga o número cru quando não há nome.
public final class HorizonSvcNames {
    private static final Map<Integer, String> NAMES = buildTable();

    private HorizonSvcNames() {
    }

    /// Nome da SVC de número `svcNumber`, ou `null` se desconhecido.
    public static String nameOf(int svcNumber) {
        return NAMES.get(svcNumber);
    }

    private static Map<Integer, String> buildTable() {
        Map<Integer, String> table = new HashMap<>();
        table.put(0x01, "svcControlMemory");
        table.put(0x02, "svcQueryMemory");
        table.put(0x03, "svcExitProcess");
        table.put(0x04, "svcGetProcessAffinityMask");
        table.put(0x05, "svcSetProcessAffinityMask");
        table.put(0x06, "svcGetProcessIdealProcessor");
        table.put(0x07, "svcSetProcessIdealProcessor");
        table.put(0x08, "svcCreateThread");
        table.put(0x09, "svcExitThread");
        table.put(0x0a, "svcSleepThread");
        table.put(0x0b, "svcGetThreadPriority");
        table.put(0x0c, "svcSetThreadPriority");
        table.put(0x0d, "svcGetThreadAffinityMask");
        table.put(0x0e, "svcSetThreadAffinityMask");
        table.put(0x0f, "svcGetThreadIdealProcessor");
        table.put(0x10, "svcSetThreadIdealProcessor");
        table.put(0x11, "svcGetProcessorID");
        table.put(0x12, "svcRun");
        table.put(0x13, "svcCreateMutex");
        table.put(0x14, "svcReleaseMutex");
        table.put(0x15, "svcCreateSemaphore");
        table.put(0x16, "svcReleaseSemaphore");
        table.put(0x17, "svcCreateEvent");
        table.put(0x18, "svcSignalEvent");
        table.put(0x19, "svcClearEvent");
        table.put(0x1a, "svcCreateTimer");
        table.put(0x1b, "svcSetTimer");
        table.put(0x1c, "svcCancelTimer");
        table.put(0x1d, "svcClearTimer");
        table.put(0x1e, "svcCreateMemoryBlock");
        table.put(0x1f, "svcMapMemoryBlock");
        table.put(0x20, "svcUnmapMemoryBlock");
        table.put(0x21, "svcCreateAddressArbiter");
        // svcArbitrateAddress e svcArbitrateAddressNoTimeout compartilham o mesmo número —
        // mesma SVC do kernel, o wrapper "NoTimeout" só passa um timeout diferente.
        table.put(0x22, "svcArbitrateAddress");
        table.put(0x23, "svcCloseHandle");
        table.put(0x24, "svcWaitSynchronization");
        table.put(0x25, "svcWaitSynchronizationN");
        table.put(0x27, "svcDuplicateHandle");
        table.put(0x28, "svcGetSystemTick");
        table.put(0x29, "svcGetHandleInfo");
        table.put(0x2a, "svcGetSystemInfo");
        table.put(0x2b, "svcGetProcessInfo");
        table.put(0x2c, "svcGetThreadInfo");
        table.put(0x2d, "svcConnectToPort");
        table.put(0x32, "svcSendSyncRequest");
        table.put(0x33, "svcOpenProcess");
        table.put(0x34, "svcOpenThread");
        table.put(0x35, "svcGetProcessId");
        table.put(0x36, "svcGetProcessIdOfThread");
        table.put(0x37, "svcGetThreadId");
        table.put(0x38, "svcGetResourceLimit");
        table.put(0x39, "svcGetResourceLimitLimitValues");
        table.put(0x3a, "svcGetResourceLimitCurrentValues");
        table.put(0x3c, "svcBreak");
        table.put(0x3d, "svcOutputDebugString");
        table.put(0x3e, "svcControlPerformanceCounter");
        table.put(0x47, "svcCreatePort");
        table.put(0x48, "svcCreateSessionToPort");
        table.put(0x49, "svcCreateSession");
        table.put(0x4a, "svcAcceptSession");
        table.put(0x4f, "svcReplyAndReceive");
        table.put(0x50, "svcBindInterrupt");
        table.put(0x51, "svcUnbindInterrupt");
        table.put(0x52, "svcInvalidateProcessDataCache");
        table.put(0x53, "svcStoreProcessDataCache");
        table.put(0x54, "svcFlushProcessDataCache");
        table.put(0x55, "svcStartInterProcessDma");
        table.put(0x56, "svcStopDma");
        table.put(0x57, "svcGetDmaState");
        table.put(0x58, "svcRestartDma");
        table.put(0x59, "svcSetGpuProt");
        table.put(0x5a, "svcSetWifiEnabled");
        table.put(0x60, "svcDebugActiveProcess");
        table.put(0x61, "svcBreakDebugProcess");
        table.put(0x62, "svcTerminateDebugProcess");
        table.put(0x63, "svcGetProcessDebugEvent");
        table.put(0x64, "svcContinueDebugEvent");
        table.put(0x65, "svcGetProcessList");
        table.put(0x66, "svcGetThreadList");
        table.put(0x67, "svcGetDebugThreadContext");
        table.put(0x68, "svcSetDebugThreadContext");
        table.put(0x69, "svcQueryDebugProcessMemory");
        table.put(0x6a, "svcReadProcessMemory");
        table.put(0x6b, "svcWriteProcessMemory");
        table.put(0x6c, "svcSetHardwareBreakPoint");
        table.put(0x6d, "svcGetDebugThreadParam");
        table.put(0x70, "svcControlProcessMemory");
        table.put(0x71, "svcMapProcessMemory");
        table.put(0x72, "svcUnmapProcessMemory");
        table.put(0x73, "svcCreateCodeSet");
        table.put(0x75, "svcCreateProcess");
        table.put(0x76, "svcTerminateProcess");
        table.put(0x77, "svcSetProcessResourceLimits");
        table.put(0x78, "svcCreateResourceLimit");
        table.put(0x79, "svcSetResourceLimitValues");
        table.put(0x7b, "svcBackdoor");
        table.put(0x7c, "svcKernelSetState");
        table.put(0x7d, "svcQueryProcessMemory");
        return Map.copyOf(table);
    }
}
