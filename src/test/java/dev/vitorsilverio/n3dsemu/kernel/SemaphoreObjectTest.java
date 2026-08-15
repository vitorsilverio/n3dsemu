package dev.vitorsilverio.n3dsemu.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testes unitários de {@link SemaphoreObject} isolados do {@link SvcTable}/`ArmCore`
/// (RFC-N3DSEMU G2 PR2).
class SemaphoreObjectTest {
    private final ThreadObject thread = ThreadObject.mainThread(1, 0x30);

    @Test
    void comContagemZeroNaoEstaDisponivel() {
        SemaphoreObject semaphore = new SemaphoreObject(0, 3);
        assertFalse(semaphore.isAvailableFor(thread));
    }

    @Test
    void comContagemPositivaEstaDisponivelEAcquireDecrementa() {
        SemaphoreObject semaphore = new SemaphoreObject(2, 3);
        assertTrue(semaphore.isAvailableFor(thread));

        semaphore.acquire(thread);
        assertTrue(semaphore.isAvailableFor(thread)); // ainda tem 1

        semaphore.acquire(thread);
        assertFalse(semaphore.isAvailableFor(thread)); // chegou a 0
    }

    @Test
    void releaseDevolveAContagemAnteriorESomaAContagemAtual() {
        SemaphoreObject semaphore = new SemaphoreObject(0, 5);

        SemaphoreObject.ReleaseResult result = semaphore.release(3);

        assertTrue(result.result().isSuccess());
        assertEquals(0, result.previousCount());
        assertTrue(semaphore.isAvailableFor(thread));
    }

    @Test
    void releaseAlemDoMaxCountDevolveErroSemAlterarOEstado() {
        SemaphoreObject semaphore = new SemaphoreObject(4, 5);

        SemaphoreObject.ReleaseResult result = semaphore.release(2); // 4+2=6 > maxCount=5

        assertEquals(Result.OUT_OF_RANGE, result.result());
        assertFalse(result.result().isSuccess());
    }

    @Test
    void releaseComContagemNaoPositivaDevolveErro() {
        SemaphoreObject semaphore = new SemaphoreObject(1, 5);
        assertEquals(Result.OUT_OF_RANGE, semaphore.release(0).result());
        assertEquals(Result.OUT_OF_RANGE, semaphore.release(-1).result());
    }
}
