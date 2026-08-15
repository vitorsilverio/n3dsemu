package dev.vitorsilverio.n3dsemu.kernel;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandleTableTest {
    private static final int PROCESS_ID = 7;
    private static final int THREAD_ID = 3;
    private static final int THREAD_PRIORITY = 0x30;

    // ThreadObject é mutável (G2 PR2, sem equals por valor) — os testes guardam a MESMA
    // instância passada ao HandleTable e comparam por identidade, não reconstroem um "igual".
    private ThreadObject mainThread;

    private HandleTable newTable() {
        mainThread = ThreadObject.mainThread(THREAD_ID, THREAD_PRIORITY);
        return new HandleTable(new ProcessObject(PROCESS_ID), mainThread);
    }

    @Test
    void pseudoHandlesResolvemParaOProcessoEThreadCorrentes() {
        HandleTable handles = newTable();

        Optional<KernelObject> process = handles.resolve(HandleTable.CURRENT_PROCESS_HANDLE);
        Optional<KernelObject> thread = handles.resolve(HandleTable.CURRENT_THREAD_HANDLE);

        assertEquals(new ProcessObject(PROCESS_ID), process.orElseThrow());
        assertSame(mainThread, thread.orElseThrow());
    }

    @Test
    void handleDesconhecidaResolveVazioSemLancar() {
        HandleTable handles = newTable();
        assertTrue(handles.resolve(0x1234).isEmpty());
    }

    @Test
    void createDevolveHandlesDistintasEResolveis() {
        HandleTable handles = newTable();
        KernelObject object = new ResourceLimitObject(PROCESS_ID);

        int handle = handles.create(object);

        assertEquals(object, handles.resolve(handle).orElseThrow());
        assertNotEquals(0, handle);
    }

    @Test
    void closeLiberaUmaHandleRealEDevolveSucesso() {
        HandleTable handles = newTable();
        int handle = handles.create(new ResourceLimitObject(PROCESS_ID));

        Result result = handles.close(handle);

        assertTrue(result.isSuccess());
        assertTrue(handles.resolve(handle).isEmpty());
    }

    @Test
    void closeDeHandleDesconhecidaDevolveInvalidHandle() {
        HandleTable handles = newTable();
        assertEquals(Result.INVALID_HANDLE, handles.close(0x9999));
    }

    @Test
    void closeDePseudoHandleDevolveInvalidHandle() {
        HandleTable handles = newTable();
        assertEquals(Result.INVALID_HANDLE, handles.close(HandleTable.CURRENT_THREAD_HANDLE));
        assertEquals(Result.INVALID_HANDLE, handles.close(HandleTable.CURRENT_PROCESS_HANDLE));
    }

    @Test
    void duplicateDeHandleRealApontaParaOMesmoObjeto() {
        HandleTable handles = newTable();
        KernelObject object = new ResourceLimitObject(PROCESS_ID);
        int original = handles.create(object);

        HandleTable.DuplicateResult duplicate = handles.duplicate(original);

        assertTrue(duplicate.result().isSuccess());
        assertNotEquals(original, duplicate.handle());
        assertEquals(object, handles.resolve(duplicate.handle()).orElseThrow());
    }

    @Test
    void duplicateDePseudoHandleCriaUmaHandleRealParaOMesmoObjeto() {
        HandleTable handles = newTable();

        HandleTable.DuplicateResult duplicate = handles.duplicate(HandleTable.CURRENT_THREAD_HANDLE);

        assertTrue(duplicate.result().isSuccess());
        assertSame(mainThread, handles.resolve(duplicate.handle()).orElseThrow());
        // A cópia é uma handle real: pode ser fechada, diferente do pseudo-handle original.
        assertTrue(handles.close(duplicate.handle()).isSuccess());
    }

    @Test
    void duplicateDeHandleDesconhecidaDevolveInvalidHandle() {
        HandleTable handles = newTable();
        HandleTable.DuplicateResult duplicate = handles.duplicate(0x4321);
        assertEquals(Result.INVALID_HANDLE, duplicate.result());
        assertFalse(duplicate.result().isSuccess());
    }
}
