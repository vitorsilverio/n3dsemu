package dev.vitorsilverio.n3dsemu.kernel;

/// O único processo do guest (RFC-N3DSEMU D1/D2: um `.3dsx`, um processo — sem multi-processo
/// no kernel HLE). Alcançável pelo handle pseudo-reservado {@link HandleTable#CURRENT_PROCESS_HANDLE}.
///
/// @param processId identificador plausível fixo (o Horizon real atribui IDs sequenciais a
///                   partir de um valor reservado para processos do sistema; sem um segundo
///                   processo para comparar, `0` é o placeholder documentado por
///                   `svcGetProcessId`)
public record ProcessObject(int processId) implements KernelObject {
    @Override
    public String debugName() {
        return "Process#" + processId;
    }
}
