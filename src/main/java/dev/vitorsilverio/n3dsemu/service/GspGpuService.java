package dev.vitorsilverio.n3dsemu.service;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
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
    /// `u8` — 1 na primeira `RegisterInterruptRelayQueue` (3dbrew/citra: `first_initialization`,
    /// diz ao guest se é o primeiro processo a registrar; esta HLE só sustenta uma thread/
    /// processo, RFC D1, então é sempre `1`).
    private static final int FIRST_INITIALIZATION_FLAG = 1;

    // ── fila de interrupções da memória compartilhada (3dbrew: GSP Shared Memory) ──────────
    // Bloco de 0x40 bytes por cliente; só um cliente nesta HLE (RFC D1), sempre no offset 0 do
    // bloco. Ver Javadoc da classe ("Achado real G3") para o porquê disto ser necessário.
    private static final int INTERRUPT_QUEUE_WRITE_INDEX_OFFSET = 0x0;
    private static final int INTERRUPT_QUEUE_COUNT_OFFSET = 0x1;
    private static final int INTERRUPT_QUEUE_ENTRIES_OFFSET = 0xC;
    private static final int INTERRUPT_QUEUE_CAPACITY = 0x40 - INTERRUPT_QUEUE_ENTRIES_OFFSET;
    /// `PDC0`/`VBlankTop` (3dbrew: GSP Shared Memory, "Interrupt list") — a única interrupção
    /// que esta HLE gera (RFC D6: só a tela de cima é composta na prática por este marco).
    private static final int INTERRUPT_TYPE_PDC0_VBLANK_TOP = 2;
    private static final int U8_MASK = 0xFF;

    private final AddressSpace memory;
    private final HandleTable handles;
    private final Scheduler scheduler;
    private final HidService hidService;
    private final int gspSharedMemoryHandle;

    private EventObject interruptEvent;
    private long commandListsTriggered;

    public GspGpuService(PrintStream log, AddressSpace memory, HandleTable handles, Scheduler scheduler,
                          HidService hidService) {
        super(log);
        this.memory = Objects.requireNonNull(memory, "memory");
        this.handles = Objects.requireNonNull(handles, "handles");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.hidService = Objects.requireNonNull(hidService, "hidService");
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
            case CMD_WRITE_HW_REGS, CMD_WRITE_HW_REGS_WITH_MASK, CMD_SET_BUFFER_SWAP,
                    CMD_FLUSH_DATA_CACHE, CMD_SET_LCD_FORCE_BLACK, CMD_ACQUIRE_RIGHT -> handleTrivialSuccess(request, response);
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
        response.normalParam(1, FIRST_INITIALIZATION_FLAG);
        response.translateHandles(gspSharedMemoryHandle);
    }

    private void handleTriggerCmdReqQueue(IpcRequest request, IpcResponse response) {
        commandListsTriggered++;
        handleTrivialSuccess(request, response);
    }

    private void handleTrivialSuccess(IpcRequest request, IpcResponse response) {
        response.header(request.commandId(), 1, 0);
        response.result(Result.SUCCESS);
    }

    /// Disparado pelo pulso de 60&nbsp;Hz de {@link Scheduler} — ver Javadoc da classe. Popula a
    /// fila de interrupções ANTES de sinalizar {@link #interruptEvent} (achado real, ver Javadoc
    /// da classe) — sem isso a thread interna do libctru que espera este evento nunca repassa o
    /// sinal para quem `gspWaitForVBlank()` de verdade espera.
    private void onVBlank() {
        Object resolvedBlock = handles.resolve(gspSharedMemoryHandle).orElse(null);
        if (resolvedBlock instanceof MemoryBlockObject block && block.hostMapped()) {
            pushInterrupt(block.address(), INTERRUPT_TYPE_PDC0_VBLANK_TOP);
        }
        if (interruptEvent != null) {
            interruptEvent.signal();
        }
        hidService.advanceFrame();
    }

    /// Escreve uma entrada na fila circular de interrupções (3dbrew: GSP Shared Memory) e
    /// avança índice/contagem — satura em {@link #INTERRUPT_QUEUE_CAPACITY} (o hardware real
    /// descarta e conta em "missed", este HLE só satura, suficiente para nunca perder o VBlank
    /// mais recente).
    private void pushInterrupt(int base, int interruptType) {
        int writeIndex = memory.read8(base + INTERRUPT_QUEUE_WRITE_INDEX_OFFSET) & U8_MASK;
        int count = memory.read8(base + INTERRUPT_QUEUE_COUNT_OFFSET) & U8_MASK;
        memory.write8(base + INTERRUPT_QUEUE_ENTRIES_OFFSET + writeIndex, interruptType);
        memory.write8(base + INTERRUPT_QUEUE_WRITE_INDEX_OFFSET, (writeIndex + 1) % INTERRUPT_QUEUE_CAPACITY);
        memory.write8(base + INTERRUPT_QUEUE_COUNT_OFFSET, Math.min(count + 1, INTERRUPT_QUEUE_CAPACITY));
    }

    /// Quantas listas de comando `TriggerCmdReqQueue` recebeu até agora — só diagnóstico (o
    /// conteúdo da lista nunca é interpretado nesta task, RFC/task: "guardadas e contadas").
    public long commandListsTriggered() {
        return commandListsTriggered;
    }
}
