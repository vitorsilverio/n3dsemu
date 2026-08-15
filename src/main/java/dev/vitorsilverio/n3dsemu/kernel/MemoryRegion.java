package dev.vitorsilverio.n3dsemu.kernel;

/// Uma faixa contígua do espaço de endereçamento com estado e permissão uniformes — a unidade
/// que {@link MemoryManager} rastreia para `svcQueryMemory` responder de verdade (RFC-N3DSEMU
/// G2: sem MMU, isto é pura contabilidade sobre memória já mapeada por
/// `dev.vitorsilverio.n3dsemu.memory.N3dsAddressSpace`, não um mapeamento de páginas de verdade).
///
/// @param base       endereço inicial (inclusive)
/// @param size       tamanho em bytes
/// @param state      um de {@link MemoryState}
/// @param permission máscara de {@link MemoryPermission}
public record MemoryRegion(int base, int size, int state, int permission) {
    /// Endereço final (exclusive).
    public int end() {
        return base + size;
    }

    public boolean contains(int address) {
        return Integer.compareUnsigned(address, base) >= 0 && Integer.compareUnsigned(address, end()) < 0;
    }
}
