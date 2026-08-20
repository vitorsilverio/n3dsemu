package dev.vitorsilverio.n3dsemu.service;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.n3dsemu.gpu.CommandListParser;
import dev.vitorsilverio.n3dsemu.gpu.FrameBufferState;
import dev.vitorsilverio.n3dsemu.gpu.GxCommandQueue;
import dev.vitorsilverio.n3dsemu.gpu.PicaRegisters;
import dev.vitorsilverio.n3dsemu.gpu.PicaRenderer;
import dev.vitorsilverio.n3dsemu.gpu.PixelFormat;
import dev.vitorsilverio.n3dsemu.gpu.Screen;
import dev.vitorsilverio.n3dsemu.gpu.VertexPipeline;
import dev.vitorsilverio.n3dsemu.gpu.shader.ShaderUpload;
import dev.vitorsilverio.n3dsemu.kernel.EventObject;
import dev.vitorsilverio.n3dsemu.kernel.HandleTable;
import dev.vitorsilverio.n3dsemu.kernel.KernelObject;
import dev.vitorsilverio.n3dsemu.kernel.MemoryBlockObject;
import dev.vitorsilverio.n3dsemu.kernel.MemoryPermission;
import dev.vitorsilverio.n3dsemu.kernel.Result;
import dev.vitorsilverio.n3dsemu.kernel.Scheduler;
import dev.vitorsilverio.n3dsemu.ipc.IpcRequest;
import dev.vitorsilverio.n3dsemu.ipc.IpcResponse;
import dev.vitorsilverio.n3dsemu.memory.MemoryMap;

import java.io.PrintStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// `gsp::Gpu`, mínimo do marco M3 (RFC-N3DSEMU G3 —
/// `libctru/include/3ds/services/gspgpu.h`). Guarda o estado que os comandos pedem e gera o
/// evento de **VBlank a 60&nbsp;Hz** (é ele que faz `gspWaitForVBlank()` retornar — RFC/task:
/// "sem o evento de VBlank, tudo trava") — mas **descarta** qualquer desenho de verdade (a G4/G5
/// trazem Vulkan e o rasterizador PICA200). As listas de comando (`TriggerCmdReqQueue`) são só
/// contadas.
///
/// **VBlank e o pulso mestre do sistema:** este serviço registra o único
/// {@link Scheduler#registerPeriodicPulse} do M3 e, a cada disparo (uma vez por
/// "quadro simulado", RFC/task), também aciona {@link HidService#advanceFrame} — HID atualiza
/// no mesmo instante que a tela "pisca", igual ao hardware real (a maioria dos jogos lê input
/// logo depois de esperar VBlank). Um relógio mestre só, por simplicidade — em vez de dois
/// pulsos independentes que teriam que ser mantidos sincronizados manualmente.
///
/// **Achado real (G3): não basta sinalizar {@link #interruptEvent}.** O libctru real
/// (`gspInit`) cria uma THREAD interna de prioridade alta (`_thread_begin`, observada no
/// `read-controls.3dsx` real via `svcCreateThread` logo após `RegisterInterruptRelayQueue`) cujo
/// único trabalho é: esperar {@link #interruptEvent}, ler a FILA de interrupções da memória
/// compartilhada (<a href="https://www.3dbrew.org/wiki/GSP_Shared_Memory">3dbrew: GSP Shared
/// Memory</a>, bloco de 0x40 bytes por cliente) e, para cada entrada, sinalizar o EVENTO
/// ESPECÍFICO daquele tipo de interrupção (`gspWaitForVBlank()` espera um evento PRÓPRIO de
/// VBlank, não {@link #interruptEvent} diretamente). Sinalizar só {@link #interruptEvent} sem
/// popular a fila faz essa thread interna acordar, achar a fila VAZIA, e voltar a dormir sem
/// nunca repassar o sinal — a aplicação principal (prioridade mais baixa, nunca escalonada
/// enquanto a thread de relay girar) trava para sempre esperando um evento de VBlank que nunca
/// chega. Descoberto rodando o `.3dsx` real e comparando com o layout documentado do 3dbrew, não
/// por analogia — {@link #onVBlank} agora escreve uma entrada `PDC0` (topo, valor `2`) na fila
/// ANTES de sinalizar, exatamente como o hardware faria.
public final class GspGpuService extends AbstractService {
    public static final String NAME = "gsp::Gpu";

