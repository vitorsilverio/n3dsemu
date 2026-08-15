package dev.vitorsilverio.n3dsemu.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testes unitários de {@link EventObject} isolados do {@link SvcTable}/`ArmCore`
/// (RFC-N3DSEMU G2 PR2 — aceite pede explicitamente "evento oneshot/sticky/pulse"; `PULSE` só
/// tem significado para timers, fora do escopo de {@link EventObject}, ver {@link ResetType}).
class EventObjectTest {
    private final ThreadObject threadA = ThreadObject.mainThread(1, 0x30);
    private final ThreadObject threadB = ThreadObject.coldStart(2, 0x30, 0, 0, 0, 0);

    @Test
    void naoSinalizadoNaoEstaDisponivel() {
        EventObject event = new EventObject(ResetType.ONESHOT);
        assertFalse(event.isAvailableFor(threadA));
    }

    @Test
    void oneshotAcordaUmaThreadESeLimpaSozinho() {
        EventObject event = new EventObject(ResetType.ONESHOT);
        event.signal();

        assertTrue(event.isAvailableFor(threadA));
        event.acquire(threadA); // consome o sinal

        assertFalse(event.isAvailableFor(threadB));
    }

    @Test
    void stickyPermaneceSinalizadoParaVariosAteClearExplicito() {
        EventObject event = new EventObject(ResetType.STICKY);
        event.signal();

        assertTrue(event.isAvailableFor(threadA));
        event.acquire(threadA); // sticky: acquire NÃO limpa

        assertTrue(event.isAvailableFor(threadB));
        event.clear();
        assertFalse(event.isAvailableFor(threadA));
    }

    @Test
    void clearSemSinalizarAntesENoOp() {
        EventObject event = new EventObject(ResetType.STICKY);
        event.clear();
        assertFalse(event.isAvailableFor(threadA));
    }
}
