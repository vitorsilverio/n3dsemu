package dev.vitorsilverio.n3dsemu.kernel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// `svcCreateAddressArbiter`/`svcArbitrateAddress` (achado real desta sessão, ver Javadoc de
/// {@code SvcTable} — `0x21` não está na lista de SVCs da task, mas `0x22` (que ESTÁ) não tem
/// como funcionar sem um handle de arbiter real: o libctru cria um logo no arranque para as
/// travas leves (`LightLock`), então sem isto o boot nem chega perto do que a task pede).
///
/// Um arbiter gerencia filas de espera POR ENDEREÇO (não é um {@link Waitable} — espera-se nele
/// por `svcArbitrateAddress`, um mecanismo de bloqueio dedicado, nunca por
/// `svcWaitSynchronization*`). Não é `KernelObject` genérico compartilhado: cada handle de
/// arbiter tem seu próprio mapa de filas.
public final class AddressArbiterObject implements KernelObject {
    private final Map<Integer, List<ThreadObject>> waitersByAddress = new HashMap<>();

    void addWaiter(int address, ThreadObject thread) {
        waitersByAddress.computeIfAbsent(address, ignored -> new ArrayList<>()).add(thread);
    }

    void removeWaiter(int address, ThreadObject thread) {
        List<ThreadObject> waiters = waitersByAddress.get(address);
        if (waiters != null) {
            waiters.remove(thread);
        }
    }

    /// Lista viva (não uma cópia) das threads esperando em `address` — {@link Scheduler} a
    /// percorre removendo enquanto acorda (`ARBITRATION_SIGNAL`).
    List<ThreadObject> waitersAt(int address) {
        return waitersByAddress.getOrDefault(address, List.of());
    }

    @Override
    public String debugName() {
        return "AddressArbiter";
    }
}
