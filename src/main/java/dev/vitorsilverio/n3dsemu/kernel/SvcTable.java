package dev.vitorsilverio.n3dsemu.kernel;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpsrRegister;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.swi.CpuState;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import dev.vitorsilverio.n3dsemu.memory.MemoryMap;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// Tabela de SVCs do kernel Horizon (RFC-N3DSEMU D2). A G1 (marco M1) deixou toda `svc`
/// interceptada pelo {@link SwiDispatcher} do arm-jitter (mesmo mecanismo que gbaemu/ndsemu
/// usam para a BIOS), traçada, e sempre lançando {@link UnsupportedSvcException}. A G2 PR1
/// implementou memória/handles/diagnóstico. Esta parte (G2 PR2, fecha o marco M2) implementa
/// threads + sincronização (mutex/semáforo/evento/`AddressArbiter`) + o começo de IPC
/// (`svcConnectToPort` cria a sessão; `svcSendSyncRequest` loga o cabeçalho e lança — "não
/// inclui" da task, serviços de verdade são a G3).
///
/// **Achado real desta sessão**: `svcCreateAddressArbiter` (`0x21`) NÃO está na lista de SVCs
/// da task — mas `svcArbitrateAddress` (`0x22`, que ESTÁ) recebe um handle de arbiter em `r0`
/// que só existe se algo o criar antes, e o próprio teste da G2 PR1 (`SvcTableTest`) já
/// documentava isso como pendência ("para o boot real progredir além da primeira svc
/// observada"). Implementado aqui como pré-requisito estritamente necessário para `0x22`
/// funcionar (o libctru cria um arbiter global no arranque, antes de `main()`, para as travas
/// leves `LightLock`) — não é "implementar por precaução" nada fora da lista, é a única forma
/// de o SVC que ESTÁ na lista produzir algo além de handle inválida.
///
/// **Convenção de registradores por SVC**: nenhuma foi assumida por analogia — cada uma foi
/// conferida contra a montagem REAL do wrapper C correspondente em `libctru.a`
/// (`arm-none-eabi-objdump -d`, libctru 2.7.0, instalado em `C:\devkitPro\libctru\lib`), porque
/// a ordem de parâmetros do C nem sempre bate com a ordem de registradores que o kernel realmente
/// lê (ex.: `svcControlMemory` recebe `operation` em `r0`, não `addr0` — o wrapper C só usa `r0`
/// de entrada como ponteiro de saída, sem relação com o argumento de mesmo nome do kernel). Cada
/// método `handleXxx` documenta a convenção observada.
///
/// **Achado real (não é um bug desta task, documentado aqui porque explica o código
/// abaixo):** `ArmDecoder`/`ThumbDecoder` do arm-jitter (compartilhados com gbaemu/ndsemu)
/// decodificam o imediato de 24 bits de `SWI`/`SVC` em modo ARM como `(raw & 0xFFFFFF) >>>
/// 16` — a convenção do BIOS do GBA/NDS, onde o número da SWI mora nos 8 bits ALTOS do campo
/// (GBATEK). O kernel Horizon do 3DS usa a convenção OPOSTA: `svc 0x21` grava `0x21` direto
/// no campo de 24 bits (confirmado via `objdump` no `libctru.a` real — `ef000021`), então o
/// valor que chegaria por {@code swi.immediate()}/o parâmetro do dispatcher seria sempre 0
/// para qualquer SVC real do 3DS (o byte baixo, onde mora o número, é descartado pelo `>>>
/// 16` antes de qualquer código do host ver o valor). Em THUMB o problema não existe — o
/// campo de `SVC` de 8 bits já É o número, sem shift —, mas o `ARM11_MPCORE` (B5.2: ARMv6K
/// COM Thumb-1 clássico, só SEM Thumb-2 largo) pode rodar código em qualquer um dos dois
/// estados. Mudar o decoder compartilhado quebraria a convenção GBA/NDS para
/// gbaemu/ndsemu/armbox (G3: sem breaking change) — em vez disso, {@link #handle} relê a
/// instrução `svc` crua da memória do guest (endereço = `pc - 4` em ARM ou `pc - 2` em THUMB,
/// já que {@code state.pc()} é sempre o PC SEQUENCIAL, avançado antes do dispatch) e extrai o
/// byte baixo do imediato bruto — a convenção real do Horizon, igual nos dois estados.
public final class SvcTable {
    private static final int DEFAULT_TRACE_CAPACITY = 32;
    private static final int ARM_INSTRUCTION_SIZE = 4;
    private static final int THUMB_INSTRUCTION_SIZE = 2;
    private static final int HORIZON_SVC_NUMBER_MASK = 0xFF;

    // Índices de registrador ARM além dos expostos por CpuState (r0-r3/sp/lr/pc/cpsr) — lidos/
    // escritos direto no ArmCore anexado (ver #attach).
    private static final int REGISTER_R0 = 0;
    private static final int REGISTER_R4 = 4;
    private static final int REGISTER_R5 = 5;

