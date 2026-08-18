package dev.vitorsilverio.n3dsemu.service;

import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.n3dsemu.ipc.IpcCommandHeader;
import dev.vitorsilverio.n3dsemu.ipc.IpcRequest;
import dev.vitorsilverio.n3dsemu.ipc.IpcResponse;
import dev.vitorsilverio.n3dsemu.kernel.HandleTable;
import dev.vitorsilverio.n3dsemu.kernel.ProcessObject;
import dev.vitorsilverio.n3dsemu.kernel.Result;
import dev.vitorsilverio.n3dsemu.kernel.ThreadObject;
import dev.vitorsilverio.n3dsemu.memory.LoggingOpenBus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/// Testa a coreografia mínima de `APT:U` que a task pede (RFC-N3DSEMU G3 —
/// `libctru/include/3ds/services/apt.h`): `GetLockHandle` → `Initialize` → `Enable` →
/// `GetAppletManInfo` → `ReceiveParameter` (entrega `APTCMD_WAKEUP` uma vez, depois nada) — o
/// "modelo mínimo que funciona" documentado na task e no Javadoc de {@link AptService}.
class AptServiceTest {
    private static final int PAGE_SHIFT = 12;
    private static final int BUFFER_ADDRESS = 0x1FF9_0080;

    private static final int CMD_GET_LOCK_HANDLE = 0x1;
    private static final int CMD_INITIALIZE = 0x2;
    private static final int CMD_ENABLE = 0x3;
    private static final int CMD_GET_APPLET_MAN_INFO = 0x5;
    private static final int CMD_RECEIVE_PARAMETER = 0xD;

    private static final int APTCMD_WAKEUP = 1;
    private static final int APTCMD_NONE = 0;

    private record Harness(PagedAddressSpace memory, HandleTable handles, AptService apt) {
    }

    private Harness newHarness() {
        PrintStream log = new PrintStream(new ByteArrayOutputStream());
        PagedAddressSpace memory = new PagedAddressSpace(PAGE_SHIFT, new LoggingOpenBus(log));
        memory.mapRam(BUFFER_ADDRESS & ~0xFFF, new byte[1 << PAGE_SHIFT]);
        HandleTable handles = new HandleTable(new ProcessObject(0), ThreadObject.mainThread(1, 0x30));
        return new Harness(memory, handles, new AptService(log, handles));
    }

    private void invoke(Harness h, int commandId, int normalParamCount, int... normalParams) {
        h.memory().write32(BUFFER_ADDRESS, IpcCommandHeader.pack(commandId, normalParamCount, 0));
        for (int i = 0; i < normalParams.length; i++) {
            h.memory().write32(BUFFER_ADDRESS + 4 * (1 + i), normalParams[i]);
        }
        IpcRequest request = new IpcRequest(h.memory(), BUFFER_ADDRESS);
        IpcResponse response = new IpcResponse(h.memory(), BUFFER_ADDRESS);
        h.apt().handleRequest(request, response);
    }

    private int result(Harness h) {
        return h.memory().read32(BUFFER_ADDRESS + 4);
    }

    @Test
    void coreografiaCompletaSucedeEmTodosOsPassos() {
        Harness h = newHarness();

        invoke(h, CMD_GET_LOCK_HANDLE, 1, 0);
        assertEquals(Result.SUCCESS.code(), result(h));

        invoke(h, CMD_INITIALIZE, 2, 0x300, 0);
        assertEquals(Result.SUCCESS.code(), result(h));

        invoke(h, CMD_ENABLE, 1, 0);
        assertEquals(Result.SUCCESS.code(), result(h));

        invoke(h, CMD_GET_APPLET_MAN_INFO, 1, 0);
        assertEquals(Result.SUCCESS.code(), result(h));
    }

    @Test
    void getLockHandleDevolveUmaHandleDeMutexValida() {
        Harness h = newHarness();

        invoke(h, CMD_GET_LOCK_HANDLE, 1, 0);

        int lockHandle = h.memory().read32(BUFFER_ADDRESS + 4 * 5); // após result+2 normais+descritor
        assertNotEquals(0, lockHandle);
        assertEquals(true, h.handles().resolve(lockHandle).isPresent());
    }

    @Test
    void receiveParameterEntregaWakeupUmaVezESeguraSemParametroDepois() {
        Harness h = newHarness();

        invoke(h, CMD_RECEIVE_PARAMETER, 2, 0x300, 0x100);
        int firstCommand = h.memory().read32(BUFFER_ADDRESS + 4 * 3);
        assertEquals(APTCMD_WAKEUP, firstCommand);

        invoke(h, CMD_RECEIVE_PARAMETER, 2, 0x300, 0x100);
        int secondCommand = h.memory().read32(BUFFER_ADDRESS + 4 * 3);
        assertEquals(APTCMD_NONE, secondCommand);

        invoke(h, CMD_RECEIVE_PARAMETER, 2, 0x300, 0x100);
        int thirdCommand = h.memory().read32(BUFFER_ADDRESS + 4 * 3);
        assertEquals(APTCMD_NONE, thirdCommand);
    }

    @Test
    void glanceParameterNuncaConsomeOWakeup() {
        Harness h = newHarness();
        int CMD_GLANCE_PARAMETER = 0xE;

        invoke(h, CMD_GLANCE_PARAMETER, 2, 0x300, 0x100);
        assertEquals(APTCMD_WAKEUP, h.memory().read32(BUFFER_ADDRESS + 4 * 3));
        invoke(h, CMD_GLANCE_PARAMETER, 2, 0x300, 0x100);
        assertEquals(APTCMD_WAKEUP, h.memory().read32(BUFFER_ADDRESS + 4 * 3)); // glance não consome

        invoke(h, CMD_RECEIVE_PARAMETER, 2, 0x300, 0x100);
        assertEquals(APTCMD_WAKEUP, h.memory().read32(BUFFER_ADDRESS + 4 * 3)); // receive consome agora
        invoke(h, CMD_RECEIVE_PARAMETER, 2, 0x300, 0x100);
        assertEquals(APTCMD_NONE, h.memory().read32(BUFFER_ADDRESS + 4 * 3));
    }

    @Test
    void comandoDesconhecidoNuncaLancaEDevolveErroGenerico() {
        Harness h = newHarness();

        invoke(h, 0x9999, 0);

        assertEquals(Result.SERVICE_COMMAND_NOT_IMPLEMENTED.code(), result(h));
    }
}
