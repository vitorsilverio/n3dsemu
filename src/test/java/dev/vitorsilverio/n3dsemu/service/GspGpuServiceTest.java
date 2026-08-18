package dev.vitorsilverio.n3dsemu.service;

import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.n3dsemu.input.InputState;
import dev.vitorsilverio.n3dsemu.ipc.IpcCommandHeader;
import dev.vitorsilverio.n3dsemu.ipc.IpcRequest;
import dev.vitorsilverio.n3dsemu.ipc.IpcResponse;
import dev.vitorsilverio.n3dsemu.kernel.EventObject;
import dev.vitorsilverio.n3dsemu.kernel.HandleTable;
import dev.vitorsilverio.n3dsemu.kernel.KernelObject;
import dev.vitorsilverio.n3dsemu.kernel.MemoryBlockObject;
import dev.vitorsilverio.n3dsemu.kernel.ProcessObject;
import dev.vitorsilverio.n3dsemu.kernel.ResetType;
import dev.vitorsilverio.n3dsemu.kernel.Scheduler;
import dev.vitorsilverio.n3dsemu.kernel.ThreadObject;
import dev.vitorsilverio.n3dsemu.memory.LoggingOpenBus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/// Testa {@link GspGpuService} isolado (RFC-N3DSEMU G3.2 — investigação da inanição do loop de
/// `read-controls.3dsx`). Regressão do achado real da G3.2: `RegisterInterruptRelayQueue`
/// devolvia `1` (constante antiga `FIRST_INITIALIZATION_FLAG`) no segundo parâmetro normal, mas
/// esse campo é o **"GSP module thread index"** (3dbrew) — o guest usa esse valor para calcular
/// a base do próprio bloco de 0x40 bytes na fila de interrupções compartilhada
/// (`clientBlock = sharedMemBase + threadIndex*0x40`). Devolver `1` fazia o guest ler/escrever no
/// bloco do cliente 1 (offset `0x40`) enquanto {@code GspGpuService#pushInterrupt} sempre escreve
/// no bloco do cliente 0 (offset `0`, único cliente sustentado por esta HLE, RFC D1) — a thread de
/// relay do libctru real (`gspEventThreadMain`) nunca via nenhuma interrupção enfileirada e girava
/// `svcClearEvent`/`svcWaitSynchronization` para sempre, nunca cedendo a CPU para a thread
/// principal da aplicação (confirmado via disassembly do `read-controls.elf` real e trace de
/// SVCs do `n3dsemu --trace-svc`).
class GspGpuServiceTest {
    private static final int PAGE_SHIFT = 12;
    private static final int BUFFER_ADDRESS = 0x1FF9_0080;
    private static final int SHARED_MEMORY_ADDRESS = 0x1000_1000;
    private static final int CMD_REGISTER_INTERRUPT_RELAY_QUEUE = 0x13;

    private record Harness(PagedAddressSpace memory, HandleTable handles, GspGpuService gsp) {
    }

    private Harness newHarness() {
        PrintStream log = new PrintStream(new ByteArrayOutputStream());
        PagedAddressSpace memory = new PagedAddressSpace(PAGE_SHIFT, new LoggingOpenBus(log));
        memory.mapRam(BUFFER_ADDRESS & ~0xFFF, new byte[1 << PAGE_SHIFT]);
        memory.mapRam(SHARED_MEMORY_ADDRESS, new byte[1 << PAGE_SHIFT]);
        HandleTable handles = new HandleTable(new ProcessObject(0), ThreadObject.mainThread(1, 0x30));
        Scheduler scheduler = new Scheduler();
        HidService hid = new HidService(log, memory, handles, new InputState(), null);
        GspGpuService gsp = new GspGpuService(log, memory, handles, scheduler, hid);
        return new Harness(memory, handles, gsp);
    }

    /// Constrói e despacha a requisição `RegisterInterruptRelayQueue(flags=0, eventHandle)`.
    private void invokeRegister(Harness h, int eventHandle) {
        h.memory().write32(BUFFER_ADDRESS, IpcCommandHeader.pack(CMD_REGISTER_INTERRUPT_RELAY_QUEUE, 1, 2));
        h.memory().write32(BUFFER_ADDRESS + 4, 0); // flags
        h.memory().write32(BUFFER_ADDRESS + 8, IpcCommandHeader.moveHandleDescriptor(1));
        h.memory().write32(BUFFER_ADDRESS + 12, eventHandle);
        IpcRequest request = new IpcRequest(h.memory(), BUFFER_ADDRESS);
        IpcResponse response = new IpcResponse(h.memory(), BUFFER_ADDRESS);
        h.gsp().handleRequest(request, response);
    }

    @Test
    void registerInterruptRelayQueueDevolveIndiceDeThreadZeroNaoUmBooleano() {
        Harness h = newHarness();
        int eventHandle = h.handles().create(new EventObject(ResetType.STICKY));

        invokeRegister(h, eventHandle);

        // Layout da resposta: word0=header, word1=result, word2=normalParam(1)="GSP module
        // thread index" (3dbrew) — precisa ser 0 (único cliente desta HLE), NÃO 1.
        assertEquals(0, h.memory().read32(BUFFER_ADDRESS + 4 * 2));
    }

    @Test
    void registerInterruptRelayQueueTraduzOHandleDaMemoriaCompartilhadaDoClienteZero() {
        Harness h = newHarness();
        int eventHandle = h.handles().create(new EventObject(ResetType.STICKY));

        invokeRegister(h, eventHandle);

        // resposta: normalParamCount=2 (result + threadIndex) -> tradução começa em word3
        // (descritor), valor da handle em word4.
        int memHandle = h.memory().read32(BUFFER_ADDRESS + 4 * 4);
        KernelObject resolved = h.handles().resolve(memHandle).orElseThrow();
        assertInstanceOf(MemoryBlockObject.class, resolved);
    }
}