    private static final int CMD_WRITE_HW_REGS = 0x1;
    private static final int CMD_WRITE_HW_REGS_WITH_MASK = 0x2;
    private static final int CMD_SET_BUFFER_SWAP = 0x5;
    private static final int CMD_FLUSH_DATA_CACHE = 0x8;
    private static final int CMD_SET_LCD_FORCE_BLACK = 0xB;
    private static final int CMD_TRIGGER_CMD_REQ_QUEUE = 0xC;
    private static final int CMD_REGISTER_INTERRUPT_RELAY_QUEUE = 0x13;
    private static final int CMD_ACQUIRE_RIGHT = 0x16;

    /// `SYSCLOCK_ARM11` (mesma constante de {@link Scheduler}, replicada aqui só para não
    /// expor um método package-private cruzado por uma única constante — ver Javadoc daquela
    /// classe para a fonte, 3dbrew `Configuration_Memory`).
    private static final long ARM11_CLOCK_HZ = 268_111_856L;
    private static final int VBLANK_HZ = 60;
    static final long TICKS_PER_FRAME = ARM11_CLOCK_HZ / VBLANK_HZ;

    private static final int GSP_SHARED_MEMORY_SIZE = MemoryMap.PAGE_SIZE;
    /// `u8` — segundo parâmetro normal de saída de `RegisterInterruptRelayQueue` (3dbrew:
    /// "GSP module thread index", **não** um booleano de "primeira inicialização" como o nome
    /// antigo desta constante sugeria — achado real da G3.2). O guest usa este valor como
    /// ÍNDICE DE CLIENTE para calcular a base do próprio bloco de 0x40 bytes na fila de
    /// interrupções (`clientBlock = sharedMemBase + threadIndex*0x40`, ver
    /// `gspEventThreadMain` do libctru real via `arm-none-eabi-objdump`) — devolver `1` fazia o
    /// guest ler/escrever no bloco do CLIENTE 1 (offset `0x40`), enquanto {@link #pushInterrupt}
    /// sempre escreve no bloco do CLIENTE 0 (offset `0`, esta HLE só sustenta um cliente, RFC
    /// D1): o guest nunca via as interrupções que este serviço empurrava, sua thread de relay
    /// ficava girando `svcClearEvent`/`svcWaitSynchronization` para sempre sem processar nada e
    /// nunca cedia a CPU para a thread principal da aplicação. `0` é o índice correto do único
    /// cliente que esta HLE sustenta.
    private static final int GSP_MODULE_THREAD_INDEX = 0;

    // ── fila de interrupções da memória compartilhada (3dbrew: GSP Shared Memory) ──────────
    // Bloco de 0x40 bytes por cliente; só um cliente nesta HLE (RFC D1), sempre no offset 0 do
    // bloco. Ver Javadoc da classe ("Achado real G3") para o porquê disto ser necessário.
    /// 3dbrew ("GSP Shared Memory"): "Offset from the count where to save incoming interrupts" —
    /// **não** é um write-cursor próprio do kernel: é o cursor de LEITURA do cliente
    /// (`gspEventThreadMain`/`popInterrupt` do libctru real leem este mesmo byte como `cur` e o
    /// avançam a cada entrada consumida). A posição de escrita correta é DERIVADA como
    /// `(readCursor + count) % CAPACITY` (achado real da G3.3, confirmado contra o texto da wiki
    /// e contra `popInterrupt()` do libctru via `WebFetch`) — o kernel NUNCA escreve neste campo.
    private static final int INTERRUPT_QUEUE_READ_CURSOR_OFFSET = 0x0;
    private static final int INTERRUPT_QUEUE_COUNT_OFFSET = 0x1;
    private static final int INTERRUPT_QUEUE_ENTRIES_OFFSET = 0xC;
    private static final int INTERRUPT_QUEUE_CAPACITY = 0x40 - INTERRUPT_QUEUE_ENTRIES_OFFSET;
    /// `PDC0`/`VBlankTop` (3dbrew: GSP Shared Memory, "Interrupt list") — a única interrupção
    /// que esta HLE gera (RFC D6: só a tela de cima é composta na prática por este marco).
    private static final int INTERRUPT_TYPE_PDC0_VBLANK_TOP = 2;
    private static final int U8_MASK = 0xFF;