    // Números das SVCs implementadas por esta task (G2 PR1+PR2) — ver tabela da spec
    // g2-kernel-hle-svc.md. Nomeadas para não repetir literais hex nos `case` do switch.
    private static final int SVC_CONTROL_MEMORY = 0x01;
    private static final int SVC_QUERY_MEMORY = 0x02;
    private static final int SVC_EXIT_PROCESS = 0x03;
    private static final int SVC_CREATE_THREAD = 0x08;
    private static final int SVC_EXIT_THREAD = 0x09;
    private static final int SVC_SLEEP_THREAD = 0x0A;
    private static final int SVC_GET_THREAD_PRIORITY = 0x0B;
    private static final int SVC_SET_THREAD_PRIORITY = 0x0C;
    private static final int SVC_CREATE_MUTEX = 0x13;
    private static final int SVC_RELEASE_MUTEX = 0x14;
    private static final int SVC_CREATE_SEMAPHORE = 0x15;
    private static final int SVC_RELEASE_SEMAPHORE = 0x16;
    private static final int SVC_CREATE_EVENT = 0x17;
    private static final int SVC_SIGNAL_EVENT = 0x18;
    private static final int SVC_CLEAR_EVENT = 0x19;
    private static final int SVC_CREATE_MEMORY_BLOCK = 0x1E;
    private static final int SVC_MAP_MEMORY_BLOCK = 0x1F;
    private static final int SVC_UNMAP_MEMORY_BLOCK = 0x20;
    // 0x21 (svcCreateAddressArbiter) não está na lista de SVCs da task — ver Javadoc da classe.
    private static final int SVC_CREATE_ADDRESS_ARBITER = 0x21;
    private static final int SVC_ARBITRATE_ADDRESS = 0x22;
    private static final int SVC_CLOSE_HANDLE = 0x23;
    private static final int SVC_WAIT_SYNCHRONIZATION_1 = 0x24;
    private static final int SVC_WAIT_SYNCHRONIZATION_N = 0x25;
    private static final int SVC_DUPLICATE_HANDLE = 0x27;
    private static final int SVC_GET_SYSTEM_TICK = 0x28;
    private static final int SVC_CONNECT_TO_PORT = 0x2D;
    private static final int SVC_SEND_SYNC_REQUEST = 0x32;
    private static final int SVC_GET_PROCESS_ID = 0x35;
    private static final int SVC_GET_THREAD_ID = 0x37;
    private static final int SVC_GET_RESOURCE_LIMIT = 0x38;
    // 0x39 (svcGetResourceLimitLimitValues) não está na lista de SVCs da task original — mesmo
    // achado real de svcCreateAddressArbiter (ver Javadoc da classe): o crt0/libctru chama esta
    // SVC ANTES de svcGetResourceLimitCurrentValues, no mesmo trecho de __system_allocateHeaps,
    // para descobrir o teto de COMMIT antes de pedir svcControlMemory(MEMOP_ALLOC). Sem ela, o
    // array de saída fica com o que já estava na pilha do guest (geralmente 0), o tamanho
    // calculado do heap linear vira 0 e o ALLOC subsequente falha com MISALIGNED_SIZE — é a
    // causa raiz documentada no `tasks/FILA-EXECUCAO.md` (sessão de investigação 2026-08-16),
    // não uma SVC "implementada por precaução".
    private static final int SVC_GET_RESOURCE_LIMIT_LIMIT_VALUES = 0x39;
    private static final int SVC_GET_RESOURCE_LIMIT_CURRENT_VALUES = 0x3A;
    private static final int SVC_BREAK = 0x3C;
    private static final int SVC_OUTPUT_DEBUG_STRING = 0x3D;

    /// 3dbrew: nomes de porta têm no máximo 11 caracteres + terminador nulo.
    private static final int MAX_PORT_NAME_LENGTH = 11;

    // Layout do cabeçalho de comando IPC (RFC §3: buffer em TLS+0x80; 3dbrew: "IPC Request/
    // Response structure") — só usado para o log de `svcSendSyncRequest` (diagnóstico, nunca
    // decodificado em profundidade nesta task).
    private static final int IPC_COMMAND_ID_SHIFT = 16;
    private static final int IPC_NORMAL_PARAMS_SHIFT = 6;
    private static final int IPC_PARAM_COUNT_MASK = 0x3F;

    /// `PageInfo::flags` não tem nenhum uso documentado conhecido — o Horizon real sempre
    /// devolve `0` (3dbrew: `SVC` não lista nenhum bit definido).
    private static final int PAGE_INFO_FLAGS_ALWAYS_ZERO = 0;
    private static final int BYTES_PER_S32 = 4;
    private static final int BYTES_PER_S64 = 8;
    private static final int LONG_LOW_WORD_SHIFT = 0;
    private static final int LONG_HIGH_WORD_SHIFT = 32;

    /// Um `Result` do Horizon é `0` quando bem-sucedido (qualquer valor não-zero é um
    /// código de erro — bits de descrição/módulo/nível, ver 3dbrew: Error codes). Gravado em
    /// `r0` antes de {@link UnsupportedSvcException} propagar (ver Javadoc da classe): sem
    /// isso, o chamador recebe o `r0` de ENTRADA (o próprio argumento da chamada, não um
    /// `Result`) como se fosse o retorno, e quase sempre o interpreta como um erro — o
    /// código do libctru entra em ramos de tratamento de falha quase imediatamente,
    /// divergindo de qualquer sequência real de `svc`s de inicialização. `0` mantém a
    /// execução no caminho feliz o quanto der para as SVCs AINDA não implementadas (o
    /// {@link #handle} abaixo, `default` do switch).
    private static final int RESULT_SUCCESS_PLACEHOLDER = Result.SUCCESS.code();

    private final AddressSpace memory;
    private final PrintStream traceLog;
    private final boolean traceEnabled;
    private final int traceCapacity;
    private final Deque<SvcCall> recentCalls = new ArrayDeque<>();
    private final HandleTable handles;
    private final MemoryManager memoryManager;
    private final Scheduler scheduler;
    private ArmCore core;

    public SvcTable(AddressSpace memory, PrintStream traceLog, boolean traceEnabled,
                     HandleTable handles, MemoryManager memoryManager, Scheduler scheduler) {
        this(memory, traceLog, traceEnabled, DEFAULT_TRACE_CAPACITY, handles, memoryManager, scheduler);
    }

