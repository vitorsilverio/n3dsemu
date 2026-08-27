package dev.vitorsilverio.n3dsemu.service;

import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.n3dsemu.ipc.IpcCommandHeader;
import dev.vitorsilverio.n3dsemu.ipc.IpcRequest;
import dev.vitorsilverio.n3dsemu.ipc.IpcResponse;
import dev.vitorsilverio.n3dsemu.kernel.HandleTable;
import dev.vitorsilverio.n3dsemu.kernel.ProcessObject;
import dev.vitorsilverio.n3dsemu.kernel.ThreadObject;
import dev.vitorsilverio.n3dsemu.memory.LoggingOpenBus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testa `fs:USER#OpenArchive`/`CloseArchive`/`OpenFileDirectly` + `FSFILE:#Read`/`GetSize`/
/// `Close` (RFC-N3DSEMU G6.2 — causa 1 achada pela G6.1: sem estes comandos,
/// `composite_scene`/`cubemap`/`gpusprites` chamavam `svcBreak(PANIC)`).
class FsUserServiceTest {
    private static final int PAGE_SHIFT = 12;
    private static final int BUFFER_ADDRESS = 0x1FF9_0080;
    private static final int OUT_BUFFER_ADDRESS = 0x1FFA_0000;

    private static final int CMD_OPEN_ARCHIVE = 0x80C;
    private static final int CMD_CLOSE_ARCHIVE = 0x80E;
    private static final int CMD_OPEN_FILE_DIRECTLY = 0x803;
    private static final int CMD_READ = 0x802;
    private static final int CMD_GET_SIZE = 0x804;
    private static final int CMD_CLOSE = 0x808;

    private static final byte[] RAW_FILE = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

    private record Harness(PagedAddressSpace memory, HandleTable handles, ServiceRegistry registry, FsUserService fs) {
    }

    private Harness newHarness() {
        PrintStream log = new PrintStream(new ByteArrayOutputStream());
        PagedAddressSpace memory = new PagedAddressSpace(PAGE_SHIFT, new LoggingOpenBus(log));
        memory.mapRam(BUFFER_ADDRESS & ~0xFFF, new byte[1 << PAGE_SHIFT]);
        memory.mapRam(OUT_BUFFER_ADDRESS & ~0xFFF, new byte[1 << PAGE_SHIFT]);
        HandleTable handles = new HandleTable(new ProcessObject(0), ThreadObject.mainThread(1, 0x30));
        ServiceRegistry registry = new ServiceRegistry();
        FsUserService fs = new FsUserService(log, handles, registry, memory, RAW_FILE.clone());
        registry.register(fs);
        return new Harness(memory, handles, registry, fs);
    }

    private void invoke(Harness h, Service service, int commandId, int normalParamCount, int... normalParams) {
        h.memory().write32(BUFFER_ADDRESS, IpcCommandHeader.pack(commandId, normalParamCount, 0));
        for (int i = 0; i < normalParams.length; i++) {
            h.memory().write32(BUFFER_ADDRESS + 4 * (1 + i), normalParams[i]);
        }
        IpcRequest request = new IpcRequest(h.memory(), BUFFER_ADDRESS);
        IpcResponse response = new IpcResponse(h.memory(), BUFFER_ADDRESS);
        service.handleRequest(request, response);
    }

    private int word(Harness h, int index) {
        return h.memory().read32(BUFFER_ADDRESS + 4 * index);
    }

    @Test
    void openArchiveSucceedsWithPlaceholderHandle() {
        Harness h = newHarness();
        invoke(h, h.fs(), CMD_OPEN_ARCHIVE, 3, 0x9 /* ARCHIVE_SDMC */, 0, 0);
        assertEquals(0, word(h, 1)); // Result.SUCCESS
        assertNotEquals(0, word(h, 2) | word(h, 3)); // handle de archive != 0
    }

    @Test
    void closeArchiveSucceeds() {
        Harness h = newHarness();
        invoke(h, h.fs(), CMD_CLOSE_ARCHIVE, 2, 1, 0);
        assertEquals(0, word(h, 1));
    }