    /// `FrameBufferUpdate` (3dbrew "GSP Shared Memory"; layout confirmado por `WebFetch` na wiki
    /// ao investigar a G4 "tela toda preta" — libctru 2.7.0 real NÃO chama `GSPGPU:SetBufferSwap`
    /// por IPC a cada quadro: `gfxSwapBuffersGpu`/`GSPGPU_SetBufferSwap` (a função C) escreve
    /// direto nesta struct em memória compartilhada — `sharedMemBase + 0x200 + clientID*0x80`
    /// (topo) / `+0x240` (baixo) — e só sinaliza via `is_dirty`; a IPC de mesmo nome existe mas
    /// nenhum app moderno a usa mais. Sem ler este bloco, {@link #frameBufferState} nunca era
    /// populado e a tela ficava preta para sempre, mesmo com o guest rodando e desenhando
    /// normalmente. Bloco de 0x40 bytes: 1 byte índice + 1 byte `is_dirty` + 2 bytes de padding +
    /// 2 entradas `FrameBufferInfo` de 7 palavras (28 bytes) cada.
    private static final int FRAMEBUFFER_UPDATE_TOP_OFFSET = 0x200;
    private static final int FRAMEBUFFER_UPDATE_BOTTOM_OFFSET = 0x240;
    private static final int FRAMEBUFFER_UPDATE_INDEX_OFFSET = 0x0;
    private static final int FRAMEBUFFER_UPDATE_DIRTY_OFFSET = 0x1;
    private static final int FRAMEBUFFER_UPDATE_ENTRIES_OFFSET = 0x4;
    private static final int FRAMEBUFFER_INFO_ENTRY_SIZE = 0x1C;
    private static final int FRAMEBUFFER_INFO_ADDRESS_LEFT_OFFSET = 0x4;
    private static final int FRAMEBUFFER_INFO_STRIDE_OFFSET = 0xC;
    private static final int FRAMEBUFFER_INFO_FORMAT_OFFSET = 0x10;

    /// `GSPGPU_SetBufferSwap(u8 screenId, GSPGPU_FramebufferInfo const* info)` — layout do IPC
    /// (3dbrew: `GSPGPU:SetBufferSwap`, `GSP_Shared_Memory` para os campos da struct): palavra 0
    /// = id da tela (0=`TOP`,1=`BOTTOM`), palavras 1-7 = `GSPGPU_FramebufferInfo` (28 bytes/7
    /// palavras: `active_fb`, `left vaddr`, `right vaddr`, `stride`, `format`, `mode`/`status`,
    /// `attribute` — só `left vaddr`/`stride`/`format` importam nesta HLE, RFC D6: sem 3D
    /// estereoscópico, sempre o olho esquerdo).
    private static final int SET_BUFFER_SWAP_SCREEN_ID = 0;
    private static final int SET_BUFFER_SWAP_LEFT_VADDR = 2;
    private static final int SET_BUFFER_SWAP_STRIDE = 4;
    private static final int SET_BUFFER_SWAP_FORMAT = 5;
    private static final int SCREEN_ID_TOP = 0;

    /// Base da fila de comandos GX (`gxCmdQueue_s`) do cliente 0 dentro da memória compartilhada
    /// (3dbrew: GSP Shared Memory, "Command buffer header" — `sharedMemBase + 0x800 +
    /// clientID*0x200`; só o cliente 0 nesta HLE, RFC D1, ver Javadoc de {@link GxCommandQueue}).
    private static final int GX_COMMAND_QUEUE_OFFSET = 0x800;