    public SvcTable(AddressSpace memory, PrintStream traceLog, boolean traceEnabled, int traceCapacity,
                     HandleTable handles, MemoryManager memoryManager, Scheduler scheduler) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.traceLog = Objects.requireNonNull(traceLog, "traceLog");
        this.traceEnabled = traceEnabled;
        this.traceCapacity = traceCapacity;
        this.handles = Objects.requireNonNull(handles, "handles");
        this.memoryManager = Objects.requireNonNull(memoryManager, "memoryManager");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /// Liga esta tabela ao {@link ArmCore} que a criou (necessário para ler/gravar registradores
    /// além de r0-r3 e para {@link ArmCore#cycles()} — `svcGetSystemTick`). Chamado pelo
    /// `N3dsMachine` logo após construir o core, já que o {@link SwiDispatcher} precisa existir
    /// ANTES do core (dependência circular do construtor).
    public void attach(ArmCore core) {
        this.core = Objects.requireNonNull(core, "core");
    }

    /// Cria o {@link SwiDispatcher} que encaminha toda `svc` para {@link #handle}.
    public SwiDispatcher dispatcher() {
        SwiDispatcher dispatcher = SwiDispatcher.empty();
        dispatcher.fallbackWithNumber(this::handle);
        return dispatcher;
    }

    private CpuState handle(int decoderImmediate, CpuState state) {
        int svcNumber = realSvcNumber(state);
        SvcCall call = new SvcCall(svcNumber, HorizonSvcNames.nameOf(svcNumber), state.r0(), state.r1(), state.pc());
        recentCalls.addLast(call);
        while (recentCalls.size() > traceCapacity) {
            recentCalls.removeFirst();
        }
        if (traceEnabled) {
            traceLog.println(call.format());
        }

        return switch (svcNumber) {
            case SVC_CONTROL_MEMORY -> handleControlMemory(state);
            case SVC_QUERY_MEMORY -> handleQueryMemory(state);
            case SVC_EXIT_PROCESS -> throw handleExitProcess();
            case SVC_CREATE_THREAD -> handleCreateThread(state);
            case SVC_EXIT_THREAD -> handleExitThread();
            case SVC_SLEEP_THREAD -> handleSleepThread(state);
            case SVC_GET_THREAD_PRIORITY -> handleGetThreadPriority(state);
            case SVC_SET_THREAD_PRIORITY -> handleSetThreadPriority(state);
            case SVC_CREATE_MUTEX -> handleCreateMutex(state);
            case SVC_RELEASE_MUTEX -> handleReleaseMutex(state);
            case SVC_CREATE_SEMAPHORE -> handleCreateSemaphore(state);
            case SVC_RELEASE_SEMAPHORE -> handleReleaseSemaphore(state);
            case SVC_CREATE_EVENT -> handleCreateEvent(state);
            case SVC_SIGNAL_EVENT -> handleSignalEvent(state);
            case SVC_CLEAR_EVENT -> handleClearEvent(state);
            case SVC_CREATE_MEMORY_BLOCK -> handleCreateMemoryBlock(state);
            case SVC_MAP_MEMORY_BLOCK -> handleMapMemoryBlock(state);
            case SVC_UNMAP_MEMORY_BLOCK -> handleUnmapMemoryBlock(state);
            case SVC_CREATE_ADDRESS_ARBITER -> handleCreateAddressArbiter(state);
            case SVC_ARBITRATE_ADDRESS -> handleArbitrateAddress(state);
            case SVC_CLOSE_HANDLE -> handleCloseHandle(state);
            case SVC_WAIT_SYNCHRONIZATION_1 -> handleWaitSynchronization1(state);
            case SVC_WAIT_SYNCHRONIZATION_N -> handleWaitSynchronizationN(state);
            case SVC_DUPLICATE_HANDLE -> handleDuplicateHandle(state);
            case SVC_GET_SYSTEM_TICK -> handleGetSystemTick(state);
            case SVC_CONNECT_TO_PORT -> handleConnectToPort(state);
            case SVC_SEND_SYNC_REQUEST -> throw handleSendSyncRequest(state);
            case SVC_GET_PROCESS_ID -> handleGetProcessId(state);
            case SVC_GET_THREAD_ID -> handleGetThreadId(state);
            case SVC_GET_RESOURCE_LIMIT -> handleGetResourceLimit(state);
            case SVC_GET_RESOURCE_LIMIT_LIMIT_VALUES -> handleGetResourceLimitLimitValues(state);
            case SVC_GET_RESOURCE_LIMIT_CURRENT_VALUES -> handleGetResourceLimitCurrentValues(state);
            case SVC_BREAK -> throw handleBreak(state);
            case SVC_OUTPUT_DEBUG_STRING -> handleOutputDebugString(state);
            default -> throw defaultUnsupported(call);
        };
    }

    private static long combineS64(int lowWord, int highWord) {
        return (((long) highWord) << LONG_HIGH_WORD_SHIFT) | (lowWord & 0xFFFF_FFFFL);
    }

    private UnsupportedSvcException defaultUnsupported(SvcCall call) {
        core.setRegister(REGISTER_R0, RESULT_SUCCESS_PLACEHOLDER);
        return new UnsupportedSvcException(call);
    }

    /// `svcControlMemory` — kernel real (não a assinatura C, ver Javadoc da classe): `r0`=
    /// `MemOp` bruto, `r1`=`addr0`, `r2`=`addr1`, `r3`=`size`, `r4`=`MemPerm`. Saída: `r0`=
    /// `Result`, `r1`=endereço resultante.
    private CpuState handleControlMemory(CpuState state) {
        int operation = state.r0();
        int addr0 = state.r1();
        int addr1 = state.r2();
        int size = state.r3();
        int permission = core.register(REGISTER_R4);
        MemoryManager.ControlMemoryResult result = memoryManager.controlMemory(operation, addr0, addr1, size, permission);
        return new CpuState(result.result().code(), result.address(), state.r2(), state.r3(),
                state.sp(), state.lr(), state.pc(), state.cpsr());
    }

