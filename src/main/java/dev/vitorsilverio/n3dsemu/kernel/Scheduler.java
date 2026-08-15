package dev.vitorsilverio.n3dsemu.kernel;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.n3dsemu.core.N3dsCp15;
import dev.vitorsilverio.n3dsemu.memory.MemoryMap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// Escalonador cooperativo do kernel Horizon HLE (RFC-N3DSEMU G2 PR2, "Escalonador" da task):
/// um único {@link ArmCore} compartilhado por todas as threads do guest, uma de cada vez — a
/// troca de "qual thread está carregada" acontece só em pontos de suspensão explícitos
/// (`svcSleepThread`, `svcWaitSynchronization*` bloqueante, `svcArbitrateAddress` bloqueante,
/// `svcExitThread`, liberação de mutex/semáforo/evento com alguém esperando), **nunca por
/// tempo** — não há preempção (justificativa na task: um escalonador preemptivo exigiria
/// salvar/restaurar contexto em pontos arbitrários do JIT).
///
/// **Contexto de troca** = {@link ArmCore#saveState}/{@link ArmCore#loadState} completo (todos
/// os bancos de registrador, CPSR, VFP) — não o {@code CpuState} de 8 campos que
/// `SwiDispatcher` usa para SVCs comuns, que não cobre r4-r12 nem os bancos privilegiados
/// (reuso pedido explicitamente pela task; ver Javadoc de {@link #switchTo}).
///
/// **Como uma troca de contexto DENTRO de um handler de SVC devolve o resultado certo**: um
/// handler de `SvcTable` que bloqueia (ex.: `svcSleepThread`) chama um método deste escalonador
/// que, internamente, já troca o {@link ArmCore} para a PRÓXIMA thread antes de retornar. A
/// convenção (ver `IrSystemExecutor#executeSwi` do arm-jitter: `core.apply(dispatcher.dispatch(
/// ..., core.toCpuState()))`) exige que o `CpuState` devolvido pelo handler reflita o que deve
/// ficar nos registradores DEPOIS do despacho — então, no caminho bloqueante, `SvcTable` deve
/// devolver `core.toCpuState()` (o estado da PRÓXIMA thread, já carregado), nunca algo derivado
/// da thread que estava executando a SVC. Quando a thread ORIGINAL for escolhida de novo, mais
/// tarde, {@link #switchTo} grava o resultado da espera dela nos registradores certos via
/// {@link ThreadObject#applyResumeRegisters} — ver esse Javadoc para o porquê de isso não poder
/// viver no contexto salvo.
public final class Scheduler {
    private static final int REGISTER_SP = 13;
    private static final int REGISTER_R0 = 0;

    /// `SYSCLOCK_ARM11` (3dbrew: `Configuration_Memory`) — clock do contador de ciclos do
    /// ARM11 usado por `svcGetSystemTick`/para converter nanossegundos de `svcSleepThread`/
    /// `svcWaitSynchronization*` em ciclos.
    private static final long ARM11_CLOCK_HZ = 268_111_856L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final BigInteger ARM11_CLOCK_HZ_BIG = BigInteger.valueOf(ARM11_CLOCK_HZ);
    private static final BigInteger NANOS_PER_SECOND_BIG = BigInteger.valueOf(NANOS_PER_SECOND);

    private final List<ThreadObject> threads = new ArrayList<>();
    private int nextThreadId = 2; // 1 é sempre a thread principal (ver ThreadObject#mainThread)
    private int nextTlsAddress;

    private ArmCore core;
    private N3dsCp15 cp15;
    private ThreadObject current;

    /// Liga o escalonador ao {@link ArmCore}/{@link N3dsCp15} reais (dependência circular do
    /// construtor — mesmo padrão de `SvcTable#attach`) e registra `mainThread` (já construída
    /// via {@link ThreadObject#mainThread}, com TLS fixo em {@link MemoryMap#TLS_BASE}) como a
    /// thread corrente — ela já está em execução desde antes da primeira `svc` (RFC-N3DSEMU
    /// M1), sem passar pelo cold-start de {@link #createThread}.
    public void attach(ArmCore core, N3dsCp15 cp15, ThreadObject mainThread) {
        this.core = Objects.requireNonNull(core, "core");
        this.cp15 = Objects.requireNonNull(cp15, "cp15");
        this.nextTlsAddress = mainThread.tlsAddress() + MemoryMap.TLS_SLOT_SIZE;
        threads.add(mainThread);
        current = mainThread;
        cp15.setThreadLocalStorage(mainThread.tlsAddress());
    }

