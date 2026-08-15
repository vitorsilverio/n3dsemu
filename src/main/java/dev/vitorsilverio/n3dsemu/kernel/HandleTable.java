package dev.vitorsilverio.n3dsemu.kernel;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// Tabela de handles de um processo (RFC-N3DSEMU G2): `int` → {@link KernelObject}.
///
/// Duas handles são **pseudo-reservadas** (3dbrew: Handle) e nunca ocupam uma entrada real da
/// tabela — {@link #CURRENT_PROCESS_HANDLE} e {@link #CURRENT_THREAD_HANDLE} resolvem sempre
/// para os mesmos objetos fixos passados ao construtor, sem passar por {@link #handles}.
/// **Decisão desta sessão** (não documentada explicitamente no 3dbrew): fechar ou duplicar uma
/// pseudo-handle devolve {@link Result#INVALID_HANDLE} — elas não são handles "reais" da tabela
/// do kernel, só atalhos aceitos por chamadas que recebem `Handle`.
///
/// Handle inválido **nunca** vaza uma `NullPointerException` para o chamador: toda consulta
/// passa por {@link #resolve}, que devolve `Optional.empty()`.
public final class HandleTable {
    /// 3dbrew: Handle — pseudo-handle que qualquer SVC que aceite `Handle` resolve para o
    /// processo do chamador, sem entrada na tabela real.
    public static final int CURRENT_PROCESS_HANDLE = 0xFFFF8000;
    /// 3dbrew: Handle — idem, para a thread do chamador.
    public static final int CURRENT_THREAD_HANDLE = 0xFFFF8001;

    /// Primeira handle alocável de verdade. `0` é reservado (nunca devolvido por
    /// {@link #create}), espelhando o Horizon real onde a handle `0` nunca é um objeto válido.
    private static final int FIRST_ALLOCATED_HANDLE = 1;

    private final Map<Integer, KernelObject> handles = new HashMap<>();
    private final ProcessObject currentProcess;
    private final ThreadObject currentThread;
    private int nextHandle = FIRST_ALLOCATED_HANDLE;

    public HandleTable(ProcessObject currentProcess, ThreadObject currentThread) {
        this.currentProcess = Objects.requireNonNull(currentProcess, "currentProcess");
        this.currentThread = Objects.requireNonNull(currentThread, "currentThread");
    }

    /// Cria uma handle nova para `object` e a devolve.
    public int create(KernelObject object) {
        Objects.requireNonNull(object, "object");
        int handle = nextHandle++;
        handles.put(handle, object);
        return handle;
    }

    /// Resolve uma handle (real ou pseudo-reservada) para o objeto que ela referencia, ou
    /// {@link Optional#empty()} se `handle` não existir — nunca lança.
    public Optional<KernelObject> resolve(int handle) {
        if (handle == CURRENT_PROCESS_HANDLE) {
            return Optional.of(currentProcess);
        }
        if (handle == CURRENT_THREAD_HANDLE) {
            return Optional.of(currentThread);
        }
        return Optional.ofNullable(handles.get(handle));
    }

    /// `svcCloseHandle`: libera `handle`. Pseudo-handles e handles desconhecidas devolvem
    /// {@link Result#INVALID_HANDLE} (ver Javadoc da classe).
    public Result close(int handle) {
        if (isPseudoHandle(handle)) {
            return Result.INVALID_HANDLE;
        }
        if (handles.remove(handle) == null) {
            return Result.INVALID_HANDLE;
        }
        return Result.SUCCESS;
    }

    /// `svcDuplicateHandle`: resultado de {@link #duplicate}.
    ///
    /// @param result resultado da operação
    /// @param handle a nova handle (só significativa quando {@code result.isSuccess()})
    public record DuplicateResult(Result result, int handle) {
        static DuplicateResult failure(Result result) {
            return new DuplicateResult(result, 0);
        }
    }

    /// `svcDuplicateHandle`: cria uma handle nova apontando para o MESMO objeto de
    /// `originalHandle` (pseudo-handles são resolvidas normalmente — duplicar
    /// {@link #CURRENT_THREAD_HANDLE} cria uma handle real para a thread atual).
    public DuplicateResult duplicate(int originalHandle) {
        Optional<KernelObject> object = resolve(originalHandle);
        if (object.isEmpty()) {
            return DuplicateResult.failure(Result.INVALID_HANDLE);
        }
        return new DuplicateResult(Result.SUCCESS, create(object.get()));
    }

    private static boolean isPseudoHandle(int handle) {
        return handle == CURRENT_PROCESS_HANDLE || handle == CURRENT_THREAD_HANDLE;
    }
}
