package dev.vitorsilverio.n3dsemu.memory;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

import java.io.PrintStream;

/// Barramento aberto: qualquer endereço fora das regiões conhecidas do mapa (RFC §3) cai
/// aqui. Loga e devolve `0` na leitura, loga na escrita — a principal ferramenta de
/// diagnóstico do marco M1 (task G1), já que nenhum acesso à memória do guest deveria cair
/// fora do mapa conhecido num homebrew `.3dsx` bem-comportado.
public final class LoggingOpenBus implements AddressSpace {
    private final PrintStream log;

    public LoggingOpenBus(PrintStream log) {
        this.log = log;
    }

    @Override
    public int read8(int address) {
        log.printf("[openbus] read8  0x%08X%n", address);
        return 0;
    }

    @Override
    public int read16(int address) {
        log.printf("[openbus] read16 0x%08X%n", address);
        return 0;
    }

    @Override
    public int read32(int address) {
        log.printf("[openbus] read32 0x%08X%n", address);
        return 0;
    }

    @Override
    public void write8(int address, int value) {
        log.printf("[openbus] write8  0x%08X = 0x%02X%n", address, value & 0xFF);
    }

    @Override
    public void write16(int address, int value) {
        log.printf("[openbus] write16 0x%08X = 0x%04X%n", address, value & 0xFFFF);
    }

    @Override
    public void write32(int address, int value) {
        log.printf("[openbus] write32 0x%08X = 0x%08X%n", address, value);
    }

    @Override
    public boolean providesAccessCycles() {
        return false;
    }
}
