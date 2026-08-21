package dev.vitorsilverio.n3dsemu.gpu.tev;

import dev.vitorsilverio.n3dsemu.gpu.PicaRegisters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// Configuração dos 6 estágios TEV (*Texture Environment*) do PICA200 (RFC-N3DSEMU G5), mais o
/// teste de alpha — decodificada dos registradores internos `GPUREG_TEXENV0`-`GPUREG_TEXENV5` (<a
/// href="https://www.3dbrew.org/wiki/GPU/Internal_Registers">3dbrew: GPU/Internal Registers</a>,
/// seção "TEV Stages") e de `GPUREG_FRAGOP_ALPHA_TEST`.
///
/// Cada estágio combina até 3 fontes de cor + 3 de alpha (cada uma com um **operando** próprio,
/// que seleciona/inverte canal) numa operação de combinação, escalada por um multiplicador.
/// `TevGlslGenerator` traduz isto para GLSL — esta classe só decodifica o estado.
///
/// **É a CHAVE do cache de shader** (RFC/task G5: "um `HashMap<TevConfig, VkShaderModule>`
/// resolve"), então todo campo tem que participar de `equals`/`hashCode` de verdade: por isso
/// `List` em vez de array — um record com array compara por IDENTIDADE e faria o cache errar
/// sempre, recompilando o mesmo shader a cada quadro.
public record TevConfig(List<Stage> stages, int bufferColorRgba8, int updateBufferConfig, AlphaTest alphaTest) {
    private static final int NUM_STAGES = 6;
    /// Offsets do primeiro registrador (`SOURCE`) de cada estágio — os 4 primeiros são
    /// consecutivos de 8 em 8 words, mas os estágios 4/5 pulam o bloco de
    /// `GPUREG_TEXENV_UPDATE_BUFFER`/`GPUREG_TEXENV_BUFFER_COLOR` (3dbrew: tabela "TEV Stages").
    private static final int[] STAGE_SOURCE_BASE = {0x0C0, 0x0C8, 0x0D0, 0x0D8, 0x0F0, 0x0F8};
    private static final int REG_UPDATE_BUFFER = 0x0E0;
    private static final int REG_BUFFER_COLOR = 0x0FD;
    /// `GPUREG_FRAGOP_ALPHA_TEST`: bit 0 = habilitado, bits 4-6 = função, bits 8-15 = referência.
    private static final int REG_ALPHA_TEST = 0x104;

    private static final int OFFSET_OPERAND = 1;
    private static final int OFFSET_COMBINER = 2;
    private static final int OFFSET_COLOR = 3;
    private static final int OFFSET_SCALE = 4;

    /// Quais estágios podem alimentar o *buffer* de cor combinada: só os 4 primeiros (3dbrew /
    /// Citra `TevStageUpdatesCombinerBufferColor`).
    public static final int MAX_BUFFER_FEEDING_STAGES = 4;
    private static final int UPDATE_BUFFER_RGB_SHIFT = 8;
    private static final int UPDATE_BUFFER_ALPHA_SHIFT = 12;

    /// Fontes de um estágio TEV (3dbrew: "TEV sources"). `PRIMARY_COLOR` é a cor interpolada do
    /// vértice (o único valor que `simple_tri` usa — sem textura).
    public enum Source {
        PRIMARY_COLOR, FRAGMENT_PRIMARY_COLOR, FRAGMENT_SECONDARY_COLOR, TEXTURE0, TEXTURE1, TEXTURE2, TEXTURE3,
        UNUSED_7, UNUSED_8, UNUSED_9, UNUSED_A, UNUSED_B, UNUSED_C, PREVIOUS_BUFFER, CONSTANT, PREVIOUS;

        static Source decode(int code) {
            Source[] values = values();
            return code >= 0 && code < values.length ? values[code] : PREVIOUS;
        }
    }

    /// Operação de combinação de um estágio (3dbrew: "TEV combiner operations").
    public enum CombinerOp {
        REPLACE, MODULATE, ADD, ADD_SIGNED, INTERPOLATE, SUBTRACT, DOT3, MULTIPLY_ADD, ADD_MULTIPLY;

        static CombinerOp decode(int code) {
            CombinerOp[] values = values();
            return code >= 0 && code < values.length ? values[code] : REPLACE;
        }
    }

    /// Comparação do teste de alpha (3dbrew `GPUREG_FRAGOP_ALPHA_TEST`, mesma ordem do
    /// `FramebufferRegs::CompareFunc` do Citra).
    public enum CompareFunc {
        NEVER, ALWAYS, EQUAL, NOT_EQUAL, LESS, LESS_OR_EQUAL, GREATER, GREATER_OR_EQUAL;

        static CompareFunc decode(int code) {
            CompareFunc[] values = values();
            return code >= 0 && code < values.length ? values[code] : ALWAYS;
        }
    }

    /// Teste de alpha: descarta o fragmento cujo alpha não satisfaz `function(alpha, reference)`.
    /// Faz parte da chave do cache porque muda o CÓDIGO do shader (um `discard`), não um estado
    /// dinâmico do pipeline.
    public record AlphaTest(boolean enabled, CompareFunc function, int reference) {
        public static final AlphaTest DISABLED = new AlphaTest(false, CompareFunc.ALWAYS, 0);
    }

