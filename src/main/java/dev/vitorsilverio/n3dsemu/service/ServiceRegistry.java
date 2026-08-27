package dev.vitorsilverio.n3dsemu.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// Mapa nome → {@link Service} (RFC-N3DSEMU G3). `svcConnectToPort`/`srv:GetServiceHandle`
/// (ver {@link dev.vitorsilverio.n3dsemu.kernel.SvcTable}) criam uma
/// {@link dev.vitorsilverio.n3dsemu.kernel.SessionObject} guardando só o nome pedido; é este
/// registro que resolve o nome para a implementação de verdade no momento de um
/// `svcSendSyncRequest`, sem acoplar {@link dev.vitorsilverio.n3dsemu.kernel.SessionObject} ao
/// pacote `service` (mantém `kernel` livre de depender de `service`).
public final class ServiceRegistry {
    private final Map<String, Service> services = new HashMap<>();

    public void register(Service service) {
        services.put(Objects.requireNonNull(service, "service").name(), service);
    }

    /// Registra a mesma instância sob um nome de porta adicional (ex.: `APT:S`/`APT:A`
    /// apontando para a mesma {@link AptService} de `APT:U` — o `aptInit` real do libctru abre
    /// sessões separadas para os três nomes, mas todas falam com o mesmo serviço do lado do
    /// sistema real; nosso HLE mínimo não distingue comportamento entre eles).
    public void registerAlias(String name, Service service) {
        services.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(service, "service"));
    }

    public Optional<Service> resolve(String name) {
        return Optional.ofNullable(services.get(name));
    }

    /// Remove o registro de `name` (RFC-N3DSEMU G6.2 — `FSFILE::Close` desfaz o nome sintético
    /// criado por `FSUSER::OpenFileDirectly` para a sessão do arquivo; sem isso, cada arquivo
    /// aberto/fechado durante a vida do processo guest deixaria uma entrada morta neste mapa).
    public void unregister(String name) {
        services.remove(Objects.requireNonNull(name, "name"));
    }
}
