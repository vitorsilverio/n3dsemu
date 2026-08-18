package dev.vitorsilverio.n3dsemu.service;

import dev.vitorsilverio.n3dsemu.ipc.IpcRequest;
import dev.vitorsilverio.n3dsemu.ipc.IpcResponse;

/// Uma porta de serviço do Horizon (RFC-N3DSEMU G3 — 3dbrew: `srv`/`IPC`).
/// `svcConnectToPort("srv:")` e, depois, `srv:GetServiceHandle("hid:USER")` devolvem sessões
/// que caem aqui — {@link ServiceRegistry} mapeia o nome do serviço (`name()`) para a
/// implementação, e {@link dev.vitorsilverio.n3dsemu.kernel.SvcTable#handle} despacha todo
/// `svcSendSyncRequest` sobre uma sessão para {@link #handleRequest} do serviço correspondente.
///
/// **Contrato de "não travar" (G3, ao contrário da G2):** um comando desconhecido dentro de um
/// serviço CONHECIDO deve ser logado e responder um erro genérico — nunca lançar. É o oposto da
/// política da G2 (parar cedo na primeira `svc` não implementada); aqui o objetivo é mapear a
/// superfície inteira que o guest usa numa única execução, não travar na primeira lacuna.
public interface Service {
    /// Nome do serviço tal como o guest pede via `srv:GetServiceHandle` (ex.: `"hid:USER"`) ou
    /// o nome da própria porta conectada via `svcConnectToPort` (só `"srv:"`, RFC-N3DSEMU G3).
    String name();

    /// Processa uma requisição IPC já decodificada, escrevendo a resposta no mesmo buffer
    /// (ver Javadoc de {@link IpcResponse}). Nunca deve lançar por comando desconhecido — ver
    /// Javadoc da interface.
    void handleRequest(IpcRequest request, IpcResponse response);
}
