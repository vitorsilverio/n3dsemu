package dev.vitorsilverio.n3dsemu.gpu;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

/// Decodifica os registradores de **formato de vértice** do PICA200 (`0x200`-`0x227`,
/// RFC-N3DSEMU G5/D5) e lê os atributos de um vértice a partir da memória do guest — mesmo
/// algoritmo do `Pica::VertexLoader` do Citra (`video_core/pica/regs_pipeline.h`/
/// `vertex_loader.h`, **fonte real conferida nesta sessão** via GitHub, não invenção).
///
/// Layout confirmado (Citra `PipelineRegs`, campo a campo, e cruzado com as constantes já
/// existentes em {@link PicaRegisters}: `DRAW_ARRAYS`/`DRAW_ELEMENTS` batem exatamente com o
/// offset calculado a partir de `0x200` + o tamanho de cada campo do struct real):
/// `0x200`=endereço base (bits 1-28, `*16` = endereço físico), `0x201`/`0x202`=formato (2 bits
/// tipo + 2 bits tamanho-1 por atributo, 12 atributos), `0x202` também traz `attribute_mask`
/// (bits 16-27) e `max_attribute_index` (bits 28-31), `0x203`-`0x226`=12 *loaders* de 3 words
/// cada (offset de dados + até 12 nibbles de componente + contagem de bytes por vértice),
/// `0x227`=array de índices (offset + formato 8/16 bits), `0x228`=contagem de vértices,
/// `0x22A`=offset inicial de vértice.
public final class VertexAttributeLoader {
    public static final int NUM_ATTRIBUTES = 12;

    private static final int REG_BASE_ADDRESS = 0x200;
    private static final int REG_FORMAT_LOW = 0x201;
    private static final int REG_FORMAT_HIGH = 0x202;
    private static final int REG_LOADER_BASE = 0x203;
    private static final int LOADER_STRIDE_WORDS = 3;
    private static final int REG_INDEX_ARRAY = 0x227;
    private static final int REG_NUM_VERTICES = 0x228;
    private static final int REG_VERTEX_OFFSET = 0x22A;

    /// 3dbrew/Citra: índice de componente >= 12 dentro de um *loader* não referencia um
    /// atributo — os valores 12-15 pulam `(n-11)` bytes de preenchimento no registro do vértice.
    private static final int PADDING_COMPONENT_BASE = 12;

    public enum Format {BYTE, UBYTE, SHORT, FLOAT}

    private final PicaRegisters registers;

    public VertexAttributeLoader(PicaRegisters registers) {
        this.registers = registers;
    }

    public long baseAddress() {
        long base = (registers.read(REG_BASE_ADDRESS) >>> 1) & 0xFFFFFFFL;
        return base * 16L;
    }

    public Format format(int attributeId) {
        int reg = attributeId < 8 ? registers.read(REG_FORMAT_LOW) : registers.read(REG_FORMAT_HIGH);
        int shift = (attributeId % 8) * 4;
        int code = (reg >>> shift) & 0x3;
        return switch (code) {
            case 0 -> Format.BYTE;
            case 1 -> Format.UBYTE;
            case 2 -> Format.SHORT;
            default -> Format.FLOAT;
        };
    }

    public int numComponents(int attributeId) {
        int reg = attributeId < 8 ? registers.read(REG_FORMAT_LOW) : registers.read(REG_FORMAT_HIGH);
        int shift = (attributeId % 8) * 4 + 2;
        return ((reg >>> shift) & 0x3) + 1;
    }

    public int elementSizeBytes(int attributeId) {
        return switch (format(attributeId)) {
            case FLOAT -> 4;
            case SHORT -> 2;
            case BYTE, UBYTE -> 1;
        };
    }

    public int attributeStride(int attributeId) {
        return numComponents(attributeId) * elementSizeBytes(attributeId);
    }

    public int numVertices() {
        return registers.read(REG_NUM_VERTICES);
    }

    public int vertexOffset() {
        return registers.read(REG_VERTEX_OFFSET);
    }

