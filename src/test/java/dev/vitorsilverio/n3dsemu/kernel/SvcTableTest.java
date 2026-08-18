package dev.vitorsilverio.n3dsemu.kernel;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.armjitter.swi.CpuState;
import dev.vitorsilverio.n3dsemu.core.N3dsCp15;
import dev.vitorsilverio.n3dsemu.memory.LoggingOpenBus;
import dev.vitorsilverio.n3dsemu.service.ServiceRegistry;
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
    private static final int GENERAL_HEAP_BASE = 0x0800_0000;
    private static final int GENERAL_HEAP_SIZE = 2 * PAGE_SIZE;
    private static final int LINEAR_HEAP_BASE = 0x1400_0000;
    private static final int LINEAR_HEAP_SIZE = 2 * PAGE_SIZE;
    private static final int MAIN_PROCESS_ID = 5;
    private static final int MAIN_THREAD_ID = 1;
    private static final int MAIN_THREAD_PRIORITY = 0x30;

    // Codificação real de `svc #imm` em modo ARM (condição AL=0xE): confirmado via
    // `arm-none-eabi-objdump -d libctru.a` — `svc 0x01` monta como `ef000001` (ver Javadoc de
    // SvcTable#realSvcNumber).
    private static final int ARM_SVC_OPCODE_BASE = 0xEF00_0000;
    private static final int ARM_INSTRUCTION_SIZE = 4;

    private static final int REGISTER_R4 = 4;
    private static final int REGISTER_R5 = 5;

    private record Harness(PagedAddressSpace memory, ArmCore core, SvcTable svcTable,
                            HandleTable handles, Scheduler scheduler, ThreadObject mainThread,
                            ByteArrayOutputStream stdout, ServiceRegistry serviceRegistry) {
    }

    private Harness newHarness() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream log = new PrintStream(captured);
        PagedAddressSpace memory = new PagedAddressSpace(PAGE_SHIFT, new LoggingOpenBus(log));
        memory.mapRam(CODE_BASE, new byte[PAGE_SIZE]);
        memory.mapRam(DATA_BASE, new byte[PAGE_SIZE]);
        memory.mapRam(dev.vitorsilverio.n3dsemu.memory.MemoryMap.TLS_BASE,
                new byte[dev.vitorsilverio.n3dsemu.memory.MemoryMap.TLS_REGION_SIZE]);

        ThreadObject mainThread = ThreadObject.mainThread(MAIN_THREAD_ID, MAIN_THREAD_PRIORITY);
        HandleTable handles = new HandleTable(new ProcessObject(MAIN_PROCESS_ID), mainThread);
        MemoryManager memoryManager = new MemoryManager(CODE_BASE, PAGE_SIZE,
                GENERAL_HEAP_BASE, GENERAL_HEAP_SIZE, LINEAR_HEAP_BASE, LINEAR_HEAP_SIZE);
        Scheduler scheduler = new Scheduler();
        ServiceRegistry serviceRegistry = new ServiceRegistry();
        SvcTable svcTable = new SvcTable(memory, log, false, handles, memoryManager, scheduler, serviceRegistry);
        ArmCore core = new ArmCore(memory, svcTable.dispatcher(), ArmArchitecture.ARM11_MPCORE);
        svcTable.attach(core);
        N3dsCp15 cp15 = new N3dsCp15();
        core.setCoprocessorBus(cp15);
        scheduler.attach(core, cp15, mainThread);
        return new Harness(memory, core, svcTable, handles, scheduler, mainThread, captured, serviceRegistry);
    }

    /// Grava `svc #svcNumber` em `CODE_BASE` e devolve o `CpuState` de entrada correspondente
    /// (pc já avançado, como o `IrSystemExecutor` deixa antes de despachar).
    private CpuState dispatch(Harness h, int svcNumber, int r0, int r1, int r2, int r3) {
        h.memory().write32(CODE_BASE, ARM_SVC_OPCODE_BASE | (svcNumber & 0xFF));
        CpuState state = new CpuState(r0, r1, r2, r3, 0, 0, CODE_BASE + ARM_INSTRUCTION_SIZE, 0);
        return h.svcTable().dispatcher().dispatch(0, state);
    }

    @Test
    void controlMemoryAlocaNoHeapGeralEDevolveOEnderecoEmR1() {
        Harness h = newHarness();
        int operation = MemoryOperation.ALLOC;
        h.core().setRegister(REGISTER_R4, MemoryPermission.READ_WRITE);

        CpuState result = dispatch(h, 0x01, operation, 0, 0, PAGE_SIZE);

        assertEquals(Result.SUCCESS.code(), result.r0());
        assertEquals(GENERAL_HEAP_BASE, result.r1());
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

    /// `0x39` não está na lista de SVCs da task original (achado real, sessão de continuação
    /// 2026-08-16, ver `tasks/FILA-EXECUCAO.md`): sem ela, `__system_allocateHeaps` do libctru
    /// computa um tamanho de heap `0` a partir do array de saída nunca escrito, e o
    /// `svcControlMemory(MEMOP_ALLOC)` seguinte falha com `MISALIGNED_SIZE` — travando o boot do
    /// `.3dsx` real bem antes de `main()`.
    @Test
    void getResourceLimitLimitValuesEscreveOTetoDeCommitNaMemoriaDoGuest() {
        Harness h = newHarness();
        CpuState resourceLimit = dispatch(h, 0x38, 0, HandleTable.CURRENT_PROCESS_HANDLE, 0, 0);
        int resourceLimitHandle = resourceLimit.r1();

        int namesAddress = DATA_BASE;
        int valuesAddress = DATA_BASE + 0x100;
        h.memory().write32(namesAddress, LimitableResource.COMMIT);

        h.memory().write32(CODE_BASE, ARM_SVC_OPCODE_BASE | 0x39);
        CpuState state = new CpuState(valuesAddress, resourceLimitHandle, namesAddress, 1,
                0, 0, CODE_BASE + ARM_INSTRUCTION_SIZE, 0);
        CpuState result = h.svcTable().dispatcher().dispatch(0, state);

        assertEquals(Result.SUCCESS.code(), result.r0());
        long low = h.memory().read32(valuesAddress) & 0xFFFF_FFFFL;
        long high = h.memory().read32(valuesAddress + 4) & 0xFFFF_FFFFL;
        long commitLimit = (high << 32) | low;
        // ResourceLimitValues lê os tamanhos REAIS de MemoryMap (não os pools reduzidos deste
        // harness de teste, que só existem para caber em PAGE_SIZE pequenos) — ver Javadoc de
        // ResourceLimitValues#limitValueOf.
        assertEquals((long) dev.vitorsilverio.n3dsemu.memory.MemoryMap.GENERAL_HEAP_SIZE
                + dev.vitorsilverio.n3dsemu.memory.MemoryMap.LINEAR_HEAP_SIZE, commitLimit);
    }

    @Test
    void getResourceLimitLimitValuesComHandleInvalidaDevolveInvalidHandle() {
        Harness h = newHarness();
        int namesAddress = DATA_BASE;
        h.memory().write32(namesAddress, LimitableResource.COMMIT);

        h.memory().write32(CODE_BASE, ARM_SVC_OPCODE_BASE | 0x39);
        int bogusHandle = 0x1234;
        CpuState state = new CpuState(DATA_BASE + 0x100, bogusHandle, namesAddress, 1,
                0, 0, CODE_BASE + ARM_INSTRUCTION_SIZE, 0);
        CpuState result = h.svcTable().dispatcher().dispatch(0, state);

        assertEquals(Result.INVALID_HANDLE.code(), result.r0());
    }

    @Test
    void svcNaoImplementadaContinuaLogandoELancandoComoNaG1() {
        Harness h = newHarness();
        h.memory().write32(CODE_BASE, ARM_SVC_OPCODE_BASE | 0x1A); // svcCreateTimer, fora do escopo da G2
        CpuState state = new CpuState(0, 0, 0, 0, 0, 0, CODE_BASE + ARM_INSTRUCTION_SIZE, 0);

        UnsupportedSvcException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedSvcException.class, () -> h.svcTable().dispatcher().dispatch(0, state));

        assertEquals(0x1A, thrown.call().number());
    }

    // ── G2 PR2: threads, sincronização, AddressArbiter, IPC ─────────────────────────────────

    @Test
    void createAddressArbiterDevolveUmaHandleValida() {
        Harness h = newHarness();

        CpuState result = dispatch(h, 0x21, 0, 0, 0, 0);

        assertEquals(Result.SUCCESS.code(), result.r0());
        assertTrue(h.handles().resolve(result.r1()).orElseThrow() instanceof AddressArbiterObject);
    }

    @Test
    void arbitrateAddressSignalSemNinguemEsperandoDevolveSucesso() {
        Harness h = newHarness();
        CpuState arbiter = dispatch(h, 0x21, 0, 0, 0, 0);
        int arbiterHandle = arbiter.r1();

        CpuState result = dispatch(h, 0x22, arbiterHandle, DATA_BASE, ArbitrationType.SIGNAL, 1);

        assertEquals(Result.SUCCESS.code(), result.r0());
    }

    @Test
    void arbitrateAddressWaitIfLessThanComCondicaoJaFalsaNaoBloqueia() {
        Harness h = newHarness();
        CpuState arbiter = dispatch(h, 0x21, 0, 0, 0, 0);
        int arbiterHandle = arbiter.r1();
        h.memory().write32(DATA_BASE, 5);

        CpuState result = dispatch(h, 0x22, arbiterHandle, DATA_BASE, ArbitrationType.WAIT_IF_LESS_THAN, 3);

        assertEquals(Result.SUCCESS.code(), result.r0());
    }

    @Test
    void createThreadRegistraUmaThreadNovaProntaESemForcarTroca() {
        Harness h = newHarness();

        CpuState result = dispatch(h, 0x08, 0x20, CODE_BASE, 0xCAFE, DATA_BASE + PAGE_SIZE / 2);

        assertEquals(Result.SUCCESS.code(), result.r0());
        assertTrue(h.handles().resolve(result.r1()).orElseThrow() instanceof ThreadObject);
        // Sem troca de contexto: a thread principal continua sendo a corrente.
        assertEquals(h.mainThread(), h.scheduler().current());
    }

    @Test
    void getSetThreadPriorityDaThreadPrincipal() {
        Harness h = newHarness();

        CpuState setResult = dispatch(h, 0x0C, HandleTable.CURRENT_THREAD_HANDLE, 0x10, 0, 0);
        CpuState getResult = dispatch(h, 0x0B, 0, HandleTable.CURRENT_THREAD_HANDLE, 0, 0);

        assertEquals(Result.SUCCESS.code(), setResult.r0());
        assertEquals(Result.SUCCESS.code(), getResult.r0());
        assertEquals(0x10, getResult.r1());
    }

    @Test
    void createMutexDestravadoPermiteReleaseSemErro() {
        Harness h = newHarness();
        CpuState created = dispatch(h, 0x13, 0, 0, 0, 0); // initiallyLocked=false
        int mutexHandle = created.r1();
        // Adquire manualmente via WaitSynchronization1 antes de liberar (mutex destravado não
        // tem dono ainda — svcReleaseMutex sem dono é erro, mesma armadilha da task).
        dispatch(h, 0x24, mutexHandle, 0, 0, 0);

        CpuState release = dispatch(h, 0x14, mutexHandle, 0, 0, 0);

        assertEquals(Result.SUCCESS.code(), release.r0());
    }

    @Test
    void createMutexInicialmenteTravadoTemAThreadCorrenteComoDono() {
        Harness h = newHarness();

        CpuState created = dispatch(h, 0x13, 0, 1, 0, 0); // initiallyLocked=true
        CpuState release = dispatch(h, 0x14, created.r1(), 0, 0, 0);

        assertEquals(Result.SUCCESS.code(), release.r0());
    }

    @Test
    void createSemaphoreEReleaseDevolveContagemAnterior() {
        Harness h = newHarness();
        CpuState created = dispatch(h, 0x15, 0, 0, 3, 0); // initialCount=0, maxCount=3
        int semaphoreHandle = created.r1();

        CpuState release = dispatch(h, 0x16, 0, semaphoreHandle, 1, 0);

        assertEquals(Result.SUCCESS.code(), release.r0());
        assertEquals(0, release.r1());
    }

    @Test
    void createEventOneshotSignalEWaitSynchronization1DevolveSucessoEConsome() {
        Harness h = newHarness();
        CpuState created = dispatch(h, 0x17, 0, ResetType.ONESHOT, 0, 0);
        int eventHandle = created.r1();
        dispatch(h, 0x18, eventHandle, 0, 0, 0); // svcSignalEvent

        CpuState wait1 = dispatch(h, 0x24, eventHandle, 0, 0, 0);
        // Oneshot já consumiu o sinal — uma segunda espera com timeout=0 expira.
        CpuState wait2 = dispatch(h, 0x24, eventHandle, 0, 0, 0);

        assertEquals(Result.SUCCESS.code(), wait1.r0());
        assertEquals(Result.TIMEOUT.code(), wait2.r0());
    }

    @Test
    void eventStickyPermaneceSinalizadoAteClear() {
        Harness h = newHarness();
        CpuState created = dispatch(h, 0x17, 0, ResetType.STICKY, 0, 0);
        int eventHandle = created.r1();
        dispatch(h, 0x18, eventHandle, 0, 0, 0);

        CpuState wait1 = dispatch(h, 0x24, eventHandle, 0, 0, 0);
        CpuState wait2 = dispatch(h, 0x24, eventHandle, 0, 0, 0);
        dispatch(h, 0x19, eventHandle, 0, 0, 0); // svcClearEvent
        CpuState wait3 = dispatch(h, 0x24, eventHandle, 0, 0, 0);

        assertEquals(Result.SUCCESS.code(), wait1.r0());
        assertEquals(Result.SUCCESS.code(), wait2.r0());
        assertEquals(Result.TIMEOUT.code(), wait3.r0());
    }

    @Test
    void waitSynchronization1ComTimeoutZeroENadaDisponivelDevolveTimeout() {
        Harness h = newHarness();
        CpuState created = dispatch(h, 0x15, 0, 0, 1, 0); // semáforo vazio

        CpuState result = dispatch(h, 0x24, created.r1(), 0, 0, 0);

        assertEquals(Result.TIMEOUT.code(), result.r0());
    }

    @Test
    void waitSynchronizationNWaitAnyDevolveIndiceDoObjetoSinalizado() {
        Harness h = newHarness();
        CpuState semaphore = dispatch(h, 0x15, 0, 0, 1, 0); // vazio, não disponível
        CpuState event = dispatch(h, 0x17, 0, ResetType.STICKY, 0, 0);
        dispatch(h, 0x18, event.r1(), 0, 0, 0);

        h.memory().write32(DATA_BASE, semaphore.r1());
        h.memory().write32(DATA_BASE + 4, event.r1());
        h.memory().write32(CODE_BASE, ARM_SVC_OPCODE_BASE | 0x25);
        CpuState state = new CpuState(0, DATA_BASE, 2, 0 /* waitAll=false */,
                0, 0, CODE_BASE + ARM_INSTRUCTION_SIZE, 0);
        CpuState result = h.svcTable().dispatcher().dispatch(0, state);

        assertEquals(Result.SUCCESS.code(), result.r0());
        assertEquals(1, result.r1()); // índice 1 = o evento, o único disponível
    }

    @Test
    void waitSynchronizationNWaitAllSoResolveQuandoTodosDisponiveis() {
        Harness h = newHarness();
        CpuState semaphore = dispatch(h, 0x15, 0, 0, 1, 0); // vazio
        CpuState event = dispatch(h, 0x17, 0, ResetType.STICKY, 0, 0);
        dispatch(h, 0x18, event.r1(), 0, 0, 0); // evento já sinalizado, semáforo ainda não

        h.memory().write32(DATA_BASE, semaphore.r1());
        h.memory().write32(DATA_BASE + 4, event.r1());
        CpuState waitAllState = new CpuState(0, DATA_BASE, 2, 1 /* waitAll=true */,
                0, 0, CODE_BASE + ARM_INSTRUCTION_SIZE, 0);

        // `dispatch(...)` reescreve CODE_BASE com o opcode svc da chamada que ele mesmo faz —
        // cada disparo manual do WaitSynchronizationN precisa reescrever 0x25 de volta antes,
        // já que `realSvcNumber` relê a instrução crua da memória (ver Javadoc de SvcTable).
        h.memory().write32(CODE_BASE, ARM_SVC_OPCODE_BASE | 0x25);
        CpuState notYet = h.svcTable().dispatcher().dispatch(0, waitAllState);
        // Nem tudo disponível ainda (semáforo vazio) e timeout=0 — expira sem sincronizar.
        assertEquals(Result.TIMEOUT.code(), notYet.r0());

        dispatch(h, 0x16, 0, semaphore.r1(), 1, 0); // libera o semáforo
        h.memory().write32(CODE_BASE, ARM_SVC_OPCODE_BASE | 0x25);
        CpuState nowReady = h.svcTable().dispatcher().dispatch(0, waitAllState);
        assertEquals(Result.SUCCESS.code(), nowReady.r0());
    }

    @Test
    void connectToPortDevolveUmaSessaoComONomeLido() {
        Harness h = newHarness();
        String portName = "srv:";
        byte[] bytes = portName.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            h.memory().write8(DATA_BASE + i, bytes[i]);
        }
        h.memory().write8(DATA_BASE + bytes.length, (byte) 0);

        CpuState result = dispatch(h, 0x2D, 0, DATA_BASE, 0, 0);

        assertEquals(Result.SUCCESS.code(), result.r0());
        assertEquals(new SessionObject(portName), h.handles().resolve(result.r1()).orElseThrow());
    }

    /// **G3**: ao contrário da G2 (que lançava na primeira `svcSendSyncRequest` real, "não
    /// inclui" daquela task), esta SVC agora despacha de verdade — mas uma sessão sobre uma
    /// PORTA sem serviço registrado (nenhum registrado neste harness isolado) não deve lançar:
    /// loga e escreve um erro genérico no buffer (mesma política de "nunca travar" da G3, ver
    /// Javadoc de {@link SvcTable#handleSendSyncRequest}), com `r0=SUCCESS` (a SVC em si
    /// funcionou — o erro é IPC-level, no buffer, não no valor de retorno da svc).
    @Test
    void sendSyncRequestSemServicoRegistradoNaoLancaEEscreveErroGenericoNoBuffer() {
        Harness h = newHarness();
        String portName = "sem-svc:U"; // <= 11 chars (3dbrew: nome de porta, MAX_PORT_NAME_LENGTH)
        byte[] bytes = portName.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            h.memory().write8(DATA_BASE + i, bytes[i]);
        }
        h.memory().write8(DATA_BASE + bytes.length, (byte) 0);
        CpuState session = dispatch(h, 0x2D, 0, DATA_BASE, 0, 0);
        int tls = h.mainThread().tlsAddress();
        int header = (0x1234 << 16); // comando 0x1234, 0 params
        h.memory().write32(tls + dev.vitorsilverio.n3dsemu.memory.MemoryMap.TLS_COMMAND_BUFFER_OFFSET, header);

        CpuState result = dispatch(h, 0x32, session.r1(), 0, 0, 0);

        assertEquals(Result.SUCCESS.code(), result.r0());
        int responseHeader = h.memory().read32(tls + dev.vitorsilverio.n3dsemu.memory.MemoryMap.TLS_COMMAND_BUFFER_OFFSET);
        int responseResult = h.memory().read32(tls + dev.vitorsilverio.n3dsemu.memory.MemoryMap.TLS_COMMAND_BUFFER_OFFSET + 4);
        assertEquals(0x1234, responseHeader >>> 16);
        assertEquals(Result.SERVICE_COMMAND_NOT_IMPLEMENTED.code(), responseResult);
        assertTrue(h.stdout().toString(StandardCharsets.UTF_8).contains(portName));
    }

    @Test
    void sendSyncRequestDespachaParaOServicoRegistrado() {
        Harness h = newHarness();
        String portName = "eco:U";
        byte[] bytes = portName.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            h.memory().write8(DATA_BASE + i, bytes[i]);
        }
        h.memory().write8(DATA_BASE + bytes.length, (byte) 0);
        h.serviceRegistry().register(new dev.vitorsilverio.n3dsemu.service.Service() {
            @Override
            public String name() {
                return portName;
            }

            @Override
            public void handleRequest(dev.vitorsilverio.n3dsemu.ipc.IpcRequest request,
                                       dev.vitorsilverio.n3dsemu.ipc.IpcResponse response) {
                response.header(request.commandId(), 1, 0);
                response.result(Result.SUCCESS);
            }
        });
        CpuState session = dispatch(h, 0x2D, 0, DATA_BASE, 0, 0);
        int tls = h.mainThread().tlsAddress();
        h.memory().write32(tls + dev.vitorsilverio.n3dsemu.memory.MemoryMap.TLS_COMMAND_BUFFER_OFFSET, 0x9999 << 16);

        CpuState result = dispatch(h, 0x32, session.r1(), 0, 0, 0);

        assertEquals(Result.SUCCESS.code(), result.r0());
        int responseResult = h.memory().read32(tls + dev.vitorsilverio.n3dsemu.memory.MemoryMap.TLS_COMMAND_BUFFER_OFFSET + 4);
        assertEquals(Result.SUCCESS.code(), responseResult);
    }

    @Test
    void sleepThreadComZeroCedeAExecucaoESeguraDeVoltaParaAMesmaThreadUnica() {
        Harness h = newHarness();

        CpuState result = dispatch(h, 0x0A, 0, 0, 0, 0);

        // Nenhuma outra thread pronta: a própria thread principal é escolhida de volta —
        // não há deadlock nem NPE de contexto salvo ausente (ver Javadoc de
        // Scheduler#switchTo).
        assertEquals(h.mainThread(), h.scheduler().current());
        assertEquals(ThreadObject.State.RUNNING, h.mainThread().state());
        org.junit.jupiter.api.Assertions.assertNotNull(result);
    }

    @Test
    void sleepThreadComTimeoutAdiantaORelogioVirtualEAcordaSozinha() {
        Harness h = newHarness();
        long before = h.core().cycles();

        dispatch(h, 0x0A, 1_000_000, 0, 0, 0); // 1 ms

        assertEquals(h.mainThread(), h.scheduler().current());
        assertEquals(ThreadObject.State.RUNNING, h.mainThread().state());
        org.junit.jupiter.api.Assertions.assertTrue(h.core().cycles() > before);
    }
}
