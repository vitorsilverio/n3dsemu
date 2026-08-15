package dev.vitorsilverio.n3dsemu.kernel;

/// Sessão devolvida por `svcConnectToPort` (RFC-N3DSEMU G2 PR2/"não inclui": nenhum serviço
/// responde de verdade — `svcSendSyncRequest` sobre esta handle loga o cabeçalho IPC e lança,
/// isso é a G3). Guarda só o nome da porta pedido, para o log de diagnóstico.
///
/// @param portName nome da porta (ex.: `"srv:"`), lido da string C do guest
public record SessionObject(String portName) implements KernelObject {
    @Override
    public String debugName() {
        return "Session(" + portName + ")";
    }
}