    public ThreadObject current() {
        return current;
    }

    /// Ciclos atuais do relógio virtual — SEMPRE derivado de {@link ArmCore#cycles()}, nunca do
    /// relógio de parede (armadilha da task: quebraria determinismo/comparabilidade
    /// JIT×interpretado).
    public long currentTick() {
        return core.cycles();
    }

    /// Converte nanossegundos (convenção Horizon: negativo = espera infinita, ver 3dbrew) em
    /// ciclos do relógio virtual. `BigInteger` evita overflow de `long` na multiplicação por
    /// `ARM11_CLOCK_HZ` para timeouts grandes — não é caminho quente (só em `sleep`/
    /// `waitSynchronization`/`blockOnArbiter`, nunca por instrução).
    static long nanosToTicks(long nanoseconds) {
        if (nanoseconds < 0) {
            return -1L;
        }
        return BigInteger.valueOf(nanoseconds).multiply(ARM11_CLOCK_HZ_BIG)
                .divide(NANOS_PER_SECOND_BIG).longValueExact();
    }

    // ── criação de threads ──────────────────────────────────────────────────────────────────

    /// `svcCreateThread`: resultado + a thread nova (só significativa em sucesso).
    public record CreateThreadResult(Result result, ThreadObject thread) {
        static CreateThreadResult failure(Result result) {
            return new CreateThreadResult(result, null);
        }
    }

    /// Registra uma thread nova, {@link ThreadObject.State#READY} mas sem cold-start (só
    /// acontece na primeira vez que {@link #switchTo} a escolhe). **Não força troca de
    /// contexto** — a thread chamadora continua executando (mesma semântica do Horizon real: a
    /// nova thread só realmente roda quando a chamadora ceder a CPU num ponto de suspensão).
    public CreateThreadResult createThread(int priority, int entryPoint, int arg, int stackTop) {
        if (nextTlsAddress >= MemoryMap.TLS_BASE + MemoryMap.TLS_REGION_SIZE) {
            return CreateThreadResult.failure(Result.OUT_OF_MEMORY);
        }
        int tls = nextTlsAddress;
        nextTlsAddress += MemoryMap.TLS_SLOT_SIZE;
        ThreadObject thread = ThreadObject.coldStart(nextThreadId++, priority, entryPoint, arg, stackTop, tls);
        threads.add(thread);
        return new CreateThreadResult(Result.SUCCESS, thread);
    }

    // ── troca de contexto ───────────────────────────────────────────────────────────────────

    /// Salva o contexto de {@link #current} (se for uma thread DIFERENTE de `next` — trocar
    /// "para si mesma", caso de `svcSleepThread(0)` sem nenhuma outra thread pronta, é um
    /// no-op: evita tanto o save/restore desnecessário quanto um `loadState(null)` na thread
    /// principal, cujo {@code savedContext} continua `null` até a primeira vez que ELA for
    /// trocada para fora de verdade) e carrega o de `next`, atualizando `CP15.TPIDRURO` (RFC
    /// §3: TLS por thread).
    private void switchTo(ThreadObject next) {
        if (current == next) {
            next.setState(ThreadObject.State.RUNNING);
            return;
        }
        if (current != null) {
            current.setSavedContext(captureContext());
        }
        if (next.started()) {
            restoreContext(next.savedContext());
        } else {
            core.configureExecutionState(next.entryPoint(), CpuMode.USER, InstructionSet.ARM, false, false);
            core.setRegister(REGISTER_SP, next.initialStackTop());
            core.setRegister(REGISTER_R0, next.initialArg());
            next.markStarted();
        }
        next.applyResumeRegisters(core);
        cp15.setThreadLocalStorage(next.tlsAddress());
        current = next;
        next.setState(ThreadObject.State.RUNNING);
    }