    /// `svcQueryMemory` — kernel real: `r2`=endereço consultado (`r0`/`r1` de entrada são só os
    /// ponteiros de saída do wrapper C, o kernel não os lê). Saída: `r0`=`Result`, `r1`=
    /// `base_addr`, `r2`=`size`, `r3`=`perm`, `r4`=`state`, `r5`=`PageInfo.flags`.
    private CpuState handleQueryMemory(CpuState state) {
        int address = state.r2();
        MemoryRegion region = memoryManager.queryMemory(address);
        core.setRegister(REGISTER_R4, region.state());
        core.setRegister(REGISTER_R5, PAGE_INFO_FLAGS_ALWAYS_ZERO);
        return new CpuState(Result.SUCCESS.code(), region.base(), region.size(), region.permission(),
                state.sp(), state.lr(), state.pc(), state.cpsr());
    }

    private KernelHaltException handleExitProcess() {
        core.setRegister(REGISTER_R0, RESULT_SUCCESS_PLACEHOLDER);
        return new KernelHaltException("svcExitProcess", KernelHaltException.Reason.PROCESS_EXIT);
    }

    // ── threads (G2 PR2) ────────────────────────────────────────────────────────────────────

    /// `svcCreateThread` — kernel real: `r0`=`priority`, `r1`=`entrypoint`, `r2`=`arg`,
    /// `r3`=`stack_top`, `r4`=`processor_id` (ignorado — RFC D1: um único núcleo, sem afinidade
    /// de processador). Saída: `r0`=`Result`, `r1`=handle nova.
    private CpuState handleCreateThread(CpuState state) {
        int priority = state.r0();
        int entryPoint = state.r1();
        int arg = state.r2();
        int stackTop = state.r3();
        Scheduler.CreateThreadResult created = scheduler.createThread(priority, entryPoint, arg, stackTop);
        if (!created.result().isSuccess()) {
            return new CpuState(created.result().code(), 0, state.r2(), state.r3(),
                    state.sp(), state.lr(), state.pc(), state.cpsr());
        }
        int handle = handles.create(created.thread());
        return new CpuState(Result.SUCCESS.code(), handle, state.r2(), state.r3(),
                state.sp(), state.lr(), state.pc(), state.cpsr());
    }

    /// `svcExitThread` — sem argumentos nem `Result` de retorno (a thread nunca mais roda).
    /// Sempre troca de contexto (ver Javadoc de {@link Scheduler}): devolve
    /// {@code core.toCpuState()}, já refletindo a PRÓXIMA thread.
    private CpuState handleExitThread() {
        scheduler.exitThread();
        return core.toCpuState();
    }

    /// `svcSleepThread` — kernel real (igual à assinatura C, `void svcSleepThread(s64 ns)`):
    /// `r0:r1`=nanossegundos. Sem `Result` de retorno. Pode trocar de contexto (yield real se
    /// `ns==0`, ou bloqueio com timeout) — sempre devolve {@code core.toCpuState()}.
    private CpuState handleSleepThread(CpuState state) {
        long nanoseconds = combineS64(state.r0(), state.r1());
        scheduler.sleep(nanoseconds);
        return core.toCpuState();
    }

    /// `svcGetThreadPriority` — kernel real: `r1`=handle da thread (`r0` de entrada é só o
    /// ponteiro de saída do wrapper C). Saída: `r0`=`Result`, `r1`=prioridade.
    private CpuState handleGetThreadPriority(CpuState state) {
        Optional<KernelObject> object = handles.resolve(state.r1());
        if (object.isEmpty() || !(object.get() instanceof ThreadObject thread)) {
            return new CpuState(Result.INVALID_HANDLE.code(), state.r1(), state.r2(), state.r3(),
                    state.sp(), state.lr(), state.pc(), state.cpsr());
        }
        return new CpuState(Result.SUCCESS.code(), thread.priority(), state.r2(), state.r3(),
                state.sp(), state.lr(), state.pc(), state.cpsr());
    }

    /// `svcSetThreadPriority` — kernel real: `r0`=handle da thread, `r1`=prioridade nova.
    /// Saída: `r0`=`Result`.
    private CpuState handleSetThreadPriority(CpuState state) {
        Optional<KernelObject> object = handles.resolve(state.r0());
        if (object.isEmpty() || !(object.get() instanceof ThreadObject thread)) {
            return state.withR0(Result.INVALID_HANDLE.code());
        }
        thread.setPriority(state.r1());
        return state.withR0(Result.SUCCESS.code());
    }

    // ── sincronização: mutex/semáforo/evento (G2 PR2) ──────────────────────────────────────

    /// `svcCreateMutex` — kernel real: `r1`=`initiallyLocked` (bool, `r0` de entrada ignorado).
    /// Saída: `r0`=`Result`, `r1`=handle nova.
    private CpuState handleCreateMutex(CpuState state) {
        boolean initiallyLocked = state.r1() != 0;
        MutexObject mutex = new MutexObject(initiallyLocked ? scheduler.current() : null);
        int handle = handles.create(mutex);
        return new CpuState(Result.SUCCESS.code(), handle, state.r2(), state.r3(),
                state.sp(), state.lr(), state.pc(), state.cpsr());
    }

    /// `svcReleaseMutex` — kernel real: `r0`=handle. Saída: `r0`=`Result`. Liberar um mutex que
    /// não é seu é erro (armadilha da task: nunca destrava o mutex de outra thread).
    private CpuState handleReleaseMutex(CpuState state) {
        Optional<KernelObject> object = handles.resolve(state.r0());
        if (object.isEmpty() || !(object.get() instanceof MutexObject mutex)) {
            return state.withR0(Result.INVALID_HANDLE.code());
        }
        return state.withR0(mutex.release(scheduler.current()).code());
    }

    /// `svcCreateSemaphore` — kernel real: `r1`=`initialCount`, `r2`=`maxCount` (`r0` de
    /// entrada ignorado). Saída: `r0`=`Result`, `r1`=handle nova.
    private CpuState handleCreateSemaphore(CpuState state) {
        SemaphoreObject semaphore = new SemaphoreObject(state.r1(), state.r2());
        int handle = handles.create(semaphore);
        return new CpuState(Result.SUCCESS.code(), handle, state.r2(), state.r3(),
                state.sp(), state.lr(), state.pc(), state.cpsr());
    }

