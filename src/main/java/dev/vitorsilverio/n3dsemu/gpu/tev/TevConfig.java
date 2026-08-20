package dev.vitorsilverio.n3dsemu.gpu.tev;

import dev.vitorsilverio.n3dsemu.gpu.PicaRegisters;

/// Configuração dos 6 estágios TEV (*Texture Environment*) do PICA200 (RFC-N3DSEMU G5/PR3),
/// decodificada dos registradores internos `GPUREG_TEXENV0`-`GPUREG_TEXENV5` (<a
/// href="https://www.3dbrew.org/wiki/GPU/Internal_Registers">3dbrew: GPU/Internal Registers</a>,
/// seção "TEV Stages" — offsets confirmados nesta sessão via `WebFetch` da tabela real da wiki).
///
/// Cada estágio combina até 3 fontes de cor + 3 de alpha (com operando/modificador próprio) numa
/// operação de combinação, escalada por um multiplicador. `TevGlslGenerator` traduz isto para
/// GLSL — esta classe só decodifica o estado, não gera nada.
public record TevConfig(Stage[] stages, int bufferColorRgba8, int updateBufferConfig) {
    private static final int NUM_STAGES = 6;
    /// Offsets do primeiro registrador (`SOURCE`) de cada estágio — os 4 primeiros são
    /// consecutivos de 8 em 8 words, mas os estágios 4/5 pulam o bloco de
    /// `GPUREG_TEXENV_UPDATE_BUFFER`/`GPUREG_TEXENV_BUFFER_COLOR` (3dbrew: tabela "TEV Stages").
    private static final int[] STAGE_SOURCE_BASE = {0x0C0, 0x0C8, 0x0D0, 0x0D8, 0x0F0, 0x0F8};
    private static final int REG_UPDATE_BUFFER = 0x0E0;
    private static final int REG_BUFFER_COLOR = 0x0FD;

    private static final int OFFSET_OPERAND = 1;
    private static final int OFFSET_COMBINER = 2;
    private static final int OFFSET_COLOR = 3;
    private static final int OFFSET_SCALE = 4;

    /// Fontes de um estágio TEV (3dbrew: "TEV sources"). `PRIMARY_COLOR` é a cor interpolada do
    /// vértice (o único valor que `simple_tri` usa — sem textura, RFC/task).
    public enum Source {
        PRIMARY_COLOR, FRAGMENT_PRIMARY_COLOR, FRAGMENT_SECONDARY_COLOR, TEXTURE0, TEXTURE1, TEXTURE2, TEXTURE3,
        UNUSED_7, UNUSED_8, UNUSED_9, UNUSED_A, UNUSED_B, UNUSED_C, PREVIOUS_BUFFER, CONSTANT, PREVIOUS;

        static Source decode(int code) {
            Source[] values = values();
            return code >= 0 && code < values.length ? values[code] : PREVIOUS;
        }
    }

    /// Operação de combinação de um estágio (3dbrew: "TEV combiner operations" — a lista
    /// transcrita na task G5: Replace/Modulate/Add/AddSigned/Interpolate/Subtract/Dot3/MultAdd/
    /// AddMult).
    public enum CombinerOp {
        REPLACE, MODULATE, ADD, ADD_SIGNED, INTERPOLATE, SUBTRACT, DOT3, MULTIPLY_ADD, ADD_MULTIPLY;

        static CombinerOp decode(int code) {
            CombinerOp[] values = values();
            return code >= 0 && code < values.length ? values[code] : REPLACE;
        }
    }

    /// Um estágio TEV completo: 3 fontes + operando de cor, 3 fontes + operando de alpha, a
    /// operação de combinação de cada canal e o multiplicador de escala (3dbrew: `0`=1x,`1`=2x,
    /// `2`=4x).
    public record Stage(Source[] colorSource, int[] colorOperand, CombinerOp colorCombine,
                         Source[] alphaSource, int[] alphaOperand, CombinerOp alphaCombine,
                         int colorScaleShift, int alphaScaleShift) {
    }

