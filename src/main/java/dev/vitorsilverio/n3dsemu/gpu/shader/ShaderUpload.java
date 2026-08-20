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
/// **Simplificação documentada desta PR**: só o modo **float32** do upload de uniforms
/// (`GPUREG_VS_FLOATUNIFORM_CONFIG` bit 31 = 1, 4 palavras IEEE754 por constante) é suportado — o
/// modo float24 empacotado (3 palavras/constante, bits cruzando fronteiras de palavra) não pôde
/// ser validado contra um upload real nesta sessão (sem GPU disponível, RFC D4) e lança
/// {@link UnsupportedOperationException} se encontrado, em vez de arriscar decodificar errado
/// silenciosamente (mesma postura de `VertexShaderInterpreter` para opcodes fora do escopo).
public final class ShaderUpload implements RegisterWriteListener {
    /// `GPUREG_VS_BOOLUNIFORM`.
    public static final int REG_BOOL_UNIFORM = 0x2B0;
    /// `GPUREG_VS_INTUNIFORM_I0`-`I3` (4 registradores consecutivos).
    public static final int REG_INT_UNIFORM_BASE = 0x2B1;
    private static final int NUM_INT_UNIFORMS = 4;
    /// `GPUREG_VS_ENTRYPOINT`.
    public static final int REG_ENTRYPOINT = 0x2BA;
    /// `GPUREG_VS_ATTRIBUTES_PERMUTATION_LOW`/`HIGH`.
    public static final int REG_ATTRIBUTES_PERMUTATION_LOW = 0x2BB;
    public static final int REG_ATTRIBUTES_PERMUTATION_HIGH = 0x2BC;
    /// `GPUREG_VS_FLOATUNIFORM_CONFIG` (índice do registrador de destino + bit 31 = modo).
    public static final int REG_FLOAT_UNIFORM_CONFIG = 0x2C0;
    private static final int REG_FLOAT_UNIFORM_DATA_FIRST = 0x2C1;
    private static final int REG_FLOAT_UNIFORM_DATA_LAST = 0x2C8;
    private static final int FLOAT_UNIFORM_MODE_32_BIT = 1 << 31;
    private static final int WORDS_PER_FLOAT32_CONSTANT = 4;
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
            mainOffset = value;
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
        if (!floatUniformMode32) {
            throw new UnsupportedOperationException(
                    "upload de uniform float em modo float24 (empacotado) não implementado nesta PR — "
                            + "ver Javadoc de ShaderUpload");
        }
        floatUniformPendingWords.add(word);
        if (floatUniformPendingWords.size() == WORDS_PER_FLOAT32_CONSTANT) {
            if (floatUniformIndex < floatConstants.length) {
                floatConstants[floatUniformIndex] = new float[]{
                        Float.intBitsToFloat(floatUniformPendingWords.get(0)),
                        Float.intBitsToFloat(floatUniformPendingWords.get(1)),
                        Float.intBitsToFloat(floatUniformPendingWords.get(2)),
                        Float.intBitsToFloat(floatUniformPendingWords.get(3))};
            }
            floatUniformIndex++;
            floatUniformPendingWords.clear();
        }
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