    /// `svcReleaseSemaphore` — kernel real: `r1`=handle, `r2`=`releaseCount` (`r0` de entrada
    /// ignorado). Saída: `r0`=`Result`, `r1`=contagem ANTERIOR à liberação.
    private CpuState handleReleaseSemaphore(CpuState state) {
        Optional<KernelObject> object = handles.resolve(state.r1());
        if (object.isEmpty() || !(object.get() instanceof SemaphoreObject semaphore)) {
            return new CpuState(Result.INVALID_HANDLE.code(), state.r1(), state.r2(), state.r3(),
                    state.sp(), state.lr(), state.pc(), state.cpsr());
        }
        SemaphoreObject.ReleaseResult released = semaphore.release(state.r2());
        return new CpuState(released.result().code(), released.previousCount(), state.r2(), state.r3(),
                state.sp(), state.lr(), state.pc(), state.cpsr());
    }

    /// `svcCreateEvent` — kernel real: `r1`=`resetType` (`r0` de entrada ignorado). Saída:
    /// `r0`=`Result`, `r1`=handle nova.
    private CpuState handleCreateEvent(CpuState state) {
        int resetType = state.r1();
        if (resetType != ResetType.ONESHOT && resetType != ResetType.STICKY) {
            return new CpuState(Result.INVALID_ENUM_VALUE.code(), 0, state.r2(), state.r3(),
                    state.sp(), state.lr(), state.pc(), state.cpsr());
        }
        EventObject event = new EventObject(resetType);
        int handle = handles.create(event);
        return new CpuState(Result.SUCCESS.code(), handle, state.r2(), state.r3(),
                state.sp(), state.lr(), state.pc(), state.cpsr());
    }

    /// `svcSignalEvent` — kernel real: `r0`=handle. Saída: `r0`=`Result`.
    private CpuState handleSignalEvent(CpuState state) {
        Optional<KernelObject> object = handles.resolve(state.r0());
        if (object.isEmpty() || !(object.get() instanceof EventObject event)) {
            return state.withR0(Result.INVALID_HANDLE.code());
        }
        event.signal();
        return state.withR0(Result.SUCCESS.code());
    }

    /// `svcClearEvent` — kernel real: `r0`=handle. Saída: `r0`=`Result`.
    private CpuState handleClearEvent(CpuState state) {
        Optional<KernelObject> object = handles.resolve(state.r0());
        if (object.isEmpty() || !(object.get() instanceof EventObject event)) {
            return state.withR0(Result.INVALID_HANDLE.code());
        }
        event.clear();
        return state.withR0(Result.SUCCESS.code());
    }

    // ── memória compartilhada (G2 PR2 — "necessário para o gsp na G3", per a task) ─────────

    /// `svcCreateMemoryBlock` — kernel real: `r0`=`other_perm`, `r1`=`addr`, `r2`=`size`,
    /// `r3`=`my_perm`. Saída: `r0`=`Result`, `r1`=handle nova. Sem MMU (RFC D2): não aloca
    /// endereço novo (`addr==0`, "escolha um endereço" do 3dbrew, não é exercitado pelo corpus
    /// desta task — o libctru sempre passa um endereço já obtido de `linearAlloc`).
    private CpuState handleCreateMemoryBlock(CpuState state) {
        int otherPermission = state.r0();
        int address = state.r1();
        int size = state.r2();
        int ownerPermission = state.r3();
        if (address == 0) {
            return new CpuState(Result.OUT_OF_RANGE.code(), 0, state.r2(), state.r3(),
                    state.sp(), state.lr(), state.pc(), state.cpsr());
        }
        int handle = handles.create(new MemoryBlockObject(address, size, ownerPermission, otherPermission));
        return new CpuState(Result.SUCCESS.code(), handle, state.r2(), state.r3(),
                state.sp(), state.lr(), state.pc(), state.cpsr());
    }

    /// `svcMapMemoryBlock` — kernel real (igual à assinatura C): `r0`=handle, `r1`=`addr`,
    /// `r2`=`my_perm`, `r3`=`other_perm`. Sem MMU (RFC D2): validação de handle só — mesma
    /// simplificação de `MemoryManager#controlMemory` para `MEMOP_MAP`/`UNMAP`.
    private CpuState handleMapMemoryBlock(CpuState state) {
        Optional<KernelObject> object = handles.resolve(state.r0());
        if (object.isEmpty() || !(object.get() instanceof MemoryBlockObject)) {
            return state.withR0(Result.INVALID_HANDLE.code());
        }
        return state.withR0(Result.SUCCESS.code());
    }

    /// `svcUnmapMemoryBlock` — kernel real: `r0`=handle, `r1`=`addr`.
    private CpuState handleUnmapMemoryBlock(CpuState state) {
        Optional<KernelObject> object = handles.resolve(state.r0());
        if (object.isEmpty() || !(object.get() instanceof MemoryBlockObject)) {
            return state.withR0(Result.INVALID_HANDLE.code());
        }
        return state.withR0(Result.SUCCESS.code());
    }

    // ── AddressArbiter (achado real — ver Javadoc da classe) ────────────────────────────────

    /// `svcCreateAddressArbiter` — sem entrada real (`r0` é só o ponteiro de saída do wrapper
    /// C). Saída: `r0`=`Result`, `r1`=handle nova.
    private CpuState handleCreateAddressArbiter(CpuState state) {
        int handle = handles.create(new AddressArbiterObject());
        return new CpuState(Result.SUCCESS.code(), handle, state.r2(), state.r3(),
                state.sp(), state.lr(), state.pc(), state.cpsr());
    }