    /// Um estágio TEV completo. `colorOperand`/`alphaOperand` são os **modificadores** de cada
    /// fonte (selecionam um canal e/ou o complemento `1-x`) — ignorá-los é a armadilha explícita
    /// da task G5 ("faz tudo quase funcionar e nada ficar certo").
    public record Stage(List<Source> colorSource, List<Integer> colorOperand, CombinerOp colorCombine,
                         List<Source> alphaSource, List<Integer> alphaOperand, CombinerOp alphaCombine,
                         int colorScaleShift, int alphaScaleShift, int constantColorRgba8) {
    }

    /// Decodifica os 6 estágios + o *buffer* de cor combinada + o teste de alpha a partir do banco
    /// de registradores bruto (RFC/task: "views tipadas quando os consumidores precisarem delas" —
    /// `PicaRegisters` continua sem saber nada de TEV).
    public static TevConfig decode(PicaRegisters registers) {
        List<Stage> stages = new ArrayList<>(NUM_STAGES);
        for (int i = 0; i < NUM_STAGES; i++) {
            stages.add(decodeStage(registers, STAGE_SOURCE_BASE[i]));
        }
        return new TevConfig(Collections.unmodifiableList(stages), registers.read(REG_BUFFER_COLOR),
                registers.read(REG_UPDATE_BUFFER), decodeAlphaTest(registers.read(REG_ALPHA_TEST)));
    }

    private static AlphaTest decodeAlphaTest(int word) {
        boolean enabled = (word & 1) != 0;
        if (!enabled) {
            return AlphaTest.DISABLED;
        }
        return new AlphaTest(true, CompareFunc.decode(bits(word, 4, 3)), bits(word, 8, 8));
    }

    private static Stage decodeStage(PicaRegisters registers, int base) {
        int source = registers.read(base);
        int operand = registers.read(base + OFFSET_OPERAND);
        int combiner = registers.read(base + OFFSET_COMBINER);
        int scale = registers.read(base + OFFSET_SCALE);

        List<Source> colorSource = List.of(
                Source.decode(bits(source, 0, 4)), Source.decode(bits(source, 4, 4)), Source.decode(bits(source, 8, 4)));
        List<Source> alphaSource = List.of(
                Source.decode(bits(source, 16, 4)), Source.decode(bits(source, 20, 4)), Source.decode(bits(source, 24, 4)));
        List<Integer> colorOperand = List.of(bits(operand, 0, 4), bits(operand, 4, 4), bits(operand, 8, 4));
        List<Integer> alphaOperand = List.of(bits(operand, 16, 3), bits(operand, 20, 3), bits(operand, 24, 3));

        return new Stage(colorSource, colorOperand, CombinerOp.decode(bits(combiner, 0, 4)),
                alphaSource, alphaOperand, CombinerOp.decode(bits(combiner, 16, 4)),
                bits(scale, 0, 2), bits(scale, 16, 2), registers.read(base + OFFSET_COLOR));
    }

    /// `true` se o estágio `stageIndex` grava sua saída de COR no *buffer* de cor combinada
    /// (Citra `TevStageUpdatesCombinerBufferColor`).
    public boolean stageUpdatesBufferColor(int stageIndex) {
        return stageIndex < MAX_BUFFER_FEEDING_STAGES
                && ((updateBufferConfig >>> UPDATE_BUFFER_RGB_SHIFT) & (1 << stageIndex)) != 0;
    }

    /// Como {@link #stageUpdatesBufferColor}, para o canal alpha.
    public boolean stageUpdatesBufferAlpha(int stageIndex) {
        return stageIndex < MAX_BUFFER_FEEDING_STAGES
                && ((updateBufferConfig >>> UPDATE_BUFFER_ALPHA_SHIFT) & (1 << stageIndex)) != 0;
    }

    /// Configuração equivalente ao que `simple_tri` programa: um único estágio `Replace` sobre
    /// `PRIMARY_COLOR` (os outros 5 ficam em `Replace(Previous)`, que não mexe na cor recebida) —
    /// a cor interpolada do vértice passa direto, sem textura nem combinação. Usada pelos testes e
    /// como referência da configuração mais comum.
    public static TevConfig passthroughPrimaryColor() {
        Stage passthroughColor = passthrough(Source.PRIMARY_COLOR);
        Stage passthroughPrevious = passthrough(Source.PREVIOUS);
        return new TevConfig(List.of(passthroughColor, passthroughPrevious, passthroughPrevious,
                passthroughPrevious, passthroughPrevious, passthroughPrevious), 0, 0, AlphaTest.DISABLED);
    }

    private static Stage passthrough(Source source) {
        List<Source> threeTimes = List.of(source, source, source);
        List<Integer> noModifier = List.of(0, 0, 0);
        return new Stage(threeTimes, noModifier, CombinerOp.REPLACE, threeTimes, noModifier, CombinerOp.REPLACE,
                0, 0, 0);
    }

    private static int bits(int word, int lowestBit, int width) {
        return (word >>> lowestBit) & ((1 << width) - 1);
    }
}