    /// Decodifica os 6 estágios + o *buffer* de cor combinada a partir do banco de registradores
    /// bruto (RFC/task: "views tipadas quando os consumidores precisarem delas" — `PicaRegisters`
    /// continua sem saber nada de TEV).
    public static TevConfig decode(PicaRegisters registers) {
        Stage[] stages = new Stage[NUM_STAGES];
        for (int i = 0; i < NUM_STAGES; i++) {
            stages[i] = decodeStage(registers, STAGE_SOURCE_BASE[i]);
        }
        return new TevConfig(stages, registers.read(REG_BUFFER_COLOR), registers.read(REG_UPDATE_BUFFER));
    }

    private static Stage decodeStage(PicaRegisters registers, int base) {
        int source = registers.read(base);
        int operand = registers.read(base + OFFSET_OPERAND);
        int combiner = registers.read(base + OFFSET_COMBINER);
        int scale = registers.read(base + OFFSET_SCALE);

        Source[] colorSource = {
                Source.decode(bits(source, 0, 4)), Source.decode(bits(source, 4, 4)), Source.decode(bits(source, 8, 4))};
        Source[] alphaSource = {
                Source.decode(bits(source, 16, 4)), Source.decode(bits(source, 20, 4)), Source.decode(bits(source, 24, 4))};
        int[] colorOperand = {bits(operand, 0, 4), bits(operand, 4, 4), bits(operand, 8, 4)};
        int[] alphaOperand = {bits(operand, 16, 3), bits(operand, 20, 3), bits(operand, 24, 3)};

        CombinerOp colorCombine = CombinerOp.decode(bits(combiner, 0, 4));
        CombinerOp alphaCombine = CombinerOp.decode(bits(combiner, 16, 4));
        int colorScaleShift = bits(scale, 0, 2);
        int alphaScaleShift = bits(scale, 16, 2);

        return new Stage(colorSource, colorOperand, colorCombine, alphaSource, alphaOperand, alphaCombine,
                colorScaleShift, alphaScaleShift);
    }

    /// Configuração equivalente ao que `simple_tri` programa: um único estágio (os outros 5 ficam
    /// em `Replace(Previous)`, que é transparente — "não mexe" na cor recebida), `Replace` sobre
    /// `PRIMARY_COLOR` — a cor interpolada do vértice passa direto, sem textura nem combinação
    /// (RFC/task: "o `simple_tri` não usa textura nenhuma"). Usado pelos testes desta PR e como
    /// referência da configuração mais comum.
    public static TevConfig passthroughPrimaryColor() {
        Stage passthroughColor = new Stage(
                new Source[]{Source.PRIMARY_COLOR, Source.PRIMARY_COLOR, Source.PRIMARY_COLOR}, new int[]{0, 0, 0},
                CombinerOp.REPLACE,
                new Source[]{Source.PRIMARY_COLOR, Source.PRIMARY_COLOR, Source.PRIMARY_COLOR}, new int[]{0, 0, 0},
                CombinerOp.REPLACE, 0, 0);
        Stage passthroughPrevious = new Stage(
                new Source[]{Source.PREVIOUS, Source.PREVIOUS, Source.PREVIOUS}, new int[]{0, 0, 0}, CombinerOp.REPLACE,
                new Source[]{Source.PREVIOUS, Source.PREVIOUS, Source.PREVIOUS}, new int[]{0, 0, 0}, CombinerOp.REPLACE,
                0, 0);
        Stage[] stages = {passthroughColor, passthroughPrevious, passthroughPrevious, passthroughPrevious,
                passthroughPrevious, passthroughPrevious};
        return new TevConfig(stages, 0, 0);
    }

    private static int bits(int word, int lowestBit, int width) {
        return (word >>> lowestBit) & ((1 << width) - 1);
    }
}