    /// `svcArbitrateAddress` — kernel real: `r0`=handle do arbiter, `r1`=`addr`, `r2`=`type`
    /// ({@link ArbitrationType}), `r3`=`value`, `r4:r5`=`timeout_ns` (só lido pelo Horizon real
    /// nos tipos `*_TIMEOUT` — replicado aqui só por clareza, o valor é ignorado nos tipos sem
    /// timeout). Saída: `r0`=`Result`.
    private CpuState handleArbitrateAddress(CpuState state) {
        Optional<KernelObject> object = handles.resolve(state.r0());
        if (object.isEmpty() || !(object.get() instanceof AddressArbiterObject arbiter)) {
            return state.withR0(Result.INVALID_HANDLE.code());
        }
        int address = state.r1();
        int type = state.r2();
        int value = state.r3();
        long timeoutNanos = combineS64(core.register(REGISTER_R4), core.register(REGISTER_R5));

        return switch (type) {
            case ArbitrationType.SIGNAL -> {
                int maxCount = value == ArbitrationType.SIGNAL_ALL ? Integer.MAX_VALUE : value;
                scheduler.signalArbiter(arbiter, address, maxCount);
                yield state.withR0(Result.SUCCESS.code());
            }
            case ArbitrationType.WAIT_IF_LESS_THAN ->
                    arbitrateWait(state, arbiter, address, value, false, -1);
            case ArbitrationType.DECREMENT_AND_WAIT_IF_LESS_THAN ->
                    arbitrateWait(state, arbiter, address, value, true, -1);
            case ArbitrationType.WAIT_IF_LESS_THAN_TIMEOUT ->
                    arbitrateWait(state, arbiter, address, value, false, timeoutNanos);
            case ArbitrationType.DECREMENT_AND_WAIT_IF_LESS_THAN_TIMEOUT ->
                    arbitrateWait(state, arbiter, address, value, true, timeoutNanos);
            default -> state.withR0(Result.INVALID_ENUM_VALUE.code());
        };
    }

    /// `WAIT_IF_LESS_THAN`/`DECREMENT_AND_WAIT_IF_LESS_THAN` (com ou sem timeout): se
    /// `memory[address] >= value`, a condição já não vale — sucesso imediato, sem bloquear
    /// (3dbrew). Senão, decrementa (se `decrement`) e bloqueia — pode trocar de contexto (ver
    /// Javadoc de {@link Scheduler#blockOnArbiter}).
    private CpuState arbitrateWait(CpuState state, AddressArbiterObject arbiter, int address, int value,
                                    boolean decrement, long timeoutNanos) {
        int current = memory.read32(address);
        if (current >= value) {
            return state.withR0(Result.SUCCESS.code());
        }
        if (decrement) {
            memory.write32(address, current - 1);
        }
        Optional<Result> immediate = scheduler.blockOnArbiter(arbiter, address, timeoutNanos);
        return immediate.map(result -> state.withR0(result.code())).orElseGet(core::toCpuState);
    }

    // ── WaitSynchronization (G2 PR2) ────────────────────────────────────────────────────────

    /// `svcWaitSynchronization1` — kernel real: `r0`=handle, `r2:r3`=`nanoseconds` (`r1` é só
    /// preenchimento de alinhamento AAPCS para o `s64`, não usado). Saída: `r0`=`Result`.
    private CpuState handleWaitSynchronization1(CpuState state) {
        Optional<KernelObject> object = handles.resolve(state.r0());
        if (object.isEmpty() || !(object.get() instanceof Waitable waitable)) {
            return state.withR0(Result.INVALID_HANDLE.code());
        }
        long timeoutNanos = combineS64(state.r2(), state.r3());
        Optional<Scheduler.WaitOutcome> outcome =
                scheduler.waitSynchronization(List.of(waitable), false, timeoutNanos);
        return outcome.map(o -> state.withR0(o.result().code())).orElseGet(core::toCpuState);
    }

    /// `svcWaitSynchronizationN` — kernel real: `r0`=`nanoseconds` (32 bits baixos),
    /// `r1`=ponteiro para array de handles no guest, `r2`=quantidade de handles, `r3`=
    /// `waitAll` (bool), `r4`=`nanoseconds` (32 bits altos). Saída: `r0`=`Result`, `r1`=índice
    /// do objeto que acordou (só significativo com `waitAll=false` — armadilha da task: devolver
    /// só o resultado faz o libctru tomar decisões erradas silenciosamente).
    private CpuState handleWaitSynchronizationN(CpuState state) {
        int handlesAddress = state.r1();
        int handleCount = state.r2();
        boolean waitAll = state.r3() != 0;
        long timeoutNanos = combineS64(state.r0(), core.register(REGISTER_R4));

        List<Waitable> objects = new ArrayList<>(handleCount);
        for (int i = 0; i < handleCount; i++) {
            int handle = memory.read32(handlesAddress + i * BYTES_PER_S32);
            Optional<KernelObject> object = handles.resolve(handle);
            if (object.isEmpty() || !(object.get() instanceof Waitable waitable)) {
                return new CpuState(Result.INVALID_HANDLE.code(), 0, state.r2(), state.r3(),
                        state.sp(), state.lr(), state.pc(), state.cpsr());
            }
            objects.add(waitable);
        }

        Optional<Scheduler.WaitOutcome> outcome = scheduler.waitSynchronization(objects, waitAll, timeoutNanos);
        if (outcome.isPresent()) {
            Scheduler.WaitOutcome o = outcome.get();
            return new CpuState(o.result().code(), o.signaledIndex(), state.r2(), state.r3(),
                    state.sp(), state.lr(), state.pc(), state.cpsr());
        }
        return core.toCpuState();
    }

    // ── IPC (G2 PR2 — "não inclui" da task: para no primeiro svcSendSyncRequest real) ───────