    @Test
    void openFileDirectlyReturnsMovedHandleToNewFileSession() {
        Harness h = newHarness();
        invoke(h, h.fs(), CMD_OPEN_FILE_DIRECTLY, 8, 0, 0x9, 0, 0, 0, 0, 1, 0);
        assertEquals(0, word(h, 1)); // Result.SUCCESS
        assertEquals(IpcCommandHeader.moveHandleDescriptor(1), word(h, 2));
        int fileHandle = word(h, 3);
        assertTrue(h.handles().resolve(fileHandle).isPresent());
        Optional<Service> fileService = h.registry().resolve("fs:file#0");
        assertTrue(fileService.isPresent());
    }

    @Test
    void fileSessionGetSizeMatchesRawFileLength() {
        Harness h = newHarness();
        invoke(h, h.fs(), CMD_OPEN_FILE_DIRECTLY, 8, 0, 0x9, 0, 0, 0, 0, 1, 0);
        Service fileService = h.registry().resolve("fs:file#0").orElseThrow();

        invoke(h, fileService, CMD_GET_SIZE, 0);
        assertEquals(0, word(h, 1));
        long size = (word(h, 2) & 0xFFFF_FFFFL) | ((long) word(h, 3) << 32);
        assertEquals(RAW_FILE.length, size);
    }

    @Test
    void fileSessionReadCopiesBytesToGuestBuffer() {
        Harness h = newHarness();
        invoke(h, h.fs(), CMD_OPEN_FILE_DIRECTLY, 8, 0, 0x9, 0, 0, 0, 0, 1, 0);
        Service fileService = h.registry().resolve("fs:file#0").orElseThrow();

        h.memory().write32(BUFFER_ADDRESS, IpcCommandHeader.pack(CMD_READ, 3, 2));
        h.memory().write32(BUFFER_ADDRESS + 4 * 1, 2); // offset baixo
        h.memory().write32(BUFFER_ADDRESS + 4 * 2, 0); // offset alto
        h.memory().write32(BUFFER_ADDRESS + 4 * 3, 5); // size
        h.memory().write32(BUFFER_ADDRESS + 4 * 4, 0); // descritor de buffer (não inspecionado pelo valor)
        h.memory().write32(BUFFER_ADDRESS + 4 * 5, OUT_BUFFER_ADDRESS);
        IpcRequest request = new IpcRequest(h.memory(), BUFFER_ADDRESS);
        IpcResponse response = new IpcResponse(h.memory(), BUFFER_ADDRESS);
        fileService.handleRequest(request, response);

        assertEquals(0, word(h, 1)); // Result.SUCCESS
        assertEquals(5, word(h, 2)); // bytesRead
        byte[] read = new byte[5];
        for (int i = 0; i < read.length; i++) {
            read[i] = (byte) h.memory().read8(OUT_BUFFER_ADDRESS + i);
        }
        assertArrayEquals(new byte[]{3, 4, 5, 6, 7}, read);
    }

    @Test
    void fileSessionReadPastEndTruncates() {
        Harness h = newHarness();
        invoke(h, h.fs(), CMD_OPEN_FILE_DIRECTLY, 8, 0, 0x9, 0, 0, 0, 0, 1, 0);
        Service fileService = h.registry().resolve("fs:file#0").orElseThrow();

        h.memory().write32(BUFFER_ADDRESS, IpcCommandHeader.pack(CMD_READ, 3, 2));
        h.memory().write32(BUFFER_ADDRESS + 4 * 1, RAW_FILE.length - 2);
        h.memory().write32(BUFFER_ADDRESS + 4 * 2, 0);
        h.memory().write32(BUFFER_ADDRESS + 4 * 3, 100);
        h.memory().write32(BUFFER_ADDRESS + 4 * 4, 0);
        h.memory().write32(BUFFER_ADDRESS + 4 * 5, OUT_BUFFER_ADDRESS);
        IpcRequest request = new IpcRequest(h.memory(), BUFFER_ADDRESS);
        IpcResponse response = new IpcResponse(h.memory(), BUFFER_ADDRESS);
        fileService.handleRequest(request, response);

        assertEquals(0, word(h, 1));
        assertEquals(2, word(h, 2));
    }

    @Test
    void fileSessionCloseUnregistersFromServiceRegistry() {
        Harness h = newHarness();
        invoke(h, h.fs(), CMD_OPEN_FILE_DIRECTLY, 8, 0, 0x9, 0, 0, 0, 0, 1, 0);
        Service fileService = h.registry().resolve("fs:file#0").orElseThrow();

        invoke(h, fileService, CMD_CLOSE, 0);
        assertEquals(0, word(h, 1));
        assertTrue(h.registry().resolve("fs:file#0").isEmpty());
    }
}
