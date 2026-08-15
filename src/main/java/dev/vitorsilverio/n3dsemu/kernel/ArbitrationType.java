package dev.vitorsilverio.n3dsemu.kernel;

/// `ArbitrationType` — modo de operação de `svcArbitrateAddress`, `libctru/include/3ds/svc.h`
/// (conferido localmente, libctru 2.7.0). Semântica de cada modo transcrita do comentário do
/// próprio header + do comportamento de `kernel/address_arbiter.cpp` do Citra (não há acesso a
/// hardware real nesta sessão para validar bit a bit — ver Javadoc de {@link Scheduler}).
public final class ArbitrationType {
    private ArbitrationType() {
    }

    /// Sinaliza `value` threads esperando no endereço (ou todas, se `value` ==
    /// {@link #SIGNAL_ALL}). Não lê nem escreve a memória do endereço.
    public static final int SIGNAL = 0;
    /// Se `memory[addr] < value`, espera por sinal (sem alterar a memória).
    public static final int WAIT_IF_LESS_THAN = 1;
    /// Se `memory[addr] < value`, decrementa `memory[addr]` e espera por sinal.
    public static final int DECREMENT_AND_WAIT_IF_LESS_THAN = 2;
    /// Como {@link #WAIT_IF_LESS_THAN}, com timeout.
    public static final int WAIT_IF_LESS_THAN_TIMEOUT = 3;
    /// Como {@link #DECREMENT_AND_WAIT_IF_LESS_THAN}, com timeout.
    public static final int DECREMENT_AND_WAIT_IF_LESS_THAN_TIMEOUT = 4;

    /// Valor de `value` (`-1`) que pede para {@link #SIGNAL} acordar TODAS as threads
    /// esperando no endereço, não só as primeiras `value`.
    public static final int SIGNAL_ALL = -1;
}
