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

    /// Regressão do achado real da G3.3: {@code pushInterrupt} escrevia a entrada nova no índice
    /// do CURSOR DE LEITURA (offset 0x0 da fila) e avançava esse mesmo campo — mas esse campo
    /// pertence ao CLIENTE (`popInterrupt()` do libctru real o lê como `cur` e o avança sozinho a
    /// cada entrada consumida). A posição de escrita correta é `(readCursor + count) % CAPACITY`
    /// (3dbrew: "Offset from the count where to save incoming interrupts") — o kernel nunca deve
    /// tocar no cursor de leitura. Sem o fix, a primeira interrupção real (`PDC0`/VBlankTop`=2`)
    /// era escrita no slot 0 mas o avanço do cursor fazia o cliente ler o slot 1 (ainda zerado =
    /// `PSC0`=`0`) — o guest sinalizava o `LightEvent` errado (`gspEvents[0]` em vez de
    /// `gspEvents[2]`) e `gspWaitForEvent(GSPGPU_EVENT_VBlank0, ...)`, chamado por `gfxInit` logo
    /// no arranque do `read-controls.3dsx`, nunca era satisfeito — a thread principal travava para
    /// sempre em `svcArbitrateAddress(WAIT_IF_LESS_THAN)`, confirmado nesta sessão via trace com
    /// captura de `LR` (localizou o chamador real em `gfxInit`, não o wrapper fino do `svc`) e
    /// desmontagem de `read-controls.elf` (`arm-none-eabi-objdump`) cruzada com o `popInterrupt()`
    /// real do libctru (`WebFetch`).
    @Test
    void pushInterruptEscreveNoIndiceDerivadoDeContagemENaoMexeNoCursorDeLeitura() {
        Harness h = newHarness();
        int eventHandle = h.handles().create(new EventObject(ResetType.STICKY));
        invokeRegister(h, eventHandle);
        int memHandle = h.memory().read32(BUFFER_ADDRESS + 4 * 4);
        MemoryBlockObject block = (MemoryBlockObject) h.handles().resolve(memHandle).orElseThrow();
        block.bindHostBacking(SHARED_MEMORY_ADDRESS);

        h.gsp().onVBlank();
        h.gsp().onVBlank();

        int readCursorOffset = 0x0;
        int countOffset = 0x1;
        int entriesOffset = 0xC;
        int pdc0VBlankTop = 2;
        // O escritor NUNCA deve mexer no cursor de leitura (pertence só ao cliente) — só a
        // contagem avança.
        assertEquals(0, h.memory().read8(SHARED_MEMORY_ADDRESS + readCursorOffset) & 0xFF);
        assertEquals(2, h.memory().read8(SHARED_MEMORY_ADDRESS + countOffset) & 0xFF);
        // As duas entradas caem em slots DIFERENTES e corretos: (readCursor=0+count) a cada
        // chamada, não ambas no slot 0 nem no slot do cursor de leitura avançado incorretamente.
        assertEquals(pdc0VBlankTop, h.memory().read8(SHARED_MEMORY_ADDRESS + entriesOffset) & 0xFF);
        assertEquals(pdc0VBlankTop, h.memory().read8(SHARED_MEMORY_ADDRESS + entriesOffset + 1) & 0xFF);
    }
}
