package dev.vitorsilverio.n3dsemu.gpu.shader;

import dev.vitorsilverio.n3dsemu.gpu.RegisterWriteListener;

import java.util.ArrayList;
import java.util.List;

/// Captura o upload de vertex shader por **registrador-FIFO** (RFC-N3DSEMU G5/PR3) — o caminho
/// REAL do hardware: `libctru`/`picasso` não mandam o `.shbin` inteiro de uma vez, a lista de
/// comandos escreve o código do programa, os *operand descriptors* e as constantes float um-a-um
/// nos registradores `GPUREG_VS_CODETRANSFER_*`/`GPUREG_VS_OPDESCS_*`/`GPUREG_VS_FLOATUNIFORM_*`
/// (offsets confirmados nesta sessão via `WebFetch` da tabela real do <a
/// href="https://www.3dbrew.org/wiki/GPU/Internal_Registers">3dbrew: GPU/Internal Registers</a>).
/// Esses registradores se comportam como **FIFO**: cada escrita empurra uma palavra e avança um
/// cursor interno, ao contrário do resto do banco (RFC/task: "`PicaRegisters` é só o banco bruto"
/// — por isso este observador vive fora dela, via {@link RegisterWriteListener}).
///
/// **Simplificação documentada desta PR**: o mapeamento *registrador de saída → semântica*
/// (`GPUREG_SH_OUTMAP_O0`-`O6`) não é decodificado — segue a convenção universal do
/// `picasso`/`citro3d` (`o0`=posição, `o1`=cor, a mesma que `simple_tri` usa e que os testes de
/// `ShaderBinary`/`VertexPipeline` das PRs anteriores já assumiam implicitamente). Decodificar a
/// tabela granular por componente (24 valores de semântica, um por componente de saída) fica para
/// quando um consumidor real precisar de uma ordem diferente.
///
/// **Uniforms float (G5.2)**: os DOIS modos de `GPUREG_VS_FLOATUNIFORM_CONFIG` são suportados —
/// float32 (bit 31 = 1, 4 palavras IEEE754 por constante) e float24 empacotado (3 palavras por
/// constante). Em ambos os casos **a ordem dos componentes é invertida** (`w`,`z`,`y`,`x`): a
/// primeira palavra do FIFO carrega o `w`. O `picasso` sobe as constantes embutidas do `.shbin` em
/// float24 e o `citro3d` sobe os uniforms do app em float32, então `simple_tri` exercita os dois
/// caminhos num único quadro.
public final class ShaderUpload implements RegisterWriteListener {
    /// `GPUREG_VS_BOOLUNIFORM`.
    public static final int REG_BOOL_UNIFORM = 0x2B0;
    /// `GPUREG_VS_INTUNIFORM_I0`-`I3` (4 registradores consecutivos).
    public static final int REG_INT_UNIFORM_BASE = 0x2B1;
    private static final int NUM_INT_UNIFORMS = 4;
    /// `GPUREG_VS_ENTRYPOINT`.
    public static final int REG_ENTRYPOINT = 0x2BA;
    private static final int ENTRYPOINT_OFFSET_MASK = 0xFFFF;
    /// `GPUREG_VS_ATTRIBUTES_PERMUTATION_LOW`/`HIGH`.
    public static final int REG_ATTRIBUTES_PERMUTATION_LOW = 0x2BB;
    public static final int REG_ATTRIBUTES_PERMUTATION_HIGH = 0x2BC;
    /// `GPUREG_VS_FLOATUNIFORM_CONFIG` (índice do registrador de destino + bit 31 = modo).
    public static final int REG_FLOAT_UNIFORM_CONFIG = 0x2C0;
    private static final int REG_FLOAT_UNIFORM_DATA_FIRST = 0x2C1;
    private static final int REG_FLOAT_UNIFORM_DATA_LAST = 0x2C8;
    private static final int FLOAT_UNIFORM_MODE_32_BIT = 1 << 31;
    private static final int WORDS_PER_FLOAT32_CONSTANT = 4;
    private static final int WORDS_PER_FLOAT24_CONSTANT = 3;
    /// `GPUREG_VS_CODETRANSFER_INDEX`.
    public static final int REG_CODETRANSFER_INDEX = 0x2CB;
    private static final int REG_CODETRANSFER_DATA_FIRST = 0x2CC;
    private static final int REG_CODETRANSFER_DATA_LAST = 0x2D3;
    /// `GPUREG_VS_OPDESCS_INDEX`.
    public static final int REG_OPDESCS_INDEX = 0x2D5;
    private static final int REG_OPDESCS_DATA_FIRST = 0x2D6;
    private static final int REG_OPDESCS_DATA_LAST = 0x2DD;

