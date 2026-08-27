package dev.vitorsilverio.n3dsemu.service;

import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.n3dsemu.font.SharedFontGenerator;
import dev.vitorsilverio.n3dsemu.ipc.IpcRequest;
import dev.vitorsilverio.n3dsemu.ipc.IpcResponse;
import dev.vitorsilverio.n3dsemu.kernel.EventObject;
import dev.vitorsilverio.n3dsemu.kernel.HandleTable;
import dev.vitorsilverio.n3dsemu.kernel.MemoryBlockObject;
import dev.vitorsilverio.n3dsemu.kernel.MemoryPermission;
import dev.vitorsilverio.n3dsemu.kernel.MutexObject;
import dev.vitorsilverio.n3dsemu.kernel.ResetType;
import dev.vitorsilverio.n3dsemu.kernel.Result;
import dev.vitorsilverio.n3dsemu.memory.MemoryMap;

import java.io.PrintStream;
import java.util.Objects;

/// `APT:U` (RFC-N3DSEMU G3 — `libctru/include/3ds/services/apt.h`). "O mais chato" da task: o
/// libctru (`aptInit`/`aptMainLoop`) espera uma coreografia específica de chamadas; sem fonte
/// do lado do servidor real disponível neste ambiente (achado de ambiente já registrado — só
/// `include/`+`lib/*.a`), o formato de cada resposta segue o mesmo que implementações HLE de
/// referência de outros emuladores 3DS usam (documentado publicamente e testado contra o
/// `libctru` real por aqueles projetos) — layout de palavras verificado contra os próprios
/// wrappers `APT_*` do header (quais offsets cada `Handle*`/`u32*` de saída ocupam).
///
/// **Modelo mínimo (RFC/task):** o app é sempre o *foreground*, nunca suspenso.
/// {@link #handleReceiveParameter} entrega `APTSIGNAL`/`APTCMD_WAKEUP` uma única vez (na
/// primeira chamada) e depois fica sem parâmetro — nunca bloqueia (bloquear aqui, sem nada para
/// jamais sinalizar de verdade, prenderia o guest para sempre). `aptMainLoop()` deve então
/// devolver `true` para sempre.
public final class AptService extends AbstractService {
    public static final String NAME = "APT:U";

    private static final int CMD_GET_LOCK_HANDLE = 0x1;
    private static final int CMD_INITIALIZE = 0x2;
    private static final int CMD_ENABLE = 0x3;
    private static final int CMD_GET_APPLET_MAN_INFO = 0x5;
    private static final int CMD_NOTIFY_TO_WAIT = 0x43;
    private static final int CMD_RECEIVE_PARAMETER = 0xD;
    private static final int CMD_GLANCE_PARAMETER = 0xE;
    private static final int CMD_APPLET_UTILITY = 0x4B;
    private static final int CMD_PREPARE_TO_START_LIBRARY_APPLET = 0x18;
    private static final int CMD_SET_APP_CPU_TIME_LIMIT = 0x4F;
    private static final int CMD_GET_APP_CPU_TIME_LIMIT = 0x50;
    private static final int CMD_REPLY_SLEEP_QUERY = 0x3E;
    private static final int CMD_INQUIRE_NOTIFICATION = 0xB;
    private static final int CMD_GET_SHARED_FONT = 0x44;
    private static final int CMD_CHECK_NEW_3DS = 0x102;

    // ── NS_APPID/APT_AppletPos/APT_Command (apt.h) — só os valores que este modelo usa ──────
    private static final int APPID_APPLICATION = 0x300;
    private static final int APPID_NONE = 0;
    private static final int APTPOS_APP = 0;
    private static final int APTCMD_WAKEUP = 1;
    private static final int APTCMD_NONE = 0;

