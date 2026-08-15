package dev.vitorsilverio.n3dsemu.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testes unitários de {@link MutexObject} isolados do {@link SvcTable}/`ArmCore` (RFC-N3DSEMU
/// G2 PR2 — aceite pede "teste unitário por objeto de kernel", incl. explicitamente "mutex
/// recursivo").
class MutexObjectTest {
    private final ThreadObject threadA = ThreadObject.mainThread(1, 0x30);
    private final ThreadObject threadB = ThreadObject.coldStart(2, 0x30, 0, 0, 0, 0);

    @Test
    void mutexDestravadoEstaDisponivelParaQualquerThread() {
        MutexObject mutex = new MutexObject(null);
        assertTrue(mutex.isAvailableFor(threadA));
        assertTrue(mutex.isAvailableFor(threadB));
    }

    @Test
    void depoisDeAdquiridoSoEstaDisponivelParaODono() {
        MutexObject mutex = new MutexObject(null);
        mutex.acquire(threadA);

        assertTrue(mutex.isAvailableFor(threadA));
        assertFalse(mutex.isAvailableFor(threadB));
    }

    @Test
    void mutexERecursivoContaReentradas() {
        MutexObject mutex = new MutexObject(null);
        mutex.acquire(threadA);
        mutex.acquire(threadA); // reentrada

        // Uma liberação não basta — ainda tem uma reentrada pendente, mutex continua do dono.
        assertEquals(Result.SUCCESS, mutex.release(threadA));
        assertFalse(mutex.isAvailableFor(threadB));

        // Segunda liberação: agora sim destrava de verdade.
        assertEquals(Result.SUCCESS, mutex.release(threadA));
        assertTrue(mutex.isAvailableFor(threadB));
    }

    @Test
    void releasePorQuemNaoEDonoDevolveErroSemAlterarOEstado() {
        MutexObject mutex = new MutexObject(threadA);

        Result result = mutex.release(threadB);

        assertEquals(Result.NOT_LOCK_OWNER, result);
        assertFalse(result.isSuccess());
        // O mutex continua travado pelo dono original — release inválido não teve efeito.
        assertFalse(mutex.isAvailableFor(threadB));
        assertTrue(mutex.isAvailableFor(threadA));
    }

    @Test
    void releaseDeMutexSemDonoDevolveErro() {
        MutexObject mutex = new MutexObject(null);
        assertEquals(Result.NOT_LOCK_OWNER, mutex.release(threadA));
    }

    @Test
    void createdComInitialOwnerJaComecaTravado() {
        MutexObject mutex = new MutexObject(threadA);
        assertFalse(mutex.isAvailableFor(threadB));
        assertEquals(Result.SUCCESS, mutex.release(threadA));
        assertTrue(mutex.isAvailableFor(threadB));
    }
}
