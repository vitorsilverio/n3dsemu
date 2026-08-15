package dev.vitorsilverio.n3dsemu.kernel;

/// Lançada por {@link SvcTable} para toda `svc` — nesta task (G1, marco M1) nenhuma tem
/// implementação real; a implementação vem na G2.
public final class UnsupportedSvcException extends RuntimeException {
    private final SvcCall call;

    public UnsupportedSvcException(SvcCall call) {
        super("SVC não implementada: " + call.format());
        this.call = call;
    }

    public SvcCall call() {
        return call;
    }
}
