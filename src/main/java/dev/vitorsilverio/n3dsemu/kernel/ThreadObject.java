package dev.vitorsilverio.n3dsemu.kernel;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.n3dsemu.memory.MemoryMap;

import java.util.List;

/// Uma thread do guest (RFC-N3DSEMU G2 PR2). Mutável (ao contrário da G2 PR1, onde era um
/// `record` com um único `threadId` fixo) porque prioridade, estado de escalonamento e o
/// contexto de registradores salvo mudam durante a vida da thread — ver {@link Scheduler}, dono
/// de toda transição de estado (os setters aqui são package-private de propósito: só o
/// escalonador decide quando uma thread muda de estado).
///
/// Alcançável pelo handle pseudo-reservado {@link HandleTable#CURRENT_THREAD_HANDLE} (a
/// corrente) ou por uma handle real devolvida por `svcCreateThread`/`svcGetThreadId`+
/// `svcOpenThread` (não implementado nesta task).
public final class ThreadObject implements Waitable {
    private static final int REGISTER_R0 = 0;
    private static final int REGISTER_R1 = 1;

    public enum State {
        READY, RUNNING, SLEEPING, WAITING_SYNC, WAITING_ARBITER, DEAD
    }

    /// Qual registrador de retorno {@link #applyResumeRegisters} deve preencher ao acordar —
    /// o contexto salvo (capturado ANTES do resultado da espera ser conhecido, ver Javadoc de
    /// {@link Scheduler#switchTo}) não pode conter esse valor.
    private enum ResumeKind {
        NONE, SYNC_RESULT, ARBITER_RESULT
    }

    private final int threadId;
    private int priority;
    private State state;
    private byte[] savedContext;
    private boolean started;

    private final int entryPoint;
    private final int initialArg;
    private final int initialStackTop;
    private final int tlsAddress;

    private boolean hasTimeout;
    private long wakeTick;

    private List<Waitable> waitObjects;
    private boolean waitAll;

    private AddressArbiterObject waitingArbiter;
    private int waitingArbiterAddress;

    private ResumeKind resumeKind = ResumeKind.NONE;
    private Result resumeResult;
    private int resumeIndex;

    private ThreadObject(int threadId, int priority, State initialState, boolean started,
                          int entryPoint, int initialArg, int initialStackTop, int tlsAddress) {
        this.threadId = threadId;
        this.priority = priority;
        this.state = initialState;
        this.started = started;
        this.entryPoint = entryPoint;
        this.initialArg = initialArg;
        this.initialStackTop = initialStackTop;
        this.tlsAddress = tlsAddress;
    }

    /// A thread principal do processo (RFC-N3DSEMU M1): já posicionada pelo `N3dsMachine` no
    /// entry point do `.3dsx` ANTES de o {@link Scheduler} existir (dependência circular do
    /// construtor, mesmo padrão de {@code SvcTable#attach}) — por isso o TLS é fixado em
    /// {@link MemoryMap#TLS_BASE} (o primeiro slot, sempre reservado a ela;
    /// {@link Scheduler#attach} continua a numeração a partir daí) em vez de alocado por um
    /// escalonador que ainda não foi construído.
    public static ThreadObject mainThread(int threadId, int priority) {
        return new ThreadObject(threadId, priority, State.RUNNING, true, 0, 0, 0, MemoryMap.TLS_BASE);
    }

    /// Uma thread nova (`svcCreateThread`): pronta mas não iniciada — o cold-start (posicionar
    /// o `ArmCore` no `entryPoint`) só acontece na primeira vez que o {@link Scheduler} a
    /// escolhe (ver {@link Scheduler#switchTo}), nunca na criação.
    static ThreadObject coldStart(int threadId, int priority, int entryPoint, int arg, int stackTop, int tlsAddress) {
        return new ThreadObject(threadId, priority, State.READY, false, entryPoint, arg, stackTop, tlsAddress);
    }

