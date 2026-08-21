package dev.vitorsilverio.n3dsemu.gpu;

/// Mapa de saída do vertex shader (`GPUREG_SH_OUTMAP_TOTAL`/`_O0`-`_O6`) — RFC-N3DSEMU G5/PR4.
///
/// O PICA200 não tem uma convenção fixa de "registrador de saída 0 = posição". Cada um dos até 7
/// registradores de saída (`o0`-`o6`) carrega **um byte de semântica por componente**: a lista de
/// comandos declara, componente a componente, para onde aquele valor vai (posição, cor,
/// coordenada de textura, quaternion, vetor de vista). A PR3 assumia a convenção do
/// `picasso`/`citro3d` (`o0`=posição, `o1`=cor) — funciona para o `simple_tri` mas não dá acesso
/// às **coordenadas de textura**, que vivem em semânticas próprias e podem sair de qualquer
/// registrador.
///
/// Índices de semântica transcritos do `RasterizerRegs::VSOutputAttributes::Semantic` do Citra
/// (`video_core/pica/regs_rasterizer.h`). {@value #SEMANTIC_INVALID} marca um componente que não
/// deve ser entregue a lugar nenhum.
public final class OutputMap {
    /// `GPUREG_SH_OUTMAP_TOTAL` — quantos registradores de saída o shader usa (bits 0-2).
    public static final int REG_TOTAL = 0x04F;
    /// `GPUREG_SH_OUTMAP_O0` — os 7 registradores são consecutivos.
    public static final int REG_FIRST = 0x050;
    public static final int MAX_OUTPUT_REGISTERS = 7;

    public static final int SEMANTIC_POSITION_X = 0;
    public static final int SEMANTIC_COLOR_R = 8;
    public static final int SEMANTIC_TEXCOORD0_U = 12;
    public static final int SEMANTIC_TEXCOORD1_U = 14;
    public static final int SEMANTIC_TEXCOORD2_U = 22;
    /// Nenhuma semântica: o componente é descartado.
    public static final int SEMANTIC_INVALID = 31;
    /// Quantas semânticas existem (`0`-`23`; `31` é o marcador de "inválido", não um slot).
    public static final int SEMANTIC_COUNT = 24;

    private static final int TOTAL_MASK = 0x7;
    private static final int COMPONENTS_PER_REGISTER = 4;
    private static final int SEMANTIC_MASK = 0x1F;
    private static final int BITS_PER_COMPONENT = 8;

    /// `semanticOf[outputRegister][component]`.
    private final int[][] semanticOf;
    private final int usedRegisters;

    private OutputMap(int[][] semanticOf, int usedRegisters) {
        this.semanticOf = semanticOf;
        this.usedRegisters = usedRegisters;
    }

    public static OutputMap decode(PicaRegisters registers) {
        int usedRegisters = Math.min((registers.read(REG_TOTAL) & TOTAL_MASK) + 1, MAX_OUTPUT_REGISTERS);
        int[][] semanticOf = new int[MAX_OUTPUT_REGISTERS][COMPONENTS_PER_REGISTER];
        for (int outputRegister = 0; outputRegister < MAX_OUTPUT_REGISTERS; outputRegister++) {
            int word = registers.read(REG_FIRST + outputRegister);
            for (int component = 0; component < COMPONENTS_PER_REGISTER; component++) {
                semanticOf[outputRegister][component] =
                        (word >>> (component * BITS_PER_COMPONENT)) & SEMANTIC_MASK;
            }
        }
        return new OutputMap(semanticOf, usedRegisters);
    }

    public int usedRegisters() {
        return usedRegisters;
    }

    public int semanticOf(int outputRegister, int component) {
        return semanticOf[outputRegister][component];
    }

    /// Redistribui a saída crua do vertex shader (`float[registrador][componente]`) num vetor
    /// indexado por SEMÂNTICA — é essa a forma que o rasterizador consome. Semânticas não
    /// escritas pelo shader ficam `0`, exceto `POSITION_W`, que fica `1`: um shader que não
    /// declare o `w` está projetando em espaço afim, e dividir por `0` produziria `NaN`.
    public float[] gather(float[][] shaderOutput) {
        float[] bySemantic = new float[SEMANTIC_COUNT];
        bySemantic[SEMANTIC_POSITION_X + 3] = 1f;
        for (int outputRegister = 0; outputRegister < usedRegisters; outputRegister++) {
            for (int component = 0; component < COMPONENTS_PER_REGISTER; component++) {
                int semantic = semanticOf[outputRegister][component];
                if (semantic < SEMANTIC_COUNT) {
                    bySemantic[semantic] = shaderOutput[outputRegister][component];
                }
            }
        }
        return bySemantic;
    }

    /// `true` se nenhum registrador declara semântica de posição — sinal de que a lista de
    /// comandos ainda não programou o mapa (estado inicial do banco de registradores, tudo zero,
    /// em que TODO componente diria "POSITION_X"). Nesse caso o chamador cai na convenção do
    /// `picasso` (`o0`=posição, `o1`=cor), que é o que a PR3 assumia.
    public boolean isUnprogrammed() {
        for (int outputRegister = 0; outputRegister < MAX_OUTPUT_REGISTERS; outputRegister++) {
            for (int component = 0; component < COMPONENTS_PER_REGISTER; component++) {
                if (semanticOf[outputRegister][component] != 0) {
                    return false;
                }
            }
        }
        return true;
    }
}
