package dev.vitorsilverio.n3dsemu.kernel;

/// Objeto de kernel devolvido por `svcGetResourceLimit`: um handle próprio que
/// `svcGetResourceLimitCurrentValues` consulta depois. O Horizon real tem um objeto destes por
/// processo, criado pelo `pm`/`loader`; aqui é um placeholder de valores plausíveis fixos (a
/// task desta sessão pede exatamente isso — ver {@link ResourceLimitValues}).
///
/// @param ownerProcessId {@link ProcessObject#processId()} do processo dono, só para diagnóstico
public record ResourceLimitObject(int ownerProcessId) implements KernelObject {
    @Override
    public String debugName() {
        return "ResourceLimit(process#" + ownerProcessId + ")";
    }
}
