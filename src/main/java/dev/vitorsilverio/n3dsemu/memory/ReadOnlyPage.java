package dev.vitorsilverio.n3dsemu.memory;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

/// Uma página de memória só-leitura (config memory, shared page): leituras vêm de um
/// `byte[]` preenchido na construção; escritas são ignoradas, como no hardware real (as duas
/// regiões do 3DS que usam esta classe são mapeadas `RO` pelo kernel para todo processo).
public final class ReadOnlyPage implements AddressSpace {
    private final int base;
    private final byte[] backing;

    public ReadOnlyPage(int base, byte[] backing) {
        this.base = base;
        this.backing = backing;
    }

    private int offset(int address) {
        return address - base;
    }

    @Override
    public int read8(int address) {
        return backing[offset(address)] & 0xFF;
    }

    @Override
    public int read16(int address) {
        int o = offset(address);
        return (backing[o] & 0xFF) | ((backing[o + 1] & 0xFF) << 8);
    }

    @Override
    public int read32(int address) {
        int o = offset(address);
        return (backing[o] & 0xFF)
                | ((backing[o + 1] & 0xFF) << 8)
                | ((backing[o + 2] & 0xFF) << 16)
                | ((backing[o + 3] & 0xFF) << 24);
    }

    @Override
    public void write8(int address, int value) {
        // Página real de hardware: escritas são descartadas silenciosamente.
    }

    @Override
    public void write16(int address, int value) {
    }

    @Override
    public void write32(int address, int value) {
    }

    @Override
    public boolean providesAccessCycles() {
        return false;
    }
}