    /// `APT_SignalType`: nenhum sinal pendente (RFC/task — sempre foreground, ver Javadoc da
    /// classe: nunca há home-button/sleep-query/shutdown para reportar). `aptEventHandler`
    /// (thread de fundo do libctru que chama `InquireNotification` em loop) trata falha
    /// (`R_FAILED`) como "sem evento agora, tentar de novo depois" — devolver sucesso com
    /// `APTSIGNAL_NONE` é estritamente melhor (evita o loop de retry a cada chamada falhada) e
    /// não muda nenhum comportamento observável, já que nenhum sinal real é modelado.
    private static final int APTSIGNAL_NONE = 0;

    /// `APT_CheckNew3DS`: esta HLE não modela hardware New3DS (RFC — só Old3DS/ARM11 MPCore, ver
    /// `N3dsMachine`/`ArmArchitecture.ARM11_MPCORE`) — sempre `false`.
    private static final boolean IS_NEW_3DS = false;

    /// Percentual de CPU plausível fixo devolvido por `GetAppCpuTimeLimit` — o Horizon real
    /// permite configurar isso por app; esta HLE nunca aplica limite de verdade (RFC D1: sem
    /// escalonamento por tempo), o valor só precisa ser um `u32` "razoável" para o guest não
    /// reagir mal.
    private static final int PLACEHOLDER_CPU_TIME_LIMIT_PERCENT = 80;

    private final HandleTable handles;
    private final PagedAddressSpace memory;
    private boolean parameterDelivered;
    private int sharedFontHandle;