    private byte[] captureContext() {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            core.saveState(new DataOutputStream(buffer));
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void restoreContext(byte[] context) {
        try {
            core.loadState(new DataInputStream(new ByteArrayInputStream(context)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── decisão de escalonamento ────────────────────────────────────────────────────────────

    /// Reavalia toda thread bloqueada (chamado antes de escolher a próxima a rodar): acorda
    /// quem já pode (condição satisfeita ou timeout vencido). Também chamado depois de
    /// qualquer operação que possa ter mudado o estado de um {@link Waitable} (liberar mutex/
    /// semáforo, sinalizar evento) — a liberação em si NÃO cede a CPU (mesma semântica do
    /// Horizon real: quem libera continua rodando), só marca os esperadores como
    /// {@link ThreadObject.State#READY} para a PRÓXIMA troca de contexto os escolher.
    private void reevaluateBlockedThreads() {
        long now = currentTick();
        for (ThreadObject t : threads) {
            switch (t.state()) {
                case SLEEPING -> {
                    if (t.hasTimeout() && now >= t.wakeTick()) {
                        t.wakeReady();
                    }
                }
                case WAITING_SYNC -> tryWakeWaitingSync(t, now);
                case WAITING_ARBITER -> {
                    if (t.hasTimeout() && now >= t.wakeTick()) {
                        t.waitingArbiter().removeWaiter(t.waitingArbiterAddress(), t);
                        t.wakeWithArbiterResult(Result.TIMEOUT);
                    }
                }
                default -> {
                }
            }
        }
    }

    private void tryWakeWaitingSync(ThreadObject t, long now) {
        List<Waitable> objects = t.waitObjects();
        if (t.waitAll()) {
            if (objects.stream().allMatch(w -> w.isAvailableFor(t))) {
                objects.forEach(w -> w.acquire(t));
                t.wakeWithSyncResult(Result.SUCCESS, 0);
                return;
            }
        } else {
            for (int i = 0; i < objects.size(); i++) {
                Waitable candidate = objects.get(i);
                if (candidate.isAvailableFor(t)) {
                    candidate.acquire(t);
                    t.wakeWithSyncResult(Result.SUCCESS, i);
                    return;
                }
            }
        }
        if (t.hasTimeout() && now >= t.wakeTick()) {
            t.wakeWithSyncResult(Result.TIMEOUT, -1);
        }
    }

    private ThreadObject selectReadyThread() {
        ThreadObject best = null;
        for (ThreadObject t : threads) {
            if (t.state() != ThreadObject.State.READY) {
                continue;
            }
            // Menor número = maior prioridade (Horizon real); empate = ordem de criação (FIFO,
            // `threads` só cresce por `append`, nunca reordena).
            if (best == null || t.priority() < best.priority()) {
                best = t;
            }
        }
        return best;
    }

    private long earliestTimeoutTick() {
        long min = Long.MAX_VALUE;
        for (ThreadObject t : threads) {
            boolean waitingWithTimeout = (t.state() == ThreadObject.State.SLEEPING
                    || t.state() == ThreadObject.State.WAITING_SYNC
                    || t.state() == ThreadObject.State.WAITING_ARBITER) && t.hasTimeout();
            if (waitingWithTimeout) {
                min = Math.min(min, t.wakeTick());
            }
        }
        return min;
    }

    /// Escolhe a próxima thread pronta e troca o {@link ArmCore} para ela. Se nenhuma está
    /// pronta mas existe alguma dormindo/esperando com timeout, adianta o relógio virtual
    /// (`core.addCycles`, RFC/task: nunca o relógio de parede) até o timeout mais próximo — sem
    /// isso, um homebrew single-thread que só dorme nunca acordaria (nenhuma instrução real é
    /// executada enquanto o escalonador decide). Se não há NENHUM candidato (nem pronto, nem
    /// com timeout), é deadlock genuíno de verdade — falta de preempção sendo exercitada além
    /// do que a task cobre (armadilha documentada: "é um achado a reportar, não a consertar
    /// aqui").
    private void pickNextAndSwitch() {
        reevaluateBlockedThreads();
        ThreadObject next = selectReadyThread();
        if (next == null) {
            long fastForwardTo = earliestTimeoutTick();
            if (fastForwardTo == Long.MAX_VALUE) {
                throw new IllegalStateException(
                        "kernel HLE: nenhuma thread pronta e nenhum timeout pendente (deadlock cooperativo — "
                                + "ver Javadoc de Scheduler#pickNextAndSwitch)");
            }
            long delta = fastForwardTo - currentTick();
            if (delta > 0) {
                core.addCycles(delta);
            }
            reevaluateBlockedThreads();
            next = selectReadyThread();
        }
        switchTo(next);
    }

    // ── operações que bloqueiam (chamadas pela SvcTable) ───────────────────────────────────

    /// `svcSleepThread`. `nanoseconds == 0` é um YIELD real (3dbrew): cede a CPU sem dormir de
    /// verdade — a própria thread volta a ficar elegível imediatamente.
    public void sleep(long nanoseconds) {
        if (nanoseconds == 0) {
            current.setState(ThreadObject.State.READY);
            pickNextAndSwitch();
            return;
        }
        long ticks = Math.max(0, nanosToTicks(nanoseconds));
        current.blockSleeping(currentTick() + ticks);
        pickNextAndSwitch();
    }

    /// `svcExitThread`: a thread corrente nunca mais roda.
    public void exitThread() {
        current.terminate();
        pickNextAndSwitch();
    }

    /// `svcWaitSynchronization1`/`N`: resultado + índice do objeto sinalizado (índice só
    /// significativo em `waitAll=false`).
    public record WaitOutcome(Result result, int signaledIndex) {
    }

    /// @param timeoutNanos negativo = espera infinita (convenção Horizon)
    /// @return o resultado, se resolvido IMEDIATAMENTE (sem troca de contexto — a thread
    ///         chamadora continua sendo {@link #current}); vazio se bloqueou e o
    ///         {@link ArmCore} já foi trocado para outra thread (o chamador, `SvcTable`, deve
    ///         devolver {@code core.toCpuState()} nesse caso — ver Javadoc da classe)
    public Optional<WaitOutcome> waitSynchronization(List<Waitable> objects, boolean waitAll, long timeoutNanos) {
        ThreadObject caller = current;
        if (waitAll) {
            if (objects.stream().allMatch(w -> w.isAvailableFor(caller))) {
                objects.forEach(w -> w.acquire(caller));
                return Optional.of(new WaitOutcome(Result.SUCCESS, 0));
            }
        } else {
            for (int i = 0; i < objects.size(); i++) {
                if (objects.get(i).isAvailableFor(caller)) {
                    objects.get(i).acquire(caller);
                    return Optional.of(new WaitOutcome(Result.SUCCESS, i));
                }
            }
        }
        if (timeoutNanos == 0) {
            return Optional.of(new WaitOutcome(Result.TIMEOUT, -1));
        }
        boolean hasTimeout = timeoutNanos > 0;
        long wakeTick = hasTimeout ? currentTick() + nanosToTicks(timeoutNanos) : 0;
        caller.blockWaitingSync(objects, waitAll, wakeTick, hasTimeout);
        pickNextAndSwitch();
        return Optional.empty();
    }

    /// `svcArbitrateAddress` (tipos `WAIT_IF_LESS_THAN*`/`DECREMENT_AND_WAIT_IF_LESS_THAN*`,
    /// já decidido pelo chamador que a condição de espera vale — ver `SvcTable`, que lê/
    /// decrementa a memória do guest antes de chamar isto). Igual a
    /// {@link #waitSynchronization}: vazio = bloqueou, `core.toCpuState()` é a resposta certa.
    public Optional<Result> blockOnArbiter(AddressArbiterObject arbiter, int address, long timeoutNanos) {
        if (timeoutNanos == 0) {
            return Optional.of(Result.TIMEOUT);
        }
        boolean hasTimeout = timeoutNanos > 0;
        long wakeTick = hasTimeout ? currentTick() + nanosToTicks(timeoutNanos) : 0;
        current.blockWaitingArbiter(arbiter, address, wakeTick, hasTimeout);
        arbiter.addWaiter(address, current);
        pickNextAndSwitch();
        return Optional.empty();
    }

    /// `svcArbitrateAddress` tipo `SIGNAL`: acorda até `maxCount` threads esperando em
    /// `address` (FIFO). Não cede a CPU (quem sinaliza continua rodando, ver Javadoc de
    /// {@link #reevaluateBlockedThreads}).
    public void signalArbiter(AddressArbiterObject arbiter, int address, int maxCount) {
        List<ThreadObject> waiters = arbiter.waitersAt(address);
        Iterator<ThreadObject> iterator = waiters.iterator();
        int woken = 0;
        while (iterator.hasNext() && woken < maxCount) {
            ThreadObject waiter = iterator.next();
            iterator.remove();
            waiter.wakeWithArbiterResult(Result.SUCCESS);
            woken++;
        }
    }
}
