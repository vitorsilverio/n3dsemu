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

    public Optional<Service> resolve(String name) {
        return Optional.ofNullable(services.get(name));
    }
}