    public AptService(PrintStream log, PagedAddressSpace memory, HandleTable handles) {
        super(log);
        this.memory = Objects.requireNonNull(memory, "memory");
        this.handles = Objects.requireNonNull(handles, "handles");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void handleRequest(IpcRequest request, IpcResponse response) {
        switch (request.commandId()) {
            case CMD_GET_LOCK_HANDLE -> handleGetLockHandle(request, response);
            case CMD_INITIALIZE -> handleInitialize(request, response);
            case CMD_ENABLE -> handleTrivialSuccess(request, response);
            case CMD_GET_APPLET_MAN_INFO -> handleGetAppletManInfo(request, response);
            case CMD_NOTIFY_TO_WAIT -> handleTrivialSuccess(request, response);
            case CMD_RECEIVE_PARAMETER -> handleReceiveOrGlanceParameter(request, response, true);
            case CMD_GLANCE_PARAMETER -> handleReceiveOrGlanceParameter(request, response, false);
            case CMD_APPLET_UTILITY -> handleAppletUtility(request, response);
            case CMD_PREPARE_TO_START_LIBRARY_APPLET -> handlePrepareToStartLibraryApplet(request, response);
            case CMD_SET_APP_CPU_TIME_LIMIT -> handleTrivialSuccess(request, response);
            case CMD_GET_APP_CPU_TIME_LIMIT -> handleGetAppCpuTimeLimit(request, response);
            case CMD_REPLY_SLEEP_QUERY -> handleTrivialSuccess(request, response);
            case CMD_INQUIRE_NOTIFICATION -> handleInquireNotification(request, response);
            case CMD_GET_SHARED_FONT -> handleGetSharedFont(request, response);
            case CMD_CHECK_NEW_3DS -> handleCheckNew3ds(request, response);
            default -> respondUnknown(request, response);
        }
    }

    /// `APT_GetLockHandle(u16 flags, Handle* lockHandle)`: normal[0]=flags de entrada, ecoado
    /// de volta em normal[1] (o wrapper real não usa esse eco, mas alguns clientes leem — écoar
    /// é inofensivo e barato). `lockHandle` novo a cada chamada: só `aptInit` chama isto uma vez
    /// nesta task, então reaproveitar não muda comportamento observável.
    private void handleGetLockHandle(IpcRequest request, IpcResponse response) {
        int flags = request.normalParam(0);
        int lockHandle = handles.create(new MutexObject(null));
        response.header(request.commandId(), 3, 2);
        response.result(Result.SUCCESS);
        response.normalParam(1, flags);
        response.normalParam(2, 0);
        response.translateHandles(lockHandle);
    }

    /// `APT_Initialize(NS_APPID appId, APT_AppletAttr attr, Handle* signalEvent, Handle*
    /// resumeEvent)`: os dois eventos de saída são sinalizados imediatamente na criação — o
    /// modelo desta task é "sempre em foreground, nunca suspenso" (RFC/task), então qualquer
    /// espera do guest sobre eles deve resolver na hora, nunca bloquear para sempre.
    private void handleInitialize(IpcRequest request, IpcResponse response) {
        EventObject signalEvent = new EventObject(ResetType.STICKY);
        EventObject resumeEvent = new EventObject(ResetType.STICKY);
        signalEvent.signal();
        resumeEvent.signal();
        int signalHandle = handles.create(signalEvent);
        int resumeHandle = handles.create(resumeEvent);
        response.header(request.commandId(), 1, 3);
        response.result(Result.SUCCESS);
        response.translateHandles(signalHandle, resumeHandle);
    }

    /// `APT_GetAppletManInfo`: modelo fixo do RFC — a própria aplicação é a posição/AppID
    /// requisitados e ativos; não há menu (HOME) rodando.
    private void handleGetAppletManInfo(IpcRequest request, IpcResponse response) {
        response.header(request.commandId(), 5, 0);
        response.result(Result.SUCCESS);
        response.normalParam(1, APTPOS_APP);
        response.normalParam(2, APPID_APPLICATION);
        response.normalParam(3, APPID_NONE);
        response.normalParam(4, APPID_APPLICATION);
    }

    /// `APT_ReceiveParameter`/`APT_GlanceParameter`: entrega `APTCMD_WAKEUP` uma vez (ver
    /// Javadoc da classe); `glance` (`consume=false`) nunca marca como entregue — é só uma
    /// espiada, não deve consumir o sinal que `ReceiveParameter` ainda vai entregar.
    private void handleReceiveOrGlanceParameter(IpcRequest request, IpcResponse response, boolean consume) {
        boolean deliverWakeup = !parameterDelivered;
        if (consume && deliverWakeup) {
            parameterDelivered = true;
        }
        response.header(request.commandId(), 4, 2);
        response.result(Result.SUCCESS);
        response.normalParam(1, deliverWakeup ? APPID_APPLICATION : APPID_NONE);
        response.normalParam(2, deliverWakeup ? APTCMD_WAKEUP : APTCMD_NONE);
        response.normalParam(3, 0);
        response.translateHandles(0);
    }

    /// `APT_AppletUtility`: esta HLE não interpreta nenhum `id` de utilitário de verdade — só
    /// sucesso, sem tocar nos buffers estáticos de entrada/saída (RFC/task: gráfico é a G4/G5,
    /// nenhum utilitário do M3 depende do conteúdo devolvido aqui).
    private void handleAppletUtility(IpcRequest request, IpcResponse response) {
        log.println("[APT:U] AppletUtility id=" + request.normalParam(0) + " (ignorado, sempre sucesso)");
        response.header(request.commandId(), 1, 0);
        response.result(Result.SUCCESS);
    }

    /// Não implementamos applets de biblioteca de verdade (RFC "não inclui" — `am`/`ns` fora de
    /// escopo) — falha logada, não lançada, para não travar um guest que só sonda a
    /// disponibilidade antes de decidir não usar o recurso.
    private void handlePrepareToStartLibraryApplet(IpcRequest request, IpcResponse response) {
        log.println("[APT:U] PrepareToStartLibraryApplet appId=0x"
                + Integer.toHexString(request.normalParam(0)) + " — não suportado nesta HLE (sem applets)");
        response.header(request.commandId(), 1, 0);
        response.result(Result.SERVICE_COMMAND_NOT_IMPLEMENTED);
    }

    private void handleGetAppCpuTimeLimit(IpcRequest request, IpcResponse response) {
        response.header(request.commandId(), 2, 0);
        response.result(Result.SUCCESS);
        response.normalParam(1, PLACEHOLDER_CPU_TIME_LIMIT_PERCENT);
    }

    private void handleTrivialSuccess(IpcRequest request, IpcResponse response) {
        response.header(request.commandId(), 1, 0);
        response.result(Result.SUCCESS);
    }

    /// `APT_InquireNotification(NS_APPID appId, APT_Signal* signal)`: chamada em loop pela
    /// thread `aptEventHandler` do libctru (não pelo `main()` do guest) — antes desta task,
    /// `respondUnknown` devolvia `SERVICE_COMMAND_NOT_IMPLEMENTED`, que o libctru real trata como
    /// "sem evento agora" (`continue`, sem travar); documentado aqui porque foi a 1ª das 3
    /// hipóteses investigadas no achado da `G6.2` (`composite_scene`) — não era a causa do
    /// `svcBreak(PANIC)` (ver Javadoc de {@link #handleGetSharedFont}), mas merece resposta real
    /// mesmo assim (ver Javadoc de {@link #APTSIGNAL_NONE}).
    private void handleInquireNotification(IpcRequest request, IpcResponse response) {
        response.header(request.commandId(), 2, 0);
        response.result(Result.SUCCESS);
        response.normalParam(1, APTSIGNAL_NONE);
    }

    /// `APT_GetSharedFont(Handle* fontHandle, u32* mapAddr)` — a causa REAL do `svcBreak(PANIC)`
    /// achado pela `G6.2` em `composite_scene` (task G6.5): `C2D_TextParse` (citro2d) exige uma
    /// fonte do sistema mapeada antes de poder medir/desenhar texto; sem este comando, o citro2d
    /// aborta. Gera a fonte (Noto Sans, licença SIL OFL — ver {@link SharedFontGenerator}) e mapeia
    /// como RAM comum uma única vez (`sharedFontHandle == 0` só na 1ª chamada; `HandleTable` nunca
    /// devolve a handle `0`, ver seu Javadoc) — chamadas seguintes reusam o mesmo bloco, mesmo
    /// padrão de "handle já mapeada" de {@link MemoryBlockObject#MemoryBlockObject(int, int, int,
    /// int)} (RAM de verdade desde a criação, ao contrário do {@link MemoryBlockObject#serverOwned}
    /// usado por `gsp::Gpu` — aqui não há negociação de endereço com o guest, o endereço já é
    /// escolhido por {@link MemoryMap#SHARED_FONT_BASE} de antemão, exatamente como o hardware
    /// real devolve `mapAddr` já resolvido, sem o guest chamar `svcMapMemoryBlock`).
    private void handleGetSharedFont(IpcRequest request, IpcResponse response) {
        if (sharedFontHandle == 0) {
            byte[] font = SharedFontGenerator.build(MemoryMap.SHARED_FONT_BASE, MemoryMap.PAGE_SIZE);
            memory.mapRam(MemoryMap.SHARED_FONT_BASE, font);
            sharedFontHandle = handles.create(new MemoryBlockObject(
                    MemoryMap.SHARED_FONT_BASE, font.length, MemoryPermission.READ, MemoryPermission.READ));
        }
        response.header(request.commandId(), 2, 2);
        response.result(Result.SUCCESS);
        response.normalParam(1, MemoryMap.SHARED_FONT_BASE);
        response.translateHandles(sharedFontHandle);
    }

    /// `APT_CheckNew3DS(bool* out)` — 2ª hipótese investigada no achado da `G6.2`; ver
    /// {@link #IS_NEW_3DS}.
    private void handleCheckNew3ds(IpcRequest request, IpcResponse response) {
        response.header(request.commandId(), 2, 0);
        response.result(Result.SUCCESS);
        response.normalParam(1, IS_NEW_3DS ? 1 : 0);
    }
}
