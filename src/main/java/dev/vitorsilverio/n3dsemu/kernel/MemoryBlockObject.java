package dev.vitorsilverio.n3dsemu.kernel;

/// `svcCreateMemoryBlock` (RFC-N3DSEMU G2 PR2 — "necessário para o `gsp` na G3", per a task).
/// Sem MMU (RFC D2), `svcMapMemoryBlock`/`svcUnmapMemoryBlock` são só validação de handle —
/// não há aliasing real de memória compartilhada entre processos nesta HLE (haveria só um
/// processo mesmo, RFC D1).
///
/// @param address          endereço da região já alocada pelo dono (linear heap, tipicamente)
/// @param size             tamanho em bytes
/// @param ownerPermission  {@link MemoryPermission} do processo dono
/// @param otherPermission  {@link MemoryPermission} de quem mapear a partir de outro processo
public record MemoryBlockObject(int address, int size, int ownerPermission, int otherPermission)
        implements KernelObject {
    @Override
    public String debugName() {
        return "MemoryBlock@0x" + Integer.toHexString(address);
    }
}