    public boolean isEightBitIndex() {
        int reg = registers.read(REG_INDEX_ARRAY);
        return ((reg >>> 31) & 1) == 0;
    }

    public long indexArrayAddress() {
        int reg = registers.read(REG_INDEX_ARRAY);
        return baseAddress() + (reg & 0xFFFFFFFL);
    }

    /// Lê o índice de vértice `n` (0-based) do array de índices (`DrawElements`).
    public int readIndex(AddressSpace memory, int n) {
        long addr = indexArrayAddress();
        if (isEightBitIndex()) {
            return memory.read8((int) (addr + n));
        }
        return memory.read16((int) (addr + 2L * n));
    }

    /// Endereço-base de cada *loader* dentro da memória do guest + a que atributo cada
    /// componente do registro do vértice pertence (mesma decodificação de
    /// `Pica::VertexLoader`, construtor): resultado indexado por `attributeId` (0-11).
    private record LoaderMap(long[] sourceAddress, int[] vertexStride) {
    }

    private LoaderMap buildLoaderMap() {
        long[] sourceAddress = new long[NUM_ATTRIBUTES];
        int[] vertexStride = new int[NUM_ATTRIBUTES];
        long base = baseAddress();
        for (int loaderIndex = 0; loaderIndex < NUM_ATTRIBUTES; loaderIndex++) {
            int loaderReg = REG_LOADER_BASE + loaderIndex * LOADER_STRIDE_WORDS;
            int dataOffset = registers.read(loaderReg) & 0xFFFFFFF;
            int compWord1 = registers.read(loaderReg + 1);
            int compWord2 = registers.read(loaderReg + 2);
            int byteCount = (compWord2 >>> 16) & 0xFF;
            int componentCount = (compWord2 >>> 28) & 0xF;

            int runningOffset = 0;
            for (int slot = 0; slot < componentCount; slot++) {
                int component = slot < 8 ? (compWord1 >>> (slot * 4)) & 0xF : (compWord2 >>> ((slot - 8) * 4)) & 0xF;
                if (component < PADDING_COMPONENT_BASE) {
                    sourceAddress[component] = base + dataOffset + runningOffset;
                    vertexStride[component] = byteCount;
                    runningOffset += attributeStride(component);
                } else {
                    runningOffset += component - (PADDING_COMPONENT_BASE - 1);
                }
            }
        }
        return new LoaderMap(sourceAddress, vertexStride);
    }

    /// Lê os {@value #NUM_ATTRIBUTES} atributos do vértice de índice `vertexIndex` (já resolvido
    /// — sequencial em `DrawArrays`, via array de índices em `DrawElements`) na convenção
    /// `float[4]` da ISA de shader (componentes ausentes ficam `0`, exceto o `w` implícito de
    /// posições que o PRÓPRIO shader decide — este loader não assume nada sobre semântica).
    public float[][] load(AddressSpace memory, int vertexIndex) {
        LoaderMap map = buildLoaderMap();
        float[][] attributes = new float[NUM_ATTRIBUTES][4];
        for (int attributeId = 0; attributeId < NUM_ATTRIBUTES; attributeId++) {
            if (map.vertexStride[attributeId] == 0) {
                continue; // atributo sem loader associado (não usado por este formato de vértice)
            }
            long address = map.sourceAddress[attributeId] + (long) map.vertexStride[attributeId] * vertexIndex;
            int components = numComponents(attributeId);
            Format format = format(attributeId);
            for (int component = 0; component < components; component++) {
                attributes[attributeId][component] = readComponent(memory, format,
                        (int) (address + (long) component * elementSizeBytes(attributeId)));
            }
        }
        return attributes;
    }

    private static float readComponent(AddressSpace memory, Format format, int address) {
        return switch (format) {
            case FLOAT -> Float.intBitsToFloat(memory.read32(address));
            case SHORT -> (short) memory.read16(address);
            case UBYTE -> memory.read8(address) & 0xFF;
            case BYTE -> (byte) memory.read8(address);
        };
    }
}
