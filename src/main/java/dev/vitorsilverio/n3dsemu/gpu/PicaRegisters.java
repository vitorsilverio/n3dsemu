package dev.vitorsilverio.n3dsemu.gpu;

/// Banco de registradores **internos** do PICA200 (`0x0000`-`0x0FFF`, RFC-N3DSEMU G5/D5) —
/// tudo que uma lista de comandos ({@link CommandListParser}) escreve. **Não** confundir com os
/// registradores **externos** (`0x1EF00000`, `gsp::WriteHWRegs`, framebuffer/LCD — já tratados na
/// G3/G4, ver Javadoc de {@link dev.vitorsilverio.n3dsemu.service.GspGpuService}): são dois
/// espaços de endereçamento distintos (armadilha explícita da task G5).
///
/// Escritas respeitam a **máscara de byte** do cabeçalho da lista de comandos: só os bytes
/// marcados na máscara são substituídos, os demais preservam o valor anterior do registrador
/// (leitura-modificação-escrita real de hardware — <a
/// href="https://www.3dbrew.org/wiki/GPU/Internal_Registers">3dbrew: GPU/Internal Registers</a>).
///
/// Só o banco bruto + os registradores de **disparo de desenho** (o único efeito observável sem
/// nenhuma outra peça da G5 — vertex shader/TEV/Vulkan vêm nos PRs seguintes) são modelados nesta
/// PR1; formato de vértice/TEV/texturas ganham views tipadas quando os consumidores (PR2/PR3)
/// precisarem delas — não há por que decodificar um campo antes de ter quem o leia.
public final class PicaRegisters {
    /// Tamanho do espaço de registradores internos (3dbrew: `0x0000`-`0x0FFF`, todos `u32`).
    public static final int REGISTER_COUNT = 0x1000;

    /// `GPUREG_FINALIZE` (3dbrew: "value written doesn't matter") — sinaliza o fim de uma lista
    /// de comandos.
    public static final int FINALIZE = 0x010;
    /// `GPUREG_DRAWARRAYS` — dispara `DrawArrays` (vértices lidos sequencialmente, sem índice).
    public static final int DRAW_ARRAYS = 0x22E;
    /// `GPUREG_DRAWELEMENTS` — dispara `DrawElements` (vértices lidos por um array de índices).
    public static final int DRAW_ELEMENTS = 0x22F;

    private static final int BYTE_MASK_BITS = 4;
    private static final int BITS_PER_BYTE = 8;
    private static final int FULL_BYTE = 0xFF;

    private final int[] registers = new int[REGISTER_COUNT];
    private long drawArraysTriggerCount;
    private long drawElementsTriggerCount;
    private long finalizeCount;

    /// Aplica uma escrita mascarada por byte (RFC/3dbrew: bits 16-19 do cabeçalho da lista de
    /// comandos). `mask` usa só os 4 bits baixos, um por byte de `value` (bit 0 = byte menos
    /// significativo).
    public void write(int registerId, int value, int byteMask) {
        int index = requireValidIndex(registerId);
        int previous = registers[index];
        int merged = previous;
        for (int byteIndex = 0; byteIndex < BYTE_MASK_BITS; byteIndex++) {
            if ((byteMask & (1 << byteIndex)) == 0) {
                continue;
            }
            int shift = byteIndex * BITS_PER_BYTE;
            merged = (merged & ~(FULL_BYTE << shift)) | (value & (FULL_BYTE << shift));
        }
        registers[index] = merged;
        countTrigger(index);
    }

    private void countTrigger(int index) {
        switch (index) {
            case FINALIZE -> finalizeCount++;
            case DRAW_ARRAYS -> drawArraysTriggerCount++;
            case DRAW_ELEMENTS -> drawElementsTriggerCount++;
            default -> { }
        }
    }

    public int read(int registerId) {
        return registers[requireValidIndex(registerId)];
    }

    private static int requireValidIndex(int registerId) {
        if (registerId < 0 || registerId >= REGISTER_COUNT) {
            throw new IllegalArgumentException("registrador PICA200 fora do intervalo: 0x"
                    + Integer.toHexString(registerId));
        }
        return registerId;
    }

    /// Quantas vezes {@link #DRAW_ARRAYS} foi escrito desde a criação — cada escrita é um
    /// pedido de desenho real de hardware (3dbrew não documenta o valor escrito como relevante).
    public long drawArraysTriggerCount() {
        return drawArraysTriggerCount;
    }

    public long drawElementsTriggerCount() {
        return drawElementsTriggerCount;
    }

    /// Quantas vezes {@link #FINALIZE} foi escrito — marca o fim de listas de comando
    /// processadas.
    public long finalizeCount() {
        return finalizeCount;
    }
}