    private final AddressSpace memory;
    private final HandleTable handles;
    private final Scheduler scheduler;
    private final HidService hidService;
    private final FrameBufferState frameBufferState = new FrameBufferState();
    private final int gspSharedMemoryHandle;

    /// Banco de registradores internos da PICA200 (RFC-N3DSEMU G5/PR3) — persistente pela vida
    /// deste serviço, exatamente como o hardware real: uma lista de comandos só programa os
    /// registradores que muda, o resto sobrevive de quadro em quadro.
    private final PicaRegisters gpuRegisters = new PicaRegisters();
    /// Captura o upload de vertex shader por registrador-FIFO da ÚLTIMA lista processada (ver
    /// Javadoc de {@link ShaderUpload}) — como {@link #gpuRegisters}, sobrevive entre listas
    /// (RFC/task: um app típico faz upload do shader uma vez e desenha em vários quadros depois).
    private final ShaderUpload shaderUpload = new ShaderUpload();
    /// Destino real do desenho (RFC-N3DSEMU G5/PR3) — `null` no modo `--headless`/testes que não
    /// precisam de saída gráfica (RFC/task G3: comportamento herdado, "descarta tudo" quando não
    /// há para onde desenhar).
    private final PicaRenderer renderer;

    private EventObject interruptEvent;
    private long commandListsTriggered;
    private Runnable vBlankListener;

    public GspGpuService(PrintStream log, AddressSpace memory, HandleTable handles, Scheduler scheduler,
                          HidService hidService) {
        this(log, memory, handles, scheduler, hidService, null);
    }

