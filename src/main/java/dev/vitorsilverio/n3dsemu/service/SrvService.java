package dev.vitorsilverio.n3dsemu.service;

import dev.vitorsilverio.n3dsemu.ipc.IpcRequest;
import dev.vitorsilverio.n3dsemu.ipc.IpcResponse;
import dev.vitorsilverio.n3dsemu.kernel.HandleTable;
import dev.vitorsilverio.n3dsemu.kernel.Result;
import dev.vitorsilverio.n3dsemu.kernel.SemaphoreObject;
import dev.vitorsilverio.n3dsemu.kernel.SessionObject;

import java.io.PrintStream;
import java.util.Objects;

/// `srv:` (RFC-N3DSEMU G3 — `libctru/include/3ds/srv.h`, `srvInit`/`srvGetServiceHandle`). A
/// porta que TODO outro serviço passa por trás — `svcConnectToPort("srv:")` (G2) devolve uma
/// sessão sobre este serviço; {@link #handleGetServiceHandle} é quem de fato entrega as sessões
/// dos demais serviços (`APT:U`, `hid:USER`...), criando uma
/// {@link dev.vitorsilverio.n3dsemu.kernel.SessionObject} nova com o nome pedido — a resolução
/// do nome para a {@link Service} de verdade só acontece depois, no próximo
/// `svcSendSyncRequest` sobre essa sessão nova (ver Javadoc de {@link ServiceRegistry}).
///
/// Números de comando conforme a task (`g3-servicos-srv-apt-hid-fs.md`): `Initialize` (`0x1`,
/// = `srvRegisterClient` do header — o nome "Initialize" é como a task chama o primeiro passo
/// da coreografia), `EnableNotification` (`0x2`), `GetServiceHandle` (`0x5`). `Subscribe`/
/// `Unsubscribe`/`ReceiveNotification` não têm número na task — usados aqui `0x9`/`0xA`/`0xB`
/// (bem documentados publicamente, mesma numeração usada por implementações HLE de referência
/// de outros emuladores 3DS de código aberto).
public final class SrvService extends AbstractService {
    public static final String NAME = "srv:";

    private static final int CMD_REGISTER_CLIENT = 0x1;
    private static final int CMD_ENABLE_NOTIFICATION = 0x2;
    private static final int CMD_GET_SERVICE_HANDLE = 0x5;
    private static final int CMD_SUBSCRIBE = 0x9;
    private static final int CMD_UNSUBSCRIBE = 0xA;
    private static final int CMD_RECEIVE_NOTIFICATION = 0xB;

    /// `srvGetServiceHandle`: nome embutido em 2 palavras normais (8 bytes ASCII), seguido de
    /// `namelen`(u32) e `flags`(u32) — 4 parâmetros normais no total.
    private static final int SERVICE_NAME_WORD_COUNT = 2;

    /// Contagem de notificações pendentes que a semáforo de notificação sustenta antes de
    /// bloquear quem libera — generoso, esta HLE nunca publica notificação de verdade (nenhum
    /// caminho do M3 depende disso), só precisa devolver uma handle válida.
    private static final int NOTIFICATION_SEMAPHORE_MAX_COUNT = 16;

    private final HandleTable handles;
    private final ServiceRegistry registry;

    public SrvService(PrintStream log, HandleTable handles, ServiceRegistry registry) {
        super(log);
        this.handles = Objects.requireNonNull(handles, "handles");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void handleRequest(IpcRequest request, IpcResponse response) {
        switch (request.commandId()) {
            case CMD_REGISTER_CLIENT -> handleRegisterClient(request, response);
            case CMD_ENABLE_NOTIFICATION -> handleEnableNotification(request, response);
            case CMD_GET_SERVICE_HANDLE -> handleGetServiceHandle(request, response);
            case CMD_SUBSCRIBE -> handleTrivialSuccess(request, response);
            case CMD_UNSUBSCRIBE -> handleTrivialSuccess(request, response);
            case CMD_RECEIVE_NOTIFICATION -> handleReceiveNotification(request, response);
            default -> respondUnknown(request, response);
        }
    }

    private void handleRegisterClient(IpcRequest request, IpcResponse response) {
        response.header(request.commandId(), 1, 0);
        response.result(Result.SUCCESS);
    }

    private void handleEnableNotification(IpcRequest request, IpcResponse response) {
        int semaphoreHandle = handles.create(new SemaphoreObject(0, NOTIFICATION_SEMAPHORE_MAX_COUNT));
        response.header(request.commandId(), 1, 2);
        response.result(Result.SUCCESS);
        response.translateHandles(semaphoreHandle);
    }

    private void handleGetServiceHandle(IpcRequest request, IpcResponse response) {
        String serviceName = request.normalParamAscii(0, SERVICE_NAME_WORD_COUNT);
        if (registry.resolve(serviceName).isEmpty()) {
            log.println("[srv:] GetServiceHandle de serviço não registrado: " + serviceName);
        }
        int sessionHandle = handles.create(new SessionObject(serviceName));
        response.header(request.commandId(), 1, 2);
        response.result(Result.SUCCESS);
        response.translateHandles(sessionHandle);
    }

    private void handleReceiveNotification(IpcRequest request, IpcResponse response) {
        // Nunca publicamos notificação de verdade (ver Javadoc da classe) — sem fila, não há
        // nada a entregar; devolve o mesmo erro genérico de "nada disponível" em vez de
        // bloquear (bloquear aqui prenderia o guest para sempre, já que nada nunca sinaliza).
        response.header(request.commandId(), 2, 0);
        response.result(Result.SERVICE_COMMAND_NOT_IMPLEMENTED);
        response.normalParam(1, 0);
    }

    private void handleTrivialSuccess(IpcRequest request, IpcResponse response) {
        response.header(request.commandId(), 1, 0);
        response.result(Result.SUCCESS);
    }
}
