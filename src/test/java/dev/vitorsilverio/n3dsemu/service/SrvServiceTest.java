package dev.vitorsilverio.n3dsemu.service;

import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.n3dsemu.ipc.IpcCommandHeader;
import dev.vitorsilverio.n3dsemu.ipc.IpcRequest;
import dev.vitorsilverio.n3dsemu.ipc.IpcResponse;
import dev.vitorsilverio.n3dsemu.kernel.HandleTable;
import dev.vitorsilverio.n3dsemu.kernel.ProcessObject;
import dev.vitorsilverio.n3dsemu.kernel.Result;
import dev.vitorsilverio.n3dsemu.kernel.SessionObject;
import dev.vitorsilverio.n3dsemu.kernel.ThreadObject;
import dev.vitorsilverio.n3dsemu.memory.LoggingOpenBus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testa `srv:` (RFC-N3DSEMU G3): `GetServiceHandle` cria uma sessão nova resolvível por
/// {@link ServiceRegistry}, e comando desconhecido nunca lança (ver Javadoc de {@link Service}).
class SrvServiceTest {
    private static final int PAGE_SHIFT = 12;
    private static final int BUFFER_ADDRESS = 0x1FF9_0080;
    private static final int CMD_GET_SERVICE_HANDLE = 0x5;

    private record Harness(PagedAddressSpace memory, HandleTable handles, ServiceRegistry registry, SrvService srv) {
    }

    private Harness newHarness() {
        PrintStream log = new PrintStream(new ByteArrayOutputStream());
        PagedAddressSpace memory = new PagedAddressSpace(PAGE_SHIFT, new LoggingOpenBus(log));
        memory.mapRam(BUFFER_ADDRESS & ~0xFFF, new byte[1 << PAGE_SHIFT]);
        HandleTable handles = new HandleTable(new ProcessObject(0), ThreadObject.mainThread(1, 0x30));
        ServiceRegistry registry = new ServiceRegistry();
        SrvService srv = new SrvService(log, handles, registry);
        registry.register(srv);
        return new Harness(memory, handles, registry, srv);
    }

    @Test
    void getServiceHandleCriaUmaSessaoParaOServicoPedido() {
        Harness h = newHarness();
        String name = "hid:USER";
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        h.memory().write32(BUFFER_ADDRESS, IpcCommandHeader.pack(CMD_GET_SERVICE_HANDLE, 4, 0));
        h.memory().write32(BUFFER_ADDRESS + 4, wordOf(bytes, 0));
        h.memory().write32(BUFFER_ADDRESS + 8, wordOf(bytes, 4));
        h.memory().write32(BUFFER_ADDRESS + 12, bytes.length);
        h.memory().write32(BUFFER_ADDRESS + 16, 0);
        IpcRequest request = new IpcRequest(h.memory(), BUFFER_ADDRESS);
        IpcResponse response = new IpcResponse(h.memory(), BUFFER_ADDRESS);

        h.srv().handleRequest(request, response);

        assertEquals(Result.SUCCESS.code(), h.memory().read32(BUFFER_ADDRESS + 4));
        int sessionHandle = h.memory().read32(BUFFER_ADDRESS + 4 * 3);
        assertEquals(new SessionObject(name), h.handles().resolve(sessionHandle).orElseThrow());
    }

    @Test
    void comandoDesconhecidoNuncaLancaEDevolveErroGenerico() {
        Harness h = newHarness();
        h.memory().write32(BUFFER_ADDRESS, IpcCommandHeader.pack(0x7777, 0, 0));
        IpcRequest request = new IpcRequest(h.memory(), BUFFER_ADDRESS);
        IpcResponse response = new IpcResponse(h.memory(), BUFFER_ADDRESS);

        h.srv().handleRequest(request, response);

        assertEquals(Result.SERVICE_COMMAND_NOT_IMPLEMENTED.code(), h.memory().read32(BUFFER_ADDRESS + 4));
    }

    @Test
    void registryResolveEncontraOServicoPeloNomeRegistrado() {
        Harness h = newHarness();

        assertTrue(h.registry().resolve(SrvService.NAME).isPresent());
        assertTrue(h.registry().resolve("nao-existe").isEmpty());
    }

    private static int wordOf(byte[] bytes, int startIndex) {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int idx = startIndex + i;
            int b = idx < bytes.length ? (bytes[idx] & 0xFF) : 0;
            value |= b << (8 * i);
        }
        return value;
    }
}