    /// Tamanho da memória de instrução do vertex shader do PICA200 (3dbrew: 512 words) e da
    /// tabela de *operand descriptors* (128 entradas) — limites reais de hardware, não escolhas
    /// arbitrárias desta implementação.
    private static final int PROGRAM_MEMORY_WORDS = 512;
    private static final int OPERAND_DESCRIPTOR_TABLE_SIZE = 128;

    private final int[] code = new int[PROGRAM_MEMORY_WORDS];
    private int codeCursor;
    private final int[] operandDescriptors = new int[OPERAND_DESCRIPTOR_TABLE_SIZE];
    private int opdescCursor;

    private final float[][] floatConstants = new float[VertexShaderInterpreter.NUM_FLOAT_CONSTANTS][4];
    private int floatUniformIndex;
    private boolean floatUniformMode32 = true;
    private final List<Integer> floatUniformPendingWords = new ArrayList<>(WORDS_PER_FLOAT32_CONSTANT);

    private final int[][] intConstants = new int[VertexShaderInterpreter.NUM_INT_CONSTANTS][4];
    private final boolean[] boolConstants = new boolean[VertexShaderInterpreter.NUM_BOOL_CONSTANTS];

    private int mainOffset;
    private final int[] attributeToInputRegister = new int[12];

    @Override
    public void onWrite(int registerId, int value, int byteMask) {
        if (registerId == REG_CODETRANSFER_INDEX) {
            codeCursor = value;
        } else if (registerId >= REG_CODETRANSFER_DATA_FIRST && registerId <= REG_CODETRANSFER_DATA_LAST) {
            if (codeCursor < code.length) {
                code[codeCursor] = value;
            }
            codeCursor++;
        } else if (registerId == REG_OPDESCS_INDEX) {
            opdescCursor = value;
        } else if (registerId >= REG_OPDESCS_DATA_FIRST && registerId <= REG_OPDESCS_DATA_LAST) {
            if (opdescCursor < operandDescriptors.length) {
                operandDescriptors[opdescCursor] = value;
            }
            opdescCursor++;
        } else if (registerId == REG_FLOAT_UNIFORM_CONFIG) {
            floatUniformIndex = value & 0x7F;
            floatUniformMode32 = (value & FLOAT_UNIFORM_MODE_32_BIT) != 0;
            floatUniformPendingWords.clear();
        } else if (registerId >= REG_FLOAT_UNIFORM_DATA_FIRST && registerId <= REG_FLOAT_UNIFORM_DATA_LAST) {
            onFloatUniformWord(value);
        } else if (registerId == REG_ENTRYPOINT) {
            // 3dbrew (`GPUREG_VS_ENTRYPOINT`): o valor escrito é `0x7FFF0000 | offset` — só os 16
            // bits BAIXOS são o ponto de entrada. Guardar a palavra inteira fazia o interpretador
            // começar a executar em `0x7FFF0000`, fora da memória de programa: o shader nunca
            // rodava e todo vértice saía zerado (achado real da G5.2).
            mainOffset = value & ENTRYPOINT_OFFSET_MASK;
        } else if (registerId == REG_ATTRIBUTES_PERMUTATION_LOW) {
            decodeAttributePermutation(value, 0, 8);
        } else if (registerId == REG_ATTRIBUTES_PERMUTATION_HIGH) {
            decodeAttributePermutation(value, 8, 4);
        } else if (registerId == REG_BOOL_UNIFORM) {
            for (int i = 0; i < boolConstants.length; i++) {
                boolConstants[i] = ((value >>> i) & 1) != 0;
            }
        } else if (registerId >= REG_INT_UNIFORM_BASE && registerId < REG_INT_UNIFORM_BASE + NUM_INT_UNIFORMS) {
            int constantIndex = registerId - REG_INT_UNIFORM_BASE;
            intConstants[constantIndex] = new int[]{value & 0xFF, (value >>> 8) & 0xFF, (value >>> 16) & 0xFF, (value >>> 24) & 0xFF};
        }
    }

    private void decodeAttributePermutation(int value, int firstAttribute, int count) {
        for (int slot = 0; slot < count; slot++) {
            attributeToInputRegister[firstAttribute + slot] = (value >>> (slot * 4)) & 0xF;
        }
    }

    private void onFloatUniformWord(int word) {
        int wordsPerConstant = floatUniformMode32 ? WORDS_PER_FLOAT32_CONSTANT : WORDS_PER_FLOAT24_CONSTANT;
        floatUniformPendingWords.add(word);
        if (floatUniformPendingWords.size() < wordsPerConstant) {
            return;
        }
        if (floatUniformIndex < floatConstants.length) {
            floatConstants[floatUniformIndex] = floatUniformMode32
                    ? decodeFloat32Constant(floatUniformPendingWords)
                    : decodeFloat24Constant(floatUniformPendingWords);
        }
        floatUniformIndex++;
        floatUniformPendingWords.clear();
    }

