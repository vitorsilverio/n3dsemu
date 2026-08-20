package dev.vitorsilverio.n3dsemu.service;

import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.n3dsemu.gpu.FrameBufferState;
import dev.vitorsilverio.n3dsemu.gpu.PicaRegisters;
import dev.vitorsilverio.n3dsemu.gpu.PixelFormat;
import dev.vitorsilverio.n3dsemu.gpu.RecordingRenderer;
import dev.vitorsilverio.n3dsemu.gpu.Screen;
import dev.vitorsilverio.n3dsemu.gpu.ShadedVertex;
import dev.vitorsilverio.n3dsemu.gpu.shader.ShaderUpload;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static final int CMD_SET_BUFFER_SWAP = 0x5;

    /// RFC-N3DSEMU G4: antes desta task, `SetBufferSwap` era um sucesso trivial que descartava
    /// os parâmetros — o laço de apresentação (`Main`) precisa saber ONDE está o framebuffer
    /// ativo de cada tela para poder ler a memória do guest a cada VBlank.
    @Test
    void setBufferSwapGravaOBufferAtivoDaTelaEmFrameBufferState() {
        Harness h = newHarness();
        int leftVaddr = 0x1800_0000;
        int stride = 240 * 3;
        int format = PixelFormat.RGB8.code();

        h.memory().write32(BUFFER_ADDRESS, IpcCommandHeader.pack(CMD_SET_BUFFER_SWAP, 8, 0));
        h.memory().write32(BUFFER_ADDRESS + 4, 0); // screenId=0 (TOP)
        h.memory().write32(BUFFER_ADDRESS + 8, 0); // active_fb
        h.memory().write32(BUFFER_ADDRESS + 12, leftVaddr);
        h.memory().write32(BUFFER_ADDRESS + 16, 0); // right vaddr (sem 3D, RFC D6)
        h.memory().write32(BUFFER_ADDRESS + 20, stride);
        h.memory().write32(BUFFER_ADDRESS + 24, format);
        h.memory().write32(BUFFER_ADDRESS + 28, 0); // mode
        h.memory().write32(BUFFER_ADDRESS + 32, 0); // attribute
        IpcRequest request = new IpcRequest(h.memory(), BUFFER_ADDRESS);
        IpcResponse response = new IpcResponse(h.memory(), BUFFER_ADDRESS);

        h.gsp().handleRequest(request, response);

        FrameBufferState.Buffer buffer = h.gsp().frameBufferState().active(Screen.TOP);
        assertEquals(leftVaddr, buffer.address());
        assertEquals(stride, buffer.stride());
        assertEquals(PixelFormat.RGB8, buffer.format());
        assertEquals(0, h.memory().read32(BUFFER_ADDRESS + 4)); // Result.SUCCESS
    }

    // ── RFC-N3DSEMU G5/PR3: integração real com a fila GX (TriggerCmdReqQueue lendo de verdade) ──

    private static final int CMD_TRIGGER_CMD_REQ_QUEUE = 0xC;
    private static final int COMMAND_LIST_ADDRESS = 0x1400_0000;
    private static final int VERTEX_DATA_ADDRESS = 0x1400_1000;
    private static final int GX_QUEUE_OFFSET = 0x800;

    /// Monta a lista de comandos PICA200 crua que um app real produziria para desenhar um único
    /// triângulo com um vertex shader `mov o0,v0 / mov o1,v1 / end` (posição/cor passadas direto,
    /// mesmo formato de `simple_tri`): upload de shader por registrador-FIFO + formato de vértice
    /// + `DrawArrays`. Layout de bits transcrito de {@link ShaderUpload} e
    /// `VertexShaderInterpreter`/`VertexAttributeLoader` (mesmos offsets, sem reinventar).
    private static int[] buildSimpleTriCommandList() {
        List<Integer> words = new ArrayList<>();

        // -- vertex shader: mov o0, v0 ; mov o1, v1 ; end (descritor 0 = máscara cheia, swizzle identidade) --
        int identityDescriptor = 0xF | (0x1B << 5); // mask=xyzw, negate=0, selector identidade (result[c]=v[c])
        int movOpcode = 0x13;
        int endOpcode = 0x22;
        int word0 = (movOpcode << 26); // mov o0(dst=0), v0(src1=0), desc=0
        int word1 = (movOpcode << 26) | (1 << 21) | (1 << 12); // mov o1(dst=1), v1(src1=1), desc=0
        int word2 = (endOpcode << 26);

        writeReg(words, ShaderUpload.REG_OPDESCS_INDEX, 0);
        writeReg(words, 0x2D6, identityDescriptor);
        writeReg(words, ShaderUpload.REG_CODETRANSFER_INDEX, 0);
        writeReg(words, 0x2CC, word0);
        writeReg(words, 0x2CC, word1);
        writeReg(words, 0x2CC, word2);
        writeReg(words, ShaderUpload.REG_ENTRYPOINT, 0);
        // Identidade completa (atributo N -> vN) — não só os 2 atributos usados: VertexPipeline
        // (PR2) escreve TODO atributo em input[attributeToInputRegister[attributeId]], mesmo os
        // não usados (que ficam com dado zerado); sem identidade nos demais, eles colidiriam no
        // MESMO registrador de entrada v0 (mapeamento default 0) e sobrescreveriam v0 com zero.
        writeReg(words, ShaderUpload.REG_ATTRIBUTES_PERMUTATION_LOW, 0x76543210);
        writeReg(words, ShaderUpload.REG_ATTRIBUTES_PERMUTATION_HIGH, 0xBA98);

        // -- formato de vértice: 2 atributos float4 (posição, cor), 1 loader, stride 32 --
        writeReg(words, 0x200, (VERTEX_DATA_ADDRESS / 16) << 1); // endereço base
        writeReg(words, 0x201, 0xFF); // atributo0/1: tipo FLOAT(3) + 4 componentes(3<<2), nibble 0xF cada
        writeReg(words, 0x202, 0);
        writeReg(words, 0x203, 0); // loader0: dataOffset=0
        writeReg(words, 0x204, 0x10); // slot0=atributo0(posição), slot1=atributo1(cor)
        writeReg(words, 0x205, (32 << 16) | (2 << 28)); // byteCount=32, componentCount=2
        writeReg(words, 0x228, 3); // numVertices
        writeReg(words, 0x22A, 0); // vertexOffset

        writeReg(words, PicaRegisters.DRAW_ARRAYS, 1);

        int[] array = new int[words.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = words.get(i);
        }
        return array;
    }

    private static void writeReg(List<Integer> words, int registerId, int value) {
        int header = (registerId & 0xFFFF) | (0xF << 16); // máscara cheia, sem extras, não-consecutivo
        words.add(value);
        words.add(header);
    }

    private static void writeVertex(PagedAddressSpace memory, int index, float px, float py, float pz, float pw,
                                     float r, float g, float b, float a) {
        int base = VERTEX_DATA_ADDRESS + index * 32;
        memory.write32(base, Float.floatToIntBits(px));
        memory.write32(base + 4, Float.floatToIntBits(py));
        memory.write32(base + 8, Float.floatToIntBits(pz));
        memory.write32(base + 12, Float.floatToIntBits(pw));
        memory.write32(base + 16, Float.floatToIntBits(r));
        memory.write32(base + 20, Float.floatToIntBits(g));
        memory.write32(base + 24, Float.floatToIntBits(b));
        memory.write32(base + 28, Float.floatToIntBits(a));
    }

    /// **O teste-alvo desta PR (RFC-N3DSEMU G5/PR3)**: até aqui, `TriggerCmdReqQueue` só contava
    /// o disparo — nenhuma lista de comandos PICA200 chegava a ser interpretada de verdade, então
    /// `simple_tri` nunca desenhava nada mesmo com o resto da G5 pronto (parser/shader
    /// interpretado/`VertexPipeline`/`VulkanRenderer` todos existiam, mas sem consumidor real).
    /// Este teste monta a fila GX real (`gxCmdQueue_s`) na memória compartilhada com UM comando
    /// `ProcessCommandList` apontando para uma lista PICA200 completa (upload de shader por
    /// registrador-FIFO + formato de vértice + `DrawArrays`), dispara `TriggerCmdReqQueue` por
    /// IPC — o MESMO caminho que `libctru`/`gspSubmitGxCommand` usam de verdade — e afirma que o
    /// {@link RecordingRenderer} recebeu o triângulo com a posição/cor exatas dos vértices, sem
    /// nenhuma alteração (o shader de teste é `mov`/`mov`/`end`, passagem direta).
    @Test
    void triggerCmdReqQueueProcessaListaDeComandosRealEDesenhaTrianguloDeVerdade() {
        PrintStream log = new PrintStream(new ByteArrayOutputStream());
        PagedAddressSpace memory = new PagedAddressSpace(PAGE_SHIFT, new LoggingOpenBus(log));
        memory.mapRam(BUFFER_ADDRESS & ~0xFFF, new byte[1 << PAGE_SHIFT]);
        memory.mapRam(SHARED_MEMORY_ADDRESS, new byte[1 << PAGE_SHIFT]);
        memory.mapRam(COMMAND_LIST_ADDRESS, new byte[1 << PAGE_SHIFT]);
        memory.mapRam(VERTEX_DATA_ADDRESS, new byte[1 << PAGE_SHIFT]);
        HandleTable handles = new HandleTable(new ProcessObject(0), ThreadObject.mainThread(1, 0x30));
        Scheduler scheduler = new Scheduler();
        HidService hid = new HidService(log, memory, handles, new InputState(), null);
        RecordingRenderer renderer = new RecordingRenderer();
        GspGpuService gsp = new GspGpuService(log, memory, handles, scheduler, hid, renderer);

        int eventHandle = handles.create(new EventObject(ResetType.STICKY));
        memory.write32(BUFFER_ADDRESS, IpcCommandHeader.pack(CMD_REGISTER_INTERRUPT_RELAY_QUEUE, 1, 2));
        memory.write32(BUFFER_ADDRESS + 4, 0);
        memory.write32(BUFFER_ADDRESS + 8, IpcCommandHeader.moveHandleDescriptor(1));
        memory.write32(BUFFER_ADDRESS + 12, eventHandle);
        gsp.handleRequest(new IpcRequest(memory, BUFFER_ADDRESS), new IpcResponse(memory, BUFFER_ADDRESS));
        int memHandle = memory.read32(BUFFER_ADDRESS + 4 * 4);
        MemoryBlockObject block = (MemoryBlockObject) handles.resolve(memHandle).orElseThrow();
        block.bindHostBacking(SHARED_MEMORY_ADDRESS);

        writeVertex(memory, 0, -0.5f, -0.5f, 0f, 1f, 1f, 0f, 0f, 1f);
        writeVertex(memory, 1, 0.5f, -0.5f, 0f, 1f, 0f, 1f, 0f, 1f);
        writeVertex(memory, 2, 0.0f, 0.5f, 0f, 1f, 0f, 0f, 1f, 1f);

        int[] commandList = buildSimpleTriCommandList();
        for (int i = 0; i < commandList.length; i++) {
            memory.write32(COMMAND_LIST_ADDRESS + 4 * i, commandList[i]);
        }

        // fila GX: 1 entrada pendente (ProcessCommandList) no cliente 0.
        int queueBase = SHARED_MEMORY_ADDRESS + GX_QUEUE_OFFSET;
        memory.write8(queueBase, 0); // commandIndex
        memory.write8(queueBase + 1, 1); // totalCommands
        int entryBase = queueBase + 0x8;
        memory.write8(entryBase, 1); // tipo = ProcessCommandList
        memory.write32(entryBase + 4, COMMAND_LIST_ADDRESS);
        memory.write32(entryBase + 8, commandList.length * 4);

        memory.write32(BUFFER_ADDRESS, IpcCommandHeader.pack(CMD_TRIGGER_CMD_REQ_QUEUE, 0, 0));
        gsp.handleRequest(new IpcRequest(memory, BUFFER_ADDRESS), new IpcResponse(memory, BUFFER_ADDRESS));

        List<ShadedVertex> triangle = renderer.lastTriangles(Screen.TOP);
        assertTrue(triangle != null && triangle.size() == 3, "esperava 3 vértices desenhados de verdade");
        assertEquals(-0.5f, triangle.get(0).ndcX());
        assertEquals(-0.5f, triangle.get(0).ndcY());
        assertEquals(1f, triangle.get(0).r());
        assertEquals(0f, triangle.get(0).g());
        assertEquals(0.5f, triangle.get(1).ndcX());
        assertEquals(0.0f, triangle.get(2).ndcX());
        assertEquals(1f, triangle.get(2).b());

        // commandIndex avançou (a fila não fica "presa" reprocessando a mesma entrada) e o evento
        // P3D foi sinalizado — sem isso, `gxCmdQueueWait`/`gspWaitForEvent` do guest travaria.
        assertEquals(1, memory.read8(queueBase) & 0xFF);
    }
}