    /// `svcConnectToPort` — kernel real: `r1`=ponteiro para o nome da porta (`r0` de entrada
    /// ignorado). Saída: `r0`=`Result`, `r1`=handle de sessão nova.
    private CpuState handleConnectToPort(CpuState state) {
        String portName = readCString(state.r1(), MAX_PORT_NAME_LENGTH);
        int handle = handles.create(new SessionObject(portName));
        return new CpuState(Result.SUCCESS.code(), handle, state.r2(), state.r3(),
                state.sp(), state.lr(), state.pc(), state.cpsr());
    }

    /// `svcSendSyncRequest` — kernel real: `r0`=handle de sessão. **Não implementada de
    /// verdade** (RFC/task "não inclui": serviços são a G3) — loga o cabeçalho de comando IPC
    /// (lido de TLS+{@link MemoryMap#TLS_COMMAND_BUFFER_OFFSET} da thread CORRENTE, RFC §3) e
    /// lança, mesmo padrão de {@link #defaultUnsupported} (grava sucesso em `r0` antes, ver
    /// Javadoc daquele método).
    private UnsupportedSvcException handleSendSyncRequest(CpuState state) {
        Optional<KernelObject> object = handles.resolve(state.r0());
        String sessionName = object.map(KernelObject::debugName)
                .orElse("handle desconhecida 0x" + Integer.toHexString(state.r0()));
        int tls = scheduler.current().tlsAddress();
        int header = memory.read32(tls + MemoryMap.TLS_COMMAND_BUFFER_OFFSET);
        int commandId = header >>> IPC_COMMAND_ID_SHIFT;
        int normalParams = (header >>> IPC_NORMAL_PARAMS_SHIFT) & IPC_PARAM_COUNT_MASK;
        int translateParams = header & IPC_PARAM_COUNT_MASK;
        traceLog.println("[ipc] svcSendSyncRequest sessao=%s cmd=0x%04X normais=%d traduzidos=%d"
                .formatted(sessionName, commandId, normalParams, translateParams));
        core.setRegister(REGISTER_R0, RESULT_SUCCESS_PLACEHOLDER);
        return new UnsupportedSvcException(
                new SvcCall(SVC_SEND_SYNC_REQUEST, HorizonSvcNames.nameOf(SVC_SEND_SYNC_REQUEST), state.r0(), 0, state.pc()));
    }

