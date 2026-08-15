package dev.vitorsilverio.n3dsemu.kernel;

/// Lançada por {@link SvcTable} quando uma `svc` implementada encerra a execução do processo de
/// propósito — `svcExitProcess` (saída limpa) ou `svcBreak` (diagnóstico do guest). Diferente de
/// {@link UnsupportedSvcException}: esta não é "svc não implementada", é "svc implementada cujo
/// efeito é parar". Carrega só o {@link Reason} semântico — quem decide o código de saída do
/// processo HOST (`n3dsemu`) é o `Main`, não o kernel (o Horizon real não devolve nada ao guest
/// nos dois casos: o processo guest simplesmente para de existir).
public final class KernelHaltException extends RuntimeException {
    /// Por que a execução parou.
    public enum Reason {
        /// `svcExitProcess` — saída limpa, esperada.
        PROCESS_EXIT,
        /// `svcBreak` — o guest pediu para parar com um diagnóstico (panic/assert/usuário).
        GUEST_BREAK
    }

    private final Reason reason;

    public KernelHaltException(String message, Reason reason) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