    public int threadId() {
        return threadId;
    }

    public int priority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    State state() {
        return state;
    }

    void setState(State state) {
        this.state = state;
    }

    boolean started() {
        return started;
    }

    void markStarted() {
        started = true;
    }

    int entryPoint() {
        return entryPoint;
    }

    int initialArg() {
        return initialArg;
    }

    int initialStackTop() {
        return initialStackTop;
    }

    int tlsAddress() {
        return tlsAddress;
    }

    byte[] savedContext() {
        return savedContext;
    }

    void setSavedContext(byte[] context) {
        savedContext = context;
    }

    boolean hasTimeout() {
        return hasTimeout;
    }

    long wakeTick() {
        return wakeTick;
    }

    boolean waitAll() {
        return waitAll;
    }

    List<Waitable> waitObjects() {
        return waitObjects;
    }

    AddressArbiterObject waitingArbiter() {
        return waitingArbiter;
    }

    int waitingArbiterAddress() {
        return waitingArbiterAddress;
    }

    void blockSleeping(long wakeTick) {
        this.state = State.SLEEPING;
        this.hasTimeout = true;
        this.wakeTick = wakeTick;
    }

    void blockWaitingSync(List<Waitable> waitObjects, boolean waitAll, long wakeTick, boolean hasTimeout) {
        this.state = State.WAITING_SYNC;
        this.waitObjects = waitObjects;
        this.waitAll = waitAll;
        this.hasTimeout = hasTimeout;
        this.wakeTick = wakeTick;
    }

    void blockWaitingArbiter(AddressArbiterObject arbiter, int address, long wakeTick, boolean hasTimeout) {
        this.state = State.WAITING_ARBITER;
        this.waitingArbiter = arbiter;
        this.waitingArbiterAddress = address;
        this.hasTimeout = hasTimeout;
        this.wakeTick = wakeTick;
    }

    void wakeReady() {
        this.state = State.READY;
        this.hasTimeout = false;
        this.waitObjects = null;
        this.waitingArbiter = null;
    }

    void wakeWithSyncResult(Result result, int index) {
        resumeKind = ResumeKind.SYNC_RESULT;
        resumeResult = result;
        resumeIndex = index;
        wakeReady();
    }

    void wakeWithArbiterResult(Result result) {
        resumeKind = ResumeKind.ARBITER_RESULT;
        resumeResult = result;
        wakeReady();
    }

    void terminate() {
        this.state = State.DEAD;
    }

    /// Chamado pelo {@link Scheduler} logo depois de restaurar (ou dar cold-start em) o
    /// contexto desta thread — só tem efeito se ela estava acordando de um
    /// `WaitSynchronization`/`ArbitrateAddress` bloqueante: grava o resultado da espera nos
    /// registradores de retorno reais, algo que o contexto salvo (capturado antes de o
    /// resultado ser conhecido) não podia conter.
    void applyResumeRegisters(ArmCore core) {
        switch (resumeKind) {
            case SYNC_RESULT -> {
                core.setRegister(REGISTER_R0, resumeResult.code());
                core.setRegister(REGISTER_R1, resumeIndex);
            }
            case ARBITER_RESULT -> core.setRegister(REGISTER_R0, resumeResult.code());
            case NONE -> {
            }
        }
        resumeKind = ResumeKind.NONE;
    }

    // ── Waitable: esperar uma HANDLE DE THREAD espera ela terminar (3dbrew) ─────────────────
    // Uma handle de thread morta fica permanentemente sinalizada — sem estado a consumir, igual
    // a um evento RESET_STICKY (qualquer número de esperadores pode ser satisfeito).

    @Override
    public boolean isAvailableFor(ThreadObject waiter) {
        return state == State.DEAD;
    }

    @Override
    public void acquire(ThreadObject waiter) {
        // Ver comentário acima: nada a consumir.
    }

    @Override
    public String debugName() {
        return "Thread#" + threadId;
    }
}