    private String readCString(int address, int maxLength) {
        byte[] bytes = new byte[maxLength];
        int length = 0;
        while (length < maxLength) {
            byte b = (byte) memory.read8(address + length);
            if (b == 0) {
                break;
            }
            bytes[length++] = b;
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    /// `svcCloseHandle` — kernel real: `r0`=handle. Saída: `r0`=`Result`.
    private CpuState handleCloseHandle(CpuState state) {
        Result result = handles.close(state.r0());
        return state.withR0(result.code());
    }

    /// `svcDuplicateHandle` — kernel real: `r1`=handle original (`r0` de entrada é só o
    /// ponteiro de saída do wrapper C). Saída: `r0`=`Result`, `r1`=handle nova.
    private CpuState handleDuplicateHandle(CpuState state) {
        HandleTable.DuplicateResult duplicate = handles.duplicate(state.r1());
        return new CpuState(duplicate.result().code(), duplicate.handle(), state.r2(), state.r3(),
                state.sp(), state.lr(), state.pc(), state.cpsr());
    }

    /// `svcGetSystemTick` — sem `Result` (a assinatura C devolve o valor direto, `s64`). Saída:
    /// `r0`=32 bits baixos, `r1`=32 bits altos de {@link ArmCore#cycles()} — NUNCA
    /// `System.nanoTime()` (quebraria determinismo/comparabilidade JIT×interpretado, ver
    /// Javadoc da task G2).
    private CpuState handleGetSystemTick(CpuState state) {
        long ticks = core.cycles();
        int low = (int) (ticks >>> LONG_LOW_WORD_SHIFT);
        int high = (int) (ticks >>> LONG_HIGH_WORD_SHIFT);
        return new CpuState(low, high, state.r2(), state.r3(), state.sp(), state.lr(), state.pc(), state.cpsr());
    }

    /// `svcGetProcessId` — kernel real: `r1`=handle do processo. Saída: `r0`=`Result`, `r1`=
    /// `processId`.
    private CpuState handleGetProcessId(CpuState state) {
        Optional<KernelObject> object = handles.resolve(state.r1());
        if (object.isPresent() && object.get() instanceof ProcessObject process) {
            return new CpuState(Result.SUCCESS.code(), process.processId(), state.r2(), state.r3(),
                    state.sp(), state.lr(), state.pc(), state.cpsr());
        }
        return new CpuState(Result.INVALID_HANDLE.code(), state.r1(), state.r2(), state.r3(),
                state.sp(), state.lr(), state.pc(), state.cpsr());
    }

    /// `svcGetThreadId` — kernel real: `r1`=handle da thread. Saída: `r0`=`Result`, `r1`=
    /// `threadId`.
    private CpuState handleGetThreadId(CpuState state) {
        Optional<KernelObject> object = handles.resolve(state.r1());
        if (object.isPresent() && object.get() instanceof ThreadObject thread) {
            return new CpuState(Result.SUCCESS.code(), thread.threadId(), state.r2(), state.r3(),
                    state.sp(), state.lr(), state.pc(), state.cpsr());
        }
        return new CpuState(Result.INVALID_HANDLE.code(), state.r1(), state.r2(), state.r3(),
                state.sp(), state.lr(), state.pc(), state.cpsr());
    }

    /// `svcGetResourceLimit` — kernel real: `r1`=handle do processo. Saída: `r0`=`Result`,
    /// `r1`=handle nova de {@link ResourceLimitObject} (valores plausíveis fixos, ver
    /// {@link ResourceLimitValues}).
    private CpuState handleGetResourceLimit(CpuState state) {
        Optional<KernelObject> object = handles.resolve(state.r1());
        if (object.isEmpty() || !(object.get() instanceof ProcessObject process)) {
            return new CpuState(Result.INVALID_HANDLE.code(), state.r1(), state.r2(), state.r3(),
                    state.sp(), state.lr(), state.pc(), state.cpsr());
        }
        int resourceLimitHandle = handles.create(new ResourceLimitObject(process.processId()));
        return new CpuState(Result.SUCCESS.code(), resourceLimitHandle, state.r2(), state.r3(),
                state.sp(), state.lr(), state.pc(), state.cpsr());
    }

    /// `svcGetResourceLimitLimitValues` — mesma convenção de registrador de
    /// {@link #handleGetResourceLimitCurrentValues} (`r0`=endereço de saída no guest, `r1`=
    /// handle do `ResourceLimitObject`, `r2`=endereço do array `LimitableResource` no guest,
    /// `r3`=quantidade de entradas) — a assinatura C (`svcGetResourceLimitLimitValues(s64*,
    /// Handle, LimitableResource*, s32)`) é idêntica em forma à de `CurrentValues`, só troca
    /// "uso atual" por "teto configurado". Ver Javadoc de {@link #SVC_GET_RESOURCE_LIMIT_LIMIT_VALUES}
    /// para o porquê desta SVC (fora da lista original da task) ser necessária.
    private CpuState handleGetResourceLimitLimitValues(CpuState state) {
        int valuesOutAddress = state.r0();
        int resourceLimitHandle = state.r1();
        int namesAddress = state.r2();
        int nameCount = state.r3();

        Optional<KernelObject> object = handles.resolve(resourceLimitHandle);
        if (object.isEmpty() || !(object.get() instanceof ResourceLimitObject)) {
            return state.withR0(Result.INVALID_HANDLE.code());
        }
        for (int i = 0; i < nameCount; i++) {
            int resource = memory.read32(namesAddress + i * BYTES_PER_S32);
            long value = ResourceLimitValues.limitValueOf(resource);
            int valueAddress = valuesOutAddress + i * BYTES_PER_S64;
            memory.write32(valueAddress, (int) (value >>> LONG_LOW_WORD_SHIFT));
            memory.write32(valueAddress + BYTES_PER_S32, (int) (value >>> LONG_HIGH_WORD_SHIFT));
        }
        return state.withR0(Result.SUCCESS.code());
    }

    /// `svcGetResourceLimitCurrentValues` — kernel real (sem wrapper de registradores, a
    /// assinatura C já é a convenção do kernel): `r0`=endereço de saída no guest (array de
    /// `s64`), `r1`=handle do `ResourceLimitObject`, `r2`=endereço no guest de um array de `s32`
    /// (`LimitableResource`), `r3`=quantidade de entradas. Escreve `nameCount` valores de 8
    /// bytes direto na memória do guest (não cabe em registrador) — saída: só `r0`=`Result`.
    private CpuState handleGetResourceLimitCurrentValues(CpuState state) {
        int valuesOutAddress = state.r0();
        int resourceLimitHandle = state.r1();
        int namesAddress = state.r2();
        int nameCount = state.r3();

        Optional<KernelObject> object = handles.resolve(resourceLimitHandle);
        if (object.isEmpty() || !(object.get() instanceof ResourceLimitObject)) {
            return state.withR0(Result.INVALID_HANDLE.code());
        }
        for (int i = 0; i < nameCount; i++) {
            int resource = memory.read32(namesAddress + i * BYTES_PER_S32);
            long value = ResourceLimitValues.currentValueOf(resource);
            int valueAddress = valuesOutAddress + i * BYTES_PER_S64;
            memory.write32(valueAddress, (int) (value >>> LONG_LOW_WORD_SHIFT));
            memory.write32(valueAddress + BYTES_PER_S32, (int) (value >>> LONG_HIGH_WORD_SHIFT));
        }
        return state.withR0(Result.SUCCESS.code());
    }

    /// `svcBreak` — kernel real: `r0`=`UserBreakType` (ver {@link UserBreakReason}). Não há
    /// `Result` de retorno: o guest não continua depois desta SVC nesta HLE (spec: "encerra com
    /// diagnóstico").
    private KernelHaltException handleBreak(CpuState state) {
        String reasonName = UserBreakReason.nameOf(state.r0());
        return new KernelHaltException("svcBreak: o guest chamou svcBreak (motivo=" + reasonName + ")",
                KernelHaltException.Reason.GUEST_BREAK);
    }

    /// `svcOutputDebugString` — kernel real: `r0`=endereço da string no guest, `r1`=tamanho em
    /// bytes. Imprime no `stdout` do host (`traceLog`, ver construtor) — é o que fecha o
    /// critério de aceite M2 desta sessão. Saída: `r0`=`Result` (sempre sucesso).
    private CpuState handleOutputDebugString(CpuState state) {
        int address = state.r0();
        int length = state.r1();
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) memory.read8(address + i);
        }
        traceLog.println(new String(bytes, StandardCharsets.UTF_8));
        return state.withR0(Result.SUCCESS.code());
    }

    // Ver Javadoc da classe: relê a instrução crua em vez de confiar no imediato já
    // pré-deslocado (convenção GBA/NDS) que o decoder compartilhado entrega.
    private int realSvcNumber(CpuState state) {
        boolean thumb = (state.cpsr() & CpsrRegister.THUMB_FLAG) != 0;
        if (thumb) {
            int instructionAddress = state.pc() - THUMB_INSTRUCTION_SIZE;
            return memory.read16(instructionAddress) & HORIZON_SVC_NUMBER_MASK;
        }
        int instructionAddress = state.pc() - ARM_INSTRUCTION_SIZE;
        return memory.read32(instructionAddress) & HORIZON_SVC_NUMBER_MASK;
    }

    /// Até as últimas {@code traceCapacity} chamadas observadas, da mais antiga para a mais
    /// recente — usado pelo `Main` para imprimir o trace ao sair.
    public List<SvcCall> recentCalls() {
        return List.copyOf(recentCalls);
    }
}