    /// **A ordem dos componentes é INVERTIDA** (`w`,`z`,`y`,`x`), igual ao modo float24 — a
    /// primeira palavra escrita no FIFO é o `w`. Confirmado contra a matriz real que o
    /// `simple_tri` sobe (`Mtx_OrthoTilt(0,400,0,240,0,1,true)`): lida na ordem direta, a primeira
    /// linha saía `(-1, 0, 1/120, 0)`; invertida, sai `(0, 1/120, 0, -1)`, que é exatamente a
    /// linha `x' = 2/(top-bottom)*y - 1` da ortográfica inclinada do 3DS. Sem a inversão o
    /// triângulo era projetado com `w = 0` e todo vértice virava `NaN`.
    private static float[] decodeFloat32Constant(List<Integer> words) {
        return new float[]{
                Float.intBitsToFloat(words.get(3)),
                Float.intBitsToFloat(words.get(2)),
                Float.intBitsToFloat(words.get(1)),
                Float.intBitsToFloat(words.get(0))};
    }

    /// Modo float24 empacotado: 4 componentes de 24 bits espremidos em 3 palavras de 32 bits, na
    /// ordem `w`,`z`,`y`,`x` (o componente `w` ocupa os 24 bits ALTOS da primeira palavra e `x` os
    /// 24 bits baixos da terceira). Layout transcrito do `Pica::Regs` real do Citra
    /// (`video_core/pica.cpp`, tratamento de `vs_uniform_setup`) — é o modo que o `citro3d` usa por
    /// padrão (`C3D_FVUnifMtx4x4`), então sem ele NENHUM app de citro3d chega a desenhar.
    private static float[] decodeFloat24Constant(List<Integer> words) {
        int w0 = words.get(0);
        int w1 = words.get(1);
        int w2 = words.get(2);
        float w = Float24.decode(w0 >>> 8);
        float z = Float24.decode(((w0 & 0xFF) << 16) | ((w1 >>> 16) & 0xFFFF));
        float y = Float24.decode(((w1 & 0xFFFF) << 8) | ((w2 >>> 24) & 0xFF));
        float x = Float24.decode(w2 & 0xFFFFFF);
        return new float[]{x, y, z, w};
    }

    /// Convenção universal do `picasso`/`citro3d` (ver Javadoc da classe): `o0`=posição (`xyzw`),
    /// `o1`=cor (`rgba`).
    private static final List<ShaderBinary.OutputRegister> STANDARD_OUTPUT_REGISTERS = List.of(
            new ShaderBinary.OutputRegister(ShaderBinary.OutputRegister.SEMANTIC_POSITION, 0, 0xF),
            new ShaderBinary.OutputRegister(ShaderBinary.OutputRegister.SEMANTIC_COLOR, 1, 0xF));

    /// Monta o {@link ShaderBinary} capturado até agora — chamado depois que a lista de comandos
    /// terminou de escrever o upload (RFC/task: "estrutura o código para que a substituição futura
    /// por um compilador SPIR-V troque só a implementação" — aqui, é a substituição do PARSER de
    /// `.shbin`-arquivo por um parser de `.shbin`-por-registrador, mesma estrutura de dados de
    /// saída).
    public ShaderBinary.Executable toExecutable() {
        ShaderBinary.Executable executable = new ShaderBinary.Executable(ShaderBinary.Executable.SHADER_TYPE_VERTEX,
                mainOffset, code.length, 0xFFFF, 0x3, STANDARD_OUTPUT_REGISTERS, List.of(), List.of(), List.of());
        return executable;
    }

    public ShaderBinary toShaderBinary() {
        return new ShaderBinary(code.clone(), operandDescriptors.clone(), List.of(toExecutable()));
    }

    public float[][] floatConstants() {
        return floatConstants;
    }

    public int[][] intConstants() {
        return intConstants;
    }

    public boolean[] boolConstants() {
        return boolConstants;
    }

    /// Mapeamento *atributo → registrador de entrada do shader* (`v0`-`v15`) decodificado das
    /// permutações — mesmo formato que `VertexPipeline` já esperava explicitamente na PR2 (agora
    /// vem do real, não de um parâmetro fixo do chamador).
    public int[] attributeToInputRegister() {
        return attributeToInputRegister.clone();
    }

    public boolean hasProgram() {
        return codeCursor > 0;
    }
}