    /// Como o construtor de 5 argumentos, mas com um {@link PicaRenderer} real (RFC-N3DSEMU
    /// G5/PR3) — `renderer` recebe os triângulos desenhados de verdade quando uma lista de
    /// comandos GX (`GX_ProcessCommandList`) dispara `DrawArrays`/`DrawElements` (ver
    /// {@link #handleCommandListWords}). `null` preserva o comportamento anterior (registradores
    /// aplicados/contados, nada desenhado) — usado pelo modo `--headless` e pelos testes que não
    /// sobem uma janela/GPU.
    public GspGpuService(PrintStream log, AddressSpace memory, HandleTable handles, Scheduler scheduler,
                          HidService hidService, PicaRenderer renderer) {
        super(log);
        this.memory = Objects.requireNonNull(memory, "memory");
        this.handles = Objects.requireNonNull(handles, "handles");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.hidService = Objects.requireNonNull(hidService, "hidService");
        this.renderer = renderer;
        this.gspSharedMemoryHandle = handles.create(
                MemoryBlockObject.serverOwned(GSP_SHARED_MEMORY_SIZE, MemoryPermission.READ_WRITE, MemoryPermission.READ));
        scheduler.registerPeriodicPulse(TICKS_PER_FRAME, this::onVBlank);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void handleRequest(IpcRequest request, IpcResponse response) {
        switch (request.commandId()) {
            case CMD_WRITE_HW_REGS, CMD_WRITE_HW_REGS_WITH_MASK,
                    CMD_FLUSH_DATA_CACHE, CMD_SET_LCD_FORCE_BLACK, CMD_ACQUIRE_RIGHT -> handleTrivialSuccess(request, response);
            case CMD_SET_BUFFER_SWAP -> handleSetBufferSwap(request, response);
            case CMD_TRIGGER_CMD_REQ_QUEUE -> handleTriggerCmdReqQueue(request, response);
            case CMD_REGISTER_INTERRUPT_RELAY_QUEUE -> handleRegisterInterruptRelayQueue(request, response);
            default -> respondUnknown(request, response);
        }
    }

    /// `GSPGPU_RegisterInterruptRelayQueue(u32 flags, Handle interruptEvent, Handle*
    /// outMemHandle, u8* threadOptimalId)`: ao contrário de `hid:USER` (que cria os próprios
    /// eventos), aqui é a APLICAÇÃO que cria o evento de interrupção (`svcCreateEvent`) e o
    /// PASSA por IPC — este serviço só guarda a referência para sinalizar a cada VBlank.
    private void handleRegisterInterruptRelayQueue(IpcRequest request, IpcResponse response) {
        int eventHandle = request.firstTranslatedHandle();
        Optional<KernelObject> resolved = handles.resolve(eventHandle);
        if (resolved.isPresent() && resolved.get() instanceof EventObject event) {
            this.interruptEvent = event;
        } else {
            log.println("[gsp::Gpu] RegisterInterruptRelayQueue com handle de evento inválida: 0x"
                    + Integer.toHexString(eventHandle));
        }
        response.header(request.commandId(), 2, 2);
        response.result(Result.SUCCESS);
        response.normalParam(1, GSP_MODULE_THREAD_INDEX);
        response.translateHandles(gspSharedMemoryHandle);
    }

    /// `GSPGPU_SetBufferSwap` — grava o buffer ATIVO da tela indicada em {@link #frameBufferState}
    /// (endereço/stride/formato, sempre o olho esquerdo — ver Javadoc da constante do layout). É
    /// isto que faz o laço de apresentação (`Main`, G4) saber onde ler o framebuffer do guest a
    /// cada VBlank — sem isto a chamada era tratada como um sucesso trivial e o conteúdo era
    /// descartado (comportamento herdado da G3, RFC/task: "descarta tudo").
    private void handleSetBufferSwap(IpcRequest request, IpcResponse response) {
        Screen screen = request.normalParam(SET_BUFFER_SWAP_SCREEN_ID) == SCREEN_ID_TOP ? Screen.TOP : Screen.BOTTOM;
        int leftVaddr = request.normalParam(SET_BUFFER_SWAP_LEFT_VADDR);
        int stride = request.normalParam(SET_BUFFER_SWAP_STRIDE);
        PixelFormat format = PixelFormat.fromCode(request.normalParam(SET_BUFFER_SWAP_FORMAT));
        frameBufferState.update(screen, leftVaddr, stride, format);
        handleTrivialSuccess(request, response);
    }

    /// `GSPGPU_TriggerCmdReqQueue` (RFC-N3DSEMU G5/PR3) — até esta PR só contava o disparo (RFC/
    /// task G3: "descarta tudo"). Agora lê de verdade a fila GX real da memória compartilhada
    /// (ver Javadoc de {@link GxCommandQueue}), processa cada `ProcessCommandList` pendente contra
    /// {@link #gpuRegisters}/{@link #shaderUpload} e sinaliza os eventos que o guest espera
    /// (`gspWaitForEvent(GSPGPU_EVENT_P3D, ...)` — sem isso a thread principal trava esperando um
    /// evento que nunca chega, mesma classe de bug já documentada para VBlank nesta classe).
    private void handleTriggerCmdReqQueue(IpcRequest request, IpcResponse response) {
        commandListsTriggered++;
        Object resolvedBlock = handles.resolve(gspSharedMemoryHandle).orElse(null);
        if (resolvedBlock instanceof MemoryBlockObject block && block.hostMapped()) {
            List<Integer> events = GxCommandQueue.processPending(
                    memory, block.address() + GX_COMMAND_QUEUE_OFFSET, this::handleCommandListWords);
            for (int event : events) {
                pushInterrupt(block.address(), event);
            }
            if (!events.isEmpty() && interruptEvent != null) {
                interruptEvent.signal();
            }
        }
        handleTrivialSuccess(request, response);
    }

    /// Aplica uma lista de comandos PICA200 crua nos registradores/no upload de shader e, se um
    /// `DrawArrays`/`DrawElements` de verdade foi disparado por ela, desenha no {@link #renderer}
    /// (RFC-N3DSEMU G5/PR3). **Simplificação documentada**: sempre {@link Screen#TOP} — a única
    /// tela composta por este HLE (RFC D6, mesma simplificação já usada em toda a G3/G4/G5). Se
    /// múltiplos disparos acontecem dentro da MESMA lista, todos leem o estado FINAL dos
    /// registradores (não um estado por disparo) — suficiente para `simple_tri`, que desenha uma
    /// vez por lista.
    private void handleCommandListWords(int[] words) {
        long arraysBefore = gpuRegisters.drawArraysTriggerCount();
        long elementsBefore = gpuRegisters.drawElementsTriggerCount();
        CommandListParser.parse(words, gpuRegisters, shaderUpload);
        if (renderer == null || !shaderUpload.hasProgram()) {
            return;
        }
        if (gpuRegisters.drawArraysTriggerCount() > arraysBefore) {
            VertexPipeline.drawArrays(shaderUpload.toShaderBinary(), shaderUpload.toExecutable(), gpuRegisters,
                    memory, shaderUpload.floatConstants(), shaderUpload.intConstants(), shaderUpload.boolConstants(),
                    shaderUpload.attributeToInputRegister(), Screen.TOP, renderer);
        }
        if (gpuRegisters.drawElementsTriggerCount() > elementsBefore) {
            VertexPipeline.drawElements(shaderUpload.toShaderBinary(), shaderUpload.toExecutable(), gpuRegisters,
                    memory, shaderUpload.floatConstants(), shaderUpload.intConstants(), shaderUpload.boolConstants(),
                    shaderUpload.attributeToInputRegister(), Screen.TOP, renderer);
        }
    }

    private void handleTrivialSuccess(IpcRequest request, IpcResponse response) {
        response.header(request.commandId(), 1, 0);
        response.result(Result.SUCCESS);
    }

    /// Disparado pelo pulso de 60&nbsp;Hz de {@link Scheduler} — ver Javadoc da classe. Popula a
    /// fila de interrupções ANTES de sinalizar {@link #interruptEvent} (achado real, ver Javadoc
    /// da classe) — sem isso a thread interna do libctru que espera este evento nunca repassa o
    /// sinal para quem `gspWaitForVBlank()` de verdade espera.
    void onVBlank() {
        Object resolvedBlock = handles.resolve(gspSharedMemoryHandle).orElse(null);
        if (resolvedBlock instanceof MemoryBlockObject block && block.hostMapped()) {
            readFramebufferUpdate(block.address() + FRAMEBUFFER_UPDATE_TOP_OFFSET, Screen.TOP);
            readFramebufferUpdate(block.address() + FRAMEBUFFER_UPDATE_BOTTOM_OFFSET, Screen.BOTTOM);
            pushInterrupt(block.address(), INTERRUPT_TYPE_PDC0_VBLANK_TOP);
        }
        if (interruptEvent != null) {
            interruptEvent.signal();
        }
        hidService.advanceFrame();
        if (vBlankListener != null) {
            vBlankListener.run();
        }
    }

    /// Registrado pelo laço de apresentação (`Main`, G4): chamado ao final de todo VBlank
    /// simulado, depois que HID já atualizou — é o sinal que `Main` espera antes de ler os
    /// framebuffers ativos e chamar {@link dev.vitorsilverio.n3dsemu.gpu.PicaRenderer#presentScreen}
    /// (RFC/task: "`runSlice()` até o `gsp` sinalizar VBlank").
    public void setVBlankListener(Runnable listener) {
        this.vBlankListener = listener;
    }

    /// Lê o `FrameBufferUpdate` de uma tela (ver Javadoc das constantes `FRAMEBUFFER_UPDATE_*`) e,
    /// se o app marcou `is_dirty`, aplica a entrada ativa em {@link #frameBufferState} e limpa a
    /// flag — o caminho real que substitui `GSPGPU:SetBufferSwap` por IPC no libctru moderno.
    private void readFramebufferUpdate(int updateBase, Screen screen) {
        int dirty = memory.read8(updateBase + FRAMEBUFFER_UPDATE_DIRTY_OFFSET) & U8_MASK;
        if (dirty == 0) {
            return;
        }
        int index = memory.read8(updateBase + FRAMEBUFFER_UPDATE_INDEX_OFFSET) & U8_MASK;
        int entryBase = updateBase + FRAMEBUFFER_UPDATE_ENTRIES_OFFSET + index * FRAMEBUFFER_INFO_ENTRY_SIZE;
        int leftVaddr = memory.read32(entryBase + FRAMEBUFFER_INFO_ADDRESS_LEFT_OFFSET);
        int stride = memory.read32(entryBase + FRAMEBUFFER_INFO_STRIDE_OFFSET);
        PixelFormat format = PixelFormat.fromCode(memory.read32(entryBase + FRAMEBUFFER_INFO_FORMAT_OFFSET));
        frameBufferState.update(screen, leftVaddr, stride, format);
        memory.write8(updateBase + FRAMEBUFFER_UPDATE_DIRTY_OFFSET, 0);
    }

    /// Escreve uma entrada na fila circular de interrupções (3dbrew: GSP Shared Memory) e avança
    /// a contagem — satura em {@link #INTERRUPT_QUEUE_CAPACITY} (o hardware real descarta e conta
    /// em "missed", este HLE só satura, suficiente para nunca perder o VBlank mais recente).
    ///
    /// **Achado real (G3.3)**: a posição de escrita é `(readCursor + count) % CAPACITY`, nunca o
    /// {@link #INTERRUPT_QUEUE_READ_CURSOR_OFFSET} diretamente — esse campo pertence ao cliente
    /// (`popInterrupt()` do libctru real o lê/avança a cada entrada consumida). A versão anterior
    /// escrevia no índice `readCursor` e avançava esse MESMO campo, colidindo com o cursor do
    /// cliente: a primeira entrada real (`PDC0`/VBlankTop`=2`) acabava sendo escrita no slot 0 mas
    /// lida do slot 1 (ainda zerado = `PSC0`=`0`), então o cliente sinalizava `gspEvents[0]` em vez
    /// de `gspEvents[2]` — `gspWaitForEvent(GSPGPU_EVENT_VBlank0, ...)`, chamado por `gfxInit` logo
    /// no arranque do `read-controls.3dsx`, nunca era satisfeito e a thread principal travava para
    /// sempre em `svcArbitrateAddress(WAIT_IF_LESS_THAN)` — a causa raiz do bloqueio investigado
    /// pela G3.3 (a G3.2, sessão anterior, já tinha corrigido um bug de índice DIFERENTE, o
    /// `GSP_MODULE_THREAD_INDEX`, que destravou a thread de relay em si, mas não este).
    private void pushInterrupt(int base, int interruptType) {
        int readCursor = memory.read8(base + INTERRUPT_QUEUE_READ_CURSOR_OFFSET) & U8_MASK;
        int count = memory.read8(base + INTERRUPT_QUEUE_COUNT_OFFSET) & U8_MASK;
        int writeIndex = (readCursor + count) % INTERRUPT_QUEUE_CAPACITY;
        memory.write8(base + INTERRUPT_QUEUE_ENTRIES_OFFSET + writeIndex, interruptType);
        memory.write8(base + INTERRUPT_QUEUE_COUNT_OFFSET, Math.min(count + 1, INTERRUPT_QUEUE_CAPACITY));
    }

    /// Quantas listas de comando `TriggerCmdReqQueue` recebeu até agora — só diagnóstico (o
    /// conteúdo da lista nunca é interpretado nesta task, RFC/task: "guardadas e contadas").
    public long commandListsTriggered() {
        return commandListsTriggered;
    }

    /// Buffers ativos mais recentes (uma entrada por {@link Screen} depois do primeiro
    /// `SetBufferSwap`) — lido pelo laço de apresentação (`Main`, G4) a cada VBlank.
    public FrameBufferState frameBufferState() {
        return frameBufferState;
    }
}
