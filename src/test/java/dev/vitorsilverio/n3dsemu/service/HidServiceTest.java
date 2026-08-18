package dev.vitorsilverio.n3dsemu.service;

import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.n3dsemu.input.InputScript;
import dev.vitorsilverio.n3dsemu.input.InputState;
import dev.vitorsilverio.n3dsemu.input.Keys;
import dev.vitorsilverio.n3dsemu.ipc.IpcCommandHeader;
import dev.vitorsilverio.n3dsemu.ipc.IpcRequest;
import dev.vitorsilverio.n3dsemu.ipc.IpcResponse;
import dev.vitorsilverio.n3dsemu.kernel.HandleTable;
import dev.vitorsilverio.n3dsemu.kernel.KernelObject;
import dev.vitorsilverio.n3dsemu.kernel.MemoryBlockObject;
import dev.vitorsilverio.n3dsemu.kernel.ProcessObject;
import dev.vitorsilverio.n3dsemu.kernel.ThreadObject;
import dev.vitorsilverio.n3dsemu.memory.LoggingOpenBus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testa {@link HidService} isolado (RFC-N3DSEMU G3): `GetIPCHandles`, o buffer em anel de
/// PAD/circle-pad da memória compartilhada (3dbrew: HID Shared Memory) e a aplicação de um
/// {@link InputScript}.
class HidServiceTest {
    private static final int PAGE_SHIFT = 12;
    private static final int BUFFER_ADDRESS = 0x1FF9_0080;
    private static final int SHARED_MEMORY_ADDRESS = 0x1000_0000;

    private static final int PAD_STATE_OFFSET = 0x1C;
    private static final int PAD_CIRCLE_OFFSET = 0x20;
    private static final int PAD_RING_INDEX_OFFSET = 0x10;
    private static final int PAD_RING_BUFFER_OFFSET = 0x28;

    private record Harness(PagedAddressSpace memory, HandleTable handles, HidService hid) {
    }

    private Harness newHarness(InputScript script) {
        PrintStream log = new PrintStream(new ByteArrayOutputStream());
        PagedAddressSpace memory = new PagedAddressSpace(PAGE_SHIFT, new LoggingOpenBus(log));
        memory.mapRam(BUFFER_ADDRESS & ~0xFFF, new byte[1 << PAGE_SHIFT]);
        memory.mapRam(SHARED_MEMORY_ADDRESS, new byte[1 << PAGE_SHIFT]);
        HandleTable handles = new HandleTable(new ProcessObject(0), ThreadObject.mainThread(1, 0x30));
        InputState inputState = new InputState();
        HidService hid = new HidService(log, memory, handles, inputState, script);
        return new Harness(memory, handles, hid);
    }

    private void invoke(Harness h, int commandId, int normalParamCount) {
        h.memory().write32(BUFFER_ADDRESS, IpcCommandHeader.pack(commandId, normalParamCount, 0));
        IpcRequest request = new IpcRequest(h.memory(), BUFFER_ADDRESS);
        IpcResponse response = new IpcResponse(h.memory(), BUFFER_ADDRESS);
        h.hid().handleRequest(request, response);
    }

    @Test
    void getIpcHandlesDevolveMemoriaCompartilhadaE5Eventos() {
        Harness h = newHarness(null);

        invoke(h, 0xA, 0);

        int normalCount = IpcCommandHeader.normalParamCount(h.memory().read32(BUFFER_ADDRESS));
        assertEquals(1, normalCount);
        // translate: 1 handle (mem) + 5 handles (eventos) = 2 descritores + 6 valores = 8 palavras.
        int translateCount = IpcCommandHeader.translateParamCount(h.memory().read32(BUFFER_ADDRESS));
        assertEquals(8, translateCount);

        int memHandle = h.memory().read32(BUFFER_ADDRESS + 4 * (1 + 1 + 1)); // após header+result+descritor
        assertTrue(h.handles().resolve(memHandle).orElseThrow() instanceof MemoryBlockObject);
    }

    @Test
    void advanceFrameEscreveEstadoDeBotoesECirclePadNaMemoriaCompartilhadaQuandoMapeada() {
        Harness h = newHarness(null);
        invoke(h, 0xA, 0);
        int memHandle = h.memory().read32(BUFFER_ADDRESS + 4 * 3);
        KernelObject block = h.handles().resolve(memHandle).orElseThrow();
        ((MemoryBlockObject) block).bindHostBacking(SHARED_MEMORY_ADDRESS);

        h.hid().advanceFrame(); // sem ação nenhuma: máscara deve ficar 0

        assertEquals(0, h.memory().read32(SHARED_MEMORY_ADDRESS + PAD_STATE_OFFSET));
    }

    @Test
    void avancarFrameSemBlocoMapeadoNaoLancaNemEscreve() {
        Harness h = newHarness(null);
        invoke(h, 0xA, 0); // cria a memória mas NUNCA mapeia (svcMapMemoryBlock nunca chamado)

        h.hid().advanceFrame(); // não deve lançar

        assertEquals(0, h.memory().read32(SHARED_MEMORY_ADDRESS + PAD_STATE_OFFSET)); // página nem tocada
    }

    @Test
    void bufferEmAnelDoPadAvancaIndiceECopiaOEstadoAtualACadaFrame() {
        Harness h = newHarness(null);
        invoke(h, 0xA, 0);
        int memHandle = h.memory().read32(BUFFER_ADDRESS + 4 * 3);
        ((MemoryBlockObject) h.handles().resolve(memHandle).orElseThrow()).bindHostBacking(SHARED_MEMORY_ADDRESS);

        h.hid().advanceFrame();
        int indexAfterFirst = h.memory().read32(SHARED_MEMORY_ADDRESS + PAD_RING_INDEX_OFFSET);
        h.hid().advanceFrame();
        int indexAfterSecond = h.memory().read32(SHARED_MEMORY_ADDRESS + PAD_RING_INDEX_OFFSET);

        assertEquals((indexAfterFirst + 1) % 8, indexAfterSecond);
        int entryOffset = SHARED_MEMORY_ADDRESS + PAD_RING_BUFFER_OFFSET + indexAfterSecond * 0x10;
        assertEquals(0, h.memory().read32(entryOffset)); // nenhum botão pressionado neste teste
    }

    @Test
    void scriptPressAplicaOBotaoNoFrameCertoENaoAntes() throws Exception {
        Path scriptPath = java.nio.file.Files.createTempFile("n3dsemu-script", ".txt");
        java.nio.file.Files.writeString(scriptPath, "2 press START\n");
        InputScript script = InputScript.load(scriptPath);
        Harness h = newHarness(script);
        invoke(h, 0xA, 0);
        int memHandle = h.memory().read32(BUFFER_ADDRESS + 4 * 3);
        ((MemoryBlockObject) h.handles().resolve(memHandle).orElseThrow()).bindHostBacking(SHARED_MEMORY_ADDRESS);

        h.hid().advanceFrame(); // frame 0: ainda não
        assertEquals(0, h.memory().read32(SHARED_MEMORY_ADDRESS + PAD_STATE_OFFSET) & Keys.KEY_START);
        h.hid().advanceFrame(); // frame 1: ainda não
        assertEquals(0, h.memory().read32(SHARED_MEMORY_ADDRESS + PAD_STATE_OFFSET) & Keys.KEY_START);
        h.hid().advanceFrame(); // frame 2: a ação do script dispara

        assertEquals(Keys.KEY_START, h.memory().read32(SHARED_MEMORY_ADDRESS + PAD_STATE_OFFSET) & Keys.KEY_START);
    }
}
