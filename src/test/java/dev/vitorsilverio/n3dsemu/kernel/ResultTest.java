package dev.vitorsilverio.n3dsemu.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// O layout de bits de {@link Result} foi reconstruído batendo {@link Result#code()} contra os
/// dois códigos completos publicados pelo 3dbrew (`Error_codes`) — ver Javadoc da classe. Este
/// teste fixa essa validação.
class ResultTest {
    @Test
    void sucessoECodigoZero() {
        assertEquals(0, Result.SUCCESS.code());
        assertTrue(Result.SUCCESS.isSuccess());
    }

    @Test
    void handleInvalidoBateComOCodigoPublicadoPeloTdbrew() {
        assertEquals(0xD8E007F7, Result.INVALID_HANDLE.code());
        assertFalse(Result.INVALID_HANDLE.isSuccess());
    }

    @Test
    void enumInvalidoBateComOCodigoPublicadoPeloTdbrew() {
        assertEquals(0xD8E007ED, Result.INVALID_ENUM_VALUE.code());
    }

    @Test
    void qualquerCampoNaoZeroTornaOCodigoUmErro() {
        Result custom = new Result(1, 0, 0, 0);
        assertFalse(custom.isSuccess());
    }
}
