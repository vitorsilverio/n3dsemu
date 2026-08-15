package dev.vitorsilverio.n3dsemu.kernel;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpsrRegister;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.swi.CpuState;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// Tabela de SVCs do kernel Horizon (RFC-N3DSEMU D2). A G1 (marco M1) deixou toda `svc`
/// interceptada pelo {@link SwiDispatcher} do arm-jitter (mesmo mecanismo que gbaemu/ndsemu
/// usam para a BIOS), traçada, e sempre lançando {@link UnsupportedSvcException}. Esta classe
/// (G2 PR1, marco M2 parcial) implementa de verdade o subconjunto de SVCs de memória/handles/
/// diagnóstico listado na task — threads, sincronização e IPC de verdade continuam caindo no
/// comportamento herdado da G1 (log + lança) até a G2 PR2.
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

    // Números das SVCs implementadas por esta task (G2 PR1) — ver tabela da spec
    // g2-kernel-hle-svc.md. Nomeadas para não repetir literais hex nos `case` do switch.
    private static final int SVC_CONTROL_MEMORY = 0x01;
    private static final int SVC_QUERY_MEMORY = 0x02;
    private static final int SVC_EXIT_PROCESS = 0x03;
    private static final int SVC_CLOSE_HANDLE = 0x23;
    private static final int SVC_DUPLICATE_HANDLE = 0x27;
    private static final int SVC_GET_SYSTEM_TICK = 0x28;
    private static final int SVC_GET_PROCESS_ID = 0x35;
    private static final int SVC_GET_THREAD_ID = 0x37;
    private static final int SVC_GET_RESOURCE_LIMIT = 0x38;
    private static final int SVC_GET_RESOURCE_LIMIT_CURRENT_VALUES = 0x3A;
    private static final int SVC_BREAK = 0x3C;
    private static final int SVC_OUTPUT_DEBUG_STRING = 0x3D;

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
    private ArmCore core;

    public SvcTable(AddressSpace memory, PrintStream traceLog, boolean traceEnabled,
                     HandleTable handles, MemoryManager memoryManager) {
        this(memory, traceLog, traceEnabled, DEFAULT_TRACE_CAPACITY, handles, memoryManager);
    }

    public SvcTable(AddressSpace memory, PrintStream traceLog, boolean traceEnabled, int traceCapacity,
                     HandleTable handles, MemoryManager memoryManager) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.traceLog = Objects.requireNonNull(traceLog, "traceLog");
        this.traceEnabled = traceEnabled;
        this.traceCapacity = traceCapacity;
        this.handles = Objects.requireNonNull(handles, "handles");
        this.memoryManager = Objects.requireNonNull(memoryManager, "memoryManager");
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
            case SVC_CLOSE_HANDLE -> handleCloseHandle(state);
            case SVC_DUPLICATE_HANDLE -> handleDuplicateHandle(state);
            case SVC_GET_SYSTEM_TICK -> handleGetSystemTick(state);
            case SVC_GET_PROCESS_ID -> handleGetProcessId(state);
            case SVC_GET_THREAD_ID -> handleGetThreadId(state);
            case SVC_GET_RESOURCE_LIMIT -> handleGetResourceLimit(state);
            case SVC_GET_RESOURCE_LIMIT_CURRENT_VALUES -> handleGetResourceLimitCurrentValues(state);
            case SVC_BREAK -> throw handleBreak(state);
            case SVC_OUTPUT_DEBUG_STRING -> handleOutputDebugString(state);
            default -> throw defaultUnsupported(call);
        };
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
    /// `threadId`. **Parcial (G2 PR1)**: a única `ThreadObject` existente é a principal fixa
    /// (ver {@link ThreadObject}) — `svcCreateThread` de verdade é G2 PR2.
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
