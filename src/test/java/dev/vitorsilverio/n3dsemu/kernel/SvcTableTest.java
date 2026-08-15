package dev.vitorsilverio.n3dsemu.kernel;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.armjitter.swi.CpuState;
import dev.vitorsilverio.n3dsemu.memory.LoggingOpenBus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testa {@link SvcTable} de ponta a ponta contra um {@link ArmCore} real (RFC-N3DSEMU G2
/// PR1) — sem passar pelo `.3dsx` completo (a G1 já cobre isso em `Application3dsxTest`; o
/// aceite completo da task G2 exige `svcCreateAddressArbiter`, G2 PR2, para o boot real
/// progredir além da primeira svc observada). Em vez disso, cada teste grava a instrução `svc`
/// real na memória do guest e monta o {@link CpuState} exatamente como o `IrSystemExecutor` do
/// arm-jitter faria antes de despachar — inclusive lendo/escrevendo r4/r5 direto no
/// {@link ArmCore} para as SVCs cuja convenção real usa registradores além de r0-r3 (ver Javadoc
/// de {@link SvcTable}).
class SvcTableTest {
    private static final int PAGE_SHIFT = 12;
    private static final int PAGE_SIZE = 1 << PAGE_SHIFT;
    private static final int CODE_BASE = 0x0010_0000;
    private static final int DATA_BASE = 0x0020_0000;
    private static final int LINEAR_HEAP_BASE = 0x0800_0000;
    private static final int LINEAR_HEAP_SIZE = 2 * PAGE_SIZE;
    private static final int NEW_HEAP_BASE = 0x1400_0000;
    private static final int NEW_HEAP_SIZE = 2 * PAGE_SIZE;
    private static final int MAIN_PROCESS_ID = 5;
    private static final int MAIN_THREAD_ID = 1;

    // Codificação real de `svc #imm` em modo ARM (condição AL=0xE): confirmado via
    // `arm-none-eabi-objdump -d libctru.a` — `svc 0x01` monta como `ef000001` (ver Javadoc de
    // SvcTable#realSvcNumber).
    private static final int ARM_SVC_OPCODE_BASE = 0xEF00_0000;
    private static final int ARM_INSTRUCTION_SIZE = 4;

    private static final int REGISTER_R4 = 4;
    private static final int REGISTER_R5 = 5;

    private record Harness(PagedAddressSpace memory, ArmCore core, SvcTable svcTable,
                            HandleTable handles, ByteArrayOutputStream stdout) {
    }

    private Harness newHarness() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream log = new PrintStream(captured);
        PagedAddressSpace memory = new PagedAddressSpace(PAGE_SHIFT, new LoggingOpenBus(log));
        memory.mapRam(CODE_BASE, new byte[PAGE_SIZE]);
        memory.mapRam(DATA_BASE, new byte[PAGE_SIZE]);

        HandleTable handles = new HandleTable(new ProcessObject(MAIN_PROCESS_ID), new ThreadObject(MAIN_THREAD_ID));
        MemoryManager memoryManager = new MemoryManager(CODE_BASE, PAGE_SIZE,
                LINEAR_HEAP_BASE, LINEAR_HEAP_SIZE, NEW_HEAP_BASE, NEW_HEAP_SIZE);
        SvcTable svcTable = new SvcTable(memory, log, false, handles, memoryManager);
        ArmCore core = new ArmCore(memory, svcTable.dispatcher(), ArmArchitecture.ARM11_MPCORE);
        svcTable.attach(core);
        return new Harness(memory, core, svcTable, handles, captured);
    }

    /// Grava `svc #svcNumber` em `CODE_BASE` e devolve o `CpuState` de entrada correspondente
    /// (pc já avançado, como o `IrSystemExecutor` deixa antes de despachar).
    private CpuState dispatch(Harness h, int svcNumber, int r0, int r1, int r2, int r3) {
        h.memory().write32(CODE_BASE, ARM_SVC_OPCODE_BASE | (svcNumber & 0xFF));
        CpuState state = new CpuState(r0, r1, r2, r3, 0, 0, CODE_BASE + ARM_INSTRUCTION_SIZE, 0);
        return h.svcTable().dispatcher().dispatch(0, state);
    }

    @Test
    void controlMemoryAlocaNoHeapNovoEDevolveOEnderecoEmR1() {
        Harness h = newHarness();
        int operation = MemoryOperation.ALLOC;
        h.core().setRegister(REGISTER_R4, MemoryPermission.READ_WRITE);

        CpuState result = dispatch(h, 0x01, operation, 0, 0, PAGE_SIZE);

        assertEquals(Result.SUCCESS.code(), result.r0());
        assertEquals(NEW_HEAP_BASE, result.r1());
    }

    @Test
    void queryMemorySobreOCodigoDevolveEstadoCodeEPermissaoReadExecute() {
        Harness h = newHarness();

        CpuState result = dispatch(h, 0x02, 0, 0, CODE_BASE, 0);

        assertEquals(Result.SUCCESS.code(), result.r0());
        assertEquals(CODE_BASE, result.r1());
        assertEquals(PAGE_SIZE, result.r2());
        assertEquals(MemoryPermission.READ_EXECUTE, result.r3());
        assertEquals(MemoryState.CODE, h.core().register(REGISTER_R4));
    }

    @Test
    void exitProcessLancaKernelHaltExceptionDeSaidaLimpa() {
        Harness h = newHarness();
        h.memory().write32(CODE_BASE, ARM_SVC_OPCODE_BASE | 0x03);
        CpuState state = new CpuState(0, 0, 0, 0, 0, 0, CODE_BASE + ARM_INSTRUCTION_SIZE, 0);

        KernelHaltException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                KernelHaltException.class, () -> h.svcTable().dispatcher().dispatch(0, state));

        assertEquals(KernelHaltException.Reason.PROCESS_EXIT, thrown.reason());
    }

    @Test
    void breakLancaKernelHaltExceptionDeDiagnosticoComOMotivo() {
        Harness h = newHarness();
        h.memory().write32(CODE_BASE, ARM_SVC_OPCODE_BASE | 0x3C);
        CpuState state = new CpuState(UserBreakReason.ASSERT, 0, 0, 0, 0, 0, CODE_BASE + ARM_INSTRUCTION_SIZE, 0);

        KernelHaltException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                KernelHaltException.class, () -> h.svcTable().dispatcher().dispatch(0, state));

        assertEquals(KernelHaltException.Reason.GUEST_BREAK, thrown.reason());
        assertTrue(thrown.getMessage().contains("ASSERT"));
    }

    @Test
    void outputDebugStringImprimeNoStdoutDoHost() {
        Harness h = newHarness();
        String text = "hello from the guest";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            h.memory().write8(DATA_BASE + i, bytes[i]);
        }

        CpuState result = dispatch(h, 0x3D, DATA_BASE, bytes.length, 0, 0);

        assertEquals(Result.SUCCESS.code(), result.r0());
        assertTrue(h.stdout().toString(StandardCharsets.UTF_8).contains(text));
    }

    @Test
    void closeHandleDeHandleDesconhecidaDevolveInvalidHandleSemLancar() {
        Harness h = newHarness();

        CpuState result = dispatch(h, 0x23, 0xDEAD, 0, 0, 0);

        assertEquals(Result.INVALID_HANDLE.code(), result.r0());
    }

    @Test
    void duplicateHandleDoProcessoCorrenteCriaUmaHandleRealValida() {
        Harness h = newHarness();

        CpuState result = dispatch(h, 0x27, 0, HandleTable.CURRENT_PROCESS_HANDLE, 0, 0);

        assertEquals(Result.SUCCESS.code(), result.r0());
        assertEquals(new ProcessObject(MAIN_PROCESS_ID), h.handles().resolve(result.r1()).orElseThrow());
    }

    @Test
    void getSystemTickDevolveOsCiclosDoArmCoreNuncaORelogioDeParede() {
        Harness h = newHarness();

        CpuState result = dispatch(h, 0x28, 0, 0, 0, 0);

        long ticks = (result.r1() & 0xFFFF_FFFFL) << 32 | (result.r0() & 0xFFFF_FFFFL);
        assertEquals(h.core().cycles(), ticks);
    }

    @Test
    void getProcessIdDaPseudoHandleDevolveOIdFixo() {
        Harness h = newHarness();

        CpuState result = dispatch(h, 0x35, 0, HandleTable.CURRENT_PROCESS_HANDLE, 0, 0);

        assertEquals(Result.SUCCESS.code(), result.r0());
        assertEquals(MAIN_PROCESS_ID, result.r1());
    }

    @Test
    void getThreadIdDaPseudoHandleDevolveOIdFixo() {
        Harness h = newHarness();

        CpuState result = dispatch(h, 0x37, 0, HandleTable.CURRENT_THREAD_HANDLE, 0, 0);

        assertEquals(Result.SUCCESS.code(), result.r0());
        assertEquals(MAIN_THREAD_ID, result.r1());
    }

    @Test
    void getThreadIdComHandleDeProcessoDevolveInvalidHandle() {
        Harness h = newHarness();

        CpuState result = dispatch(h, 0x37, 0, HandleTable.CURRENT_PROCESS_HANDLE, 0, 0);

        assertEquals(Result.INVALID_HANDLE.code(), result.r0());
    }

    @Test
    void getResourceLimitDoProcessoCorrenteDevolveUmaHandleValida() {
        Harness h = newHarness();

        CpuState result = dispatch(h, 0x38, 0, HandleTable.CURRENT_PROCESS_HANDLE, 0, 0);

        assertEquals(Result.SUCCESS.code(), result.r0());
        assertTrue(h.handles().resolve(result.r1()).orElseThrow() instanceof ResourceLimitObject);
    }

    @Test
    void getResourceLimitCurrentValuesEscreveOsValoresNaMemoriaDoGuest() {
        Harness h = newHarness();
        CpuState resourceLimit = dispatch(h, 0x38, 0, HandleTable.CURRENT_PROCESS_HANDLE, 0, 0);
        int resourceLimitHandle = resourceLimit.r1();

        int namesAddress = DATA_BASE;
        int valuesAddress = DATA_BASE + 0x100;
        h.memory().write32(namesAddress, LimitableResource.THREAD);

        h.memory().write32(CODE_BASE, ARM_SVC_OPCODE_BASE | 0x3A);
        CpuState state = new CpuState(valuesAddress, resourceLimitHandle, namesAddress, 1,
                0, 0, CODE_BASE + ARM_INSTRUCTION_SIZE, 0);
        CpuState result = h.svcTable().dispatcher().dispatch(0, state);

        assertEquals(Result.SUCCESS.code(), result.r0());
        long low = h.memory().read32(valuesAddress) & 0xFFFF_FFFFL;
        long high = h.memory().read32(valuesAddress + 4) & 0xFFFF_FFFFL;
        assertEquals(1L, (high << 32) | low);
    }

    @Test
    void svcNaoImplementadaContinuaLogandoELancandoComoNaG1() {
        Harness h = newHarness();
        h.memory().write32(CODE_BASE, ARM_SVC_OPCODE_BASE | 0x21); // svcCreateAddressArbiter, G2 PR2
        CpuState state = new CpuState(0, 0, 0, 0, 0, 0, CODE_BASE + ARM_INSTRUCTION_SIZE, 0);

        UnsupportedSvcException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedSvcException.class, () -> h.svcTable().dispatcher().dispatch(0, state));

        assertEquals(0x21, thrown.call().number());
    }
}
