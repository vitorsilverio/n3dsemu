package dev.vitorsilverio.n3dsemu.gpu.shader;

/// Interpretador da ISA de vertex shader do PICA200 (RFC-N3DSEMU G5/D5: "interpretar na CPU" —
/// decisão explícita, sem JIT de shader nesta task).
///
/// Layout de bits das instruções transcrito de <a
/// href="https://www.3dbrew.org/wiki/GPU/Shader_Instruction_Set">3dbrew: GPU/Shader Instruction
/// Set</a>. Os formatos **1/1u** (a maioria da ISA: `ADD`/`DP3`/`DP4`/`DPH`/`MUL`/`MAX`/`MIN`/
/// `SGE`/`SLT`/`MOV`/`MOVA`/`RCP`/`RSQ`/`FLR`/`EX2`/`LG2`/`END`/`NOP`) e o mapeamento de
/// registrador (`v0-v15`=0-15, `r0-r15`=16-31, `c0-c95`=32-127) foram **validados nesta sessão**
/// bit a bit contra um `.shbin` real compilado pelo `picasso` (`simple_tri`, conferido instrução
/// a instrução contra o `.v.pica` de origem — ver `ShaderBinaryTest`/`VertexShaderInterpreterTest`).
///
/// **Escopo original (G5/PR2): formatos 1/1u** (`ADD`/`DP3`/`DP4`/`DPH`/`MUL`/`SGE`/`SLT`/
/// `MAX`/`MIN`/`EX2`/`LG2`/`FLR`/`RCP`/`RSQ`/`MOVA`/`MOV`/`END`/`NOP`) — o suficiente para
/// `simple_tri` (único consumidor real disponível na época).
///
/// **G6.3 acrescentou o formato 1c** (`CMP`, opcodes `0x2E`/`0x2F`) **e os formatos 5/5i**
/// (`MAD`/`MADI`, opcodes `0x38`-`0x3F`/`0x30`-`0x37`) — layout de bits validado contra o código
/// fonte real do <a href="https://github.com/neobrain/nihstro">nihstro</a>
/// (`include/nihstro/shader_bytecode.h`, `union Instruction::Common`/`::mad`), não só o wiki do
/// 3dbrew (que não documenta offsets de bit) — `curl` direto do arquivo raw do GitHub nesta
/// sessão, ver task `g6.3-vertex-shader-cmp-mad.md` para o detalhe. Achado real: os opcodes `CMP`/
/// `MAD`/`MADI` têm bits "ignorados" na identificação (LSB para `CMP`, 3 bits baixos para
/// `MAD`/`MADI`) que na verdade são dados reais (`cmp.x`/parte do registrador de destino) —
/// tratado despachando por FAIXA de opcode (`>= 0x38`/`>= 0x30`/`== 0x2E ou 0x2F`) antes do
/// `switch` do formato 1, e relendo os bits de volta do word bruto dentro de cada handler (nunca
/// do valor de opcode já decodificado).
///
/// **Ainda não implementados** (lançam `UnsupportedOperationException`): `DST`/`LITP` (formato 1,
/// mas semântica especial), o formato **1i** (`DPHI`/`DSTI`/`SGEI`/`SLTI`) e todo o controle de
/// fluxo (`CALL`/`IFU`/`IFC`/`LOOP`/`JMPC`/`JMPU`/`BREAK`/`BREAKC`) — `CMP` grava em `cmp.x`/`cmp.y`
/// (ver {@link Result#conditionCode()}), mas nada ainda LÊ essas condition codes (não há `JMPC`/
/// `IFC`/`BREAKC`), então o efeito observável de `CMP` hoje é só o valor exposto por
/// {@link #runDetailed}. Candidatos a uma PR de extensão quando um consumidor real (ex.:
/// `textured_cube`, que usa laços/condicionais) aparecer.
public final class VertexShaderInterpreter {
    public static final int NUM_INPUT_REGISTERS = 16;
    public static final int NUM_TEMP_REGISTERS = 16;
    public static final int NUM_OUTPUT_REGISTERS = 16;
    public static final int NUM_FLOAT_CONSTANTS = 96;
    public static final int NUM_INT_CONSTANTS = 4;
    public static final int NUM_BOOL_CONSTANTS = 16;

    // Faixas do espaço de registrador unificado usado nos campos DST/SRC1/SRC2/SRC3 da ISA
    // (3dbrew: "Register mapping"; validado nesta sessão contra o `simple_tri.shbin` real —
    // `dp4 outpos.x, projection[0], r0` decodifica src1=32 e a tabela de uniforms do DVLE
    // aloca `projection` em c0, confirmando a base 32 para constantes NA ISA, distinta da base
    // 16 usada nas tabelas de metadados do DVLE).
    private static final int INPUT_BASE = 0;
    private static final int TEMP_BASE = 16;
    private static final int CONST_BASE = 32;
    private static final int CONST_REGISTER_MASK = 0x7F;

    private static final int MAX_STEPS = 1_000_000; // guarda contra programa malformado/loop infinito

    private VertexShaderInterpreter() {
    }

    /// Preenche um banco de 96 constantes float a partir das constantes embutidas no `.shbin`
    /// (literais do shader, ex.: `.constf`) — uniforms de runtime (ex.: matriz de projeção) devem
    /// ser sobrepostos pelo chamador DEPOIS desta chamada.
    public static float[][] embeddedFloatConstants(ShaderBinary.Executable executable) {
        float[][] constants = new float[NUM_FLOAT_CONSTANTS][4];
        for (ShaderBinary.ConstantFloat c : executable.floatConstants()) {
            constants[c.registerId()] = new float[]{c.x(), c.y(), c.z(), c.w()};
        }
        return constants;
    }

    public static boolean[] embeddedBoolConstants(ShaderBinary.Executable executable) {
        boolean[] constants = new boolean[NUM_BOOL_CONSTANTS];
        for (ShaderBinary.ConstantBool c : executable.boolConstants()) {
            constants[c.registerId()] = c.value();
        }
        return constants;
    }

    public static int[][] embeddedIntConstants(ShaderBinary.Executable executable) {
        int[][] constants = new int[NUM_INT_CONSTANTS][4];
        for (ShaderBinary.ConstantInt c : executable.intConstants()) {
            constants[c.registerId()] = new int[]{c.x(), c.y(), c.z(), c.w()};
        }
        return constants;
    }

    /// Executa o programa a partir de `mainOffset` até `END`. Retorna os 16 registradores de
    /// saída (`o0`-`o15`) — cabe ao chamador extrair só os semanticamente relevantes via
    /// {@link ShaderBinary.OutputRegister}.
    public static float[][] run(ShaderBinary shader, int mainOffset, float[][] inputRegisters,
                                 float[][] floatConstants, int[][] intConstants, boolean[] boolConstants) {
        return runDetailed(shader, mainOffset, inputRegisters, floatConstants, intConstants, boolConstants).output();
    }

    /// Como {@link #run}, mas também expõe as condition codes (`cmp.x`/`cmp.y`) gravadas por
    /// `CMP` — nenhum consumidor real lê essas condition codes ainda (não há `JMPC`/`IFC`/`BREAKC`
    /// implementados), então este método existe hoje só para permitir teste automatizado do
    /// efeito observável de `CMP` (RFC/protocolo: "todo comportamento observável novo precisa de
    /// teste automatizado").
    public static Result runDetailed(ShaderBinary shader, int mainOffset, float[][] inputRegisters,
                                      float[][] floatConstants, int[][] intConstants, boolean[] boolConstants) {
        Run run = new Run(shader, inputRegisters, floatConstants, intConstants, boolConstants);
        float[][] output = run.execute(mainOffset);
        return new Result(output, run.conditionCode.clone());
    }

    /// `conditionCode[0]` = `cmp.x`, `conditionCode[1]` = `cmp.y` (3dbrew: registradores de
    /// condição de 1 bit cada, gravados por `CMP` e lidos por `JMPC`/`IFC`/`BREAKC` — nenhum dos
    /// três implementado ainda).
    public record Result(float[][] output, boolean[] conditionCode) {
    }

    private static final class Run {
        private final int[] code;
        private final int[] operandDescriptors;
        private final float[][] input;
        private final float[][] temp = new float[NUM_TEMP_REGISTERS][4];
        private final float[][] output = new float[NUM_OUTPUT_REGISTERS][4];
        private final float[][] floatConstants;
        private final int[][] intConstants;
        private final boolean[] boolConstants;
        private final int[] addressRegister = new int[2]; // a0.x, a0.y
        private final int loopCounter = 0; // aL — LOOP não implementado nesta PR (ver Javadoc da classe)
        private final boolean[] conditionCode = new boolean[2]; // cmp.x, cmp.y — gravado por CMP

        Run(ShaderBinary shader, float[][] input, float[][] floatConstants, int[][] intConstants,
            boolean[] boolConstants) {
            this.code = shader.programCode();
            this.operandDescriptors = shader.operandDescriptors();
            this.input = input;
            this.floatConstants = floatConstants;
            this.intConstants = intConstants;
            this.boolConstants = boolConstants;
        }

        float[][] execute(int mainOffset) {
            int pc = mainOffset;
            for (int step = 0; step < MAX_STEPS; step++) {
                if (pc < 0 || pc >= code.length) {
                    return output;
                }
                int word = code[pc];
                int opcode = (word >>> 26) & 0x3F;
                Boolean halted = step(opcode, word, pc);
                if (halted != null && halted) {
                    return output;
                }
                pc = nextPc;
            }
            throw new IllegalStateException("vertex shader excedeu " + MAX_STEPS
                    + " passos — programa malformado ou laço infinito");
        }

        private int nextPc;

        /// Executa uma instrução; retorna `true` se o programa terminou (`END`), `null`/`false`
        /// caso contrário. Efeito colateral: define {@link #nextPc}.
        // nihstro shader_bytecode.h: "MAD = 0x38, // lower 3 opcode bits ignored" / "MADI = 0x30"
        // / "CMP = 0x2E, // LSB opcode bit ignored" — os bits "ignorados" na identificação são,
        // na verdade, dados reais (parte do registrador de destino do MAD, cmp.x do CMP), então o
        // despacho é por FAIXA de opcode, e os handlers relêem esses bits do word bruto (nunca do
        // valor de opcode já mascarado por este dispatch).
        private static final int OPCODE_CMP = 0x2E;
        private static final int OPCODE_MADI = 0x30;
        private static final int OPCODE_MAD = 0x38;

        private Boolean step(int opcode, int word, int pc) {
            nextPc = pc + 1;
            if (opcode >= OPCODE_MAD) {
                multiplyAdd(word, false);
                return Boolean.FALSE;
            }
            if (opcode >= OPCODE_MADI) {
                multiplyAdd(word, true);
                return Boolean.FALSE;
            }
            if (opcode == OPCODE_CMP || opcode == OPCODE_CMP + 1) {
                compare(word);
                return Boolean.FALSE;
            }
            switch (opcode) {
                case 0x00 -> format1(word, Run::add);
                case 0x01 -> format1Reduce(word, Run::dot3);
                case 0x02 -> format1Reduce(word, Run::dot4);
                case 0x03 -> format1Reduce(word, Run::dotH);
                case 0x08 -> format1(word, Run::mul);
                case 0x09 -> format1(word, Run::compareGeVector);
                case 0x0A -> format1(word, Run::compareLtVector);
                case 0x0C -> format1(word, Run::max);
                case 0x0D -> format1(word, Run::min);
                case 0x05 -> format1u(word, a -> broadcast((float) Math.pow(2.0, a[0])));
                case 0x06 -> format1u(word, a -> broadcast((float) (Math.log(a[0]) / Math.log(2.0))));
                case 0x0B -> format1u(word, a -> apply(a, x -> (float) Math.floor(x)));
                case 0x0E -> format1u(word, a -> broadcast(1.0f / a[0]));
                case 0x0F -> format1u(word, a -> broadcast((float) (1.0 / Math.sqrt(a[0]))));
                case 0x12 -> mova(word);
                case 0x13 -> format1u(word, a -> a);
                case 0x22 -> {
                    return Boolean.TRUE;
                }
                case 0x21 -> {
                    // NOP
                }
                default -> throw new UnsupportedOperationException(
                        "opcode de vertex shader não implementado: 0x" + Integer.toHexString(opcode));
            }
            return Boolean.FALSE;
        }

        private interface BinaryOp {
            float[] apply(float[] a, float[] b);
        }

        private interface ReduceOp {
            float apply(float[] a, float[] b);
        }

        private interface UnaryOp {
            float[] apply(float[] a);
        }

        private void format1(int word, BinaryOp op) {
            int desc = bits(word, 0, 7);
            int src2Index = bits(word, 7, 5);
            int src1Index = bits(word, 12, 7);
            int idx1 = bits(word, 19, 2);
            int dst = bits(word, 21, 5);
            float[] src1 = readSource(src1Index, idx1, operandDescriptors[desc], OPERAND_SRC1);
            float[] src2 = readSource(src2Index, 0, operandDescriptors[desc], OPERAND_SRC2);
            writeDest(dst, operandDescriptors[desc], op.apply(src1, src2));
        }

        /// Como {@link #format1}, mas o resultado (escalar em `result[0]`) é replicado em todos os
        /// componentes habilitados pela máscara de destino (`DP3`/`DP4`/`DPH`: 3dbrew "result
        /// broadcast to all components").
        private void format1Reduce(int word, ReduceOp op) {
            format1(word, (a, b) -> broadcast(op.apply(a, b)));
        }

        private void format1u(int word, UnaryOp op) {
            int desc = bits(word, 0, 7);
            int src1Index = bits(word, 12, 7);
            int idx1 = bits(word, 19, 2);
            int dst = bits(word, 21, 5);
            float[] src1 = readSource(src1Index, idx1, operandDescriptors[desc], OPERAND_SRC1);
            writeDest(dst, operandDescriptors[desc], op.apply(src1));
        }

        private void mova(int word) {
            int desc = bits(word, 0, 7);
            int src1Index = bits(word, 12, 7);
            int idx1 = bits(word, 19, 2);
            float[] src1 = readSource(src1Index, idx1, operandDescriptors[desc], OPERAND_SRC1);
            int descriptor = operandDescriptors[desc];
            int dstMask = bits(descriptor, 0, 4);
            if ((dstMask & 0b1000) != 0) {
                addressRegister[0] = (int) src1[0];
            }
            if ((dstMask & 0b0100) != 0) {
                addressRegister[1] = (int) src1[1];
            }
        }

        // Slot do operando dentro do descritor de swizzle/negate (3dbrew "SwizzlePattern"/nihstro
        // `union SwizzlePattern`, validado contra o código-fonte real do nihstro nesta sessão —
        // ver Javadoc da classe): cada slot tem seu próprio bit de negação e seletor de 8 bits.
        // `OPERAND_SRC3` só é usado por `MAD`/`MADI` (formato 5/5i); formato 1/1u/1c usam só
        // `SRC1`/`SRC2`.
        private static final int OPERAND_SRC1 = 0;
        private static final int OPERAND_SRC2 = 1;
        private static final int OPERAND_SRC3 = 2;
        private static final int[] OPERAND_NEGATE_BIT = {4, 13, 22};
        private static final int[] OPERAND_SELECTOR_SHIFT = {5, 14, 23};

        private float[] readSource(int registerIndex, int addressSelector, int descriptor, int operandSlot) {
            int effectiveIndex = registerIndex;
            if (addressSelector != 0 && registerIndex >= CONST_BASE) {
                int offset = switch (addressSelector) {
                    case 1 -> addressRegister[0];
                    case 2 -> addressRegister[1];
                    case 3 -> loopCounter;
                    default -> 0;
                };
                if (offset >= -128 && offset <= 127) {
                    effectiveIndex = (registerIndex + offset) & CONST_REGISTER_MASK;
                }
            }
            float[] raw = readRegister(effectiveIndex);
            int selector = bits(descriptor, OPERAND_SELECTOR_SHIFT[operandSlot], 8);
            boolean negate = ((descriptor >>> OPERAND_NEGATE_BIT[operandSlot]) & 1) != 0;
            float[] swizzled = swizzle(raw, selector);
            return negate ? negate(swizzled) : swizzled;
        }

        // Formato 1c (3dbrew/nihstro `union CompareOpType`): opcode `0x2E`/`0x2F`, mesmo layout de
        // src1/src2/idx do formato 1 comum, mas os bits 21-26 (que no formato 1 seriam `dest`) são
        // dois campos de 3 bits (`cmp.y`=21-23, `cmp.x`=24-26) — sem registrador de destino normal,
        // o resultado vai para as condition codes `cmp.x`/`cmp.y` (ver {@link Result#conditionCode}).
        private static final int COMPARE_EQUAL = 0;
        private static final int COMPARE_NOT_EQUAL = 1;
        private static final int COMPARE_LESS_THAN = 2;
        private static final int COMPARE_LESS_EQUAL = 3;
        private static final int COMPARE_GREATER_THAN = 4;
        private static final int COMPARE_GREATER_EQUAL = 5;

        private void compare(int word) {
            int desc = bits(word, 0, 7);
            int src2Index = bits(word, 7, 5);
            int src1Index = bits(word, 12, 7);
            int idx1 = bits(word, 19, 2);
            int opY = bits(word, 21, 3);
            int opX = bits(word, 24, 3);
            int descriptor = operandDescriptors[desc];
            float[] src1 = readSource(src1Index, idx1, descriptor, OPERAND_SRC1);
            float[] src2 = readSource(src2Index, 0, descriptor, OPERAND_SRC2);
            conditionCode[0] = compareComponent(opX, src1[0], src2[0]);
            conditionCode[1] = compareComponent(opY, src1[1], src2[1]);
        }

        private static boolean compareComponent(int op, float a, float b) {
            return switch (op) {
                case COMPARE_EQUAL -> a == b;
                case COMPARE_NOT_EQUAL -> a != b;
                case COMPARE_LESS_THAN -> a < b;
                case COMPARE_LESS_EQUAL -> a <= b;
                case COMPARE_GREATER_THAN -> a > b;
                case COMPARE_GREATER_EQUAL -> a >= b;
                default -> throw new UnsupportedOperationException(
                        "operador de comparação reservado (0x6/0x7) do CMP do PICA200: " + op);
            };
        }

        // Formato 5/5i (3dbrew/nihstro `union Instruction::mad` — validado contra o código-fonte
        // real, não o wiki): 3 operandos, descritor de operando de só 5 bits (mesma tabela de
        // operandDescriptors do formato 1, só que MAD convencionalmente usa índices < 32 — ver
        // Javadoc da classe). `inverted=false` (opcode `MAD`, `0x38`-`0x3F`): src1 em bits(17,5),
        // src2 "largo" (7 bits, pode referenciar uniform) em bits(10,7), src3 "estreito" (5 bits)
        // em bits(5,5) — endereçamento relativo (`idx`) se aplica ao operando largo, aqui src2.
        // `inverted=true` (opcode `MADI`, `0x30`-`0x37`): src1 continua igual; src2 vira estreito
        // (5 bits) em bits(12,5); src3 vira o largo (7 bits) em bits(5,7) — `idx` passa a se
        // aplicar a src3.
        private void multiplyAdd(int word, boolean inverted) {
            int desc = bits(word, 0, 5);
            int src1Index = bits(word, 17, 5);
            int src2Index = inverted ? bits(word, 12, 5) : bits(word, 10, 7);
            int src3Index = inverted ? bits(word, 5, 7) : bits(word, 5, 5);
            int idx = bits(word, 22, 2);
            int dst = bits(word, 24, 5);
            int descriptor = operandDescriptors[desc];
            float[] src1 = readSource(src1Index, 0, descriptor, OPERAND_SRC1);
            float[] src2 = readSource(src2Index, inverted ? 0 : idx, descriptor, OPERAND_SRC2);
            float[] src3 = readSource(src3Index, inverted ? idx : 0, descriptor, OPERAND_SRC3);
            writeDest(dst, descriptor, multiplyAdd(src1, src2, src3));
        }

        private static float[] multiplyAdd(float[] a, float[] b, float[] c) {
            return new float[]{
                    Math.fma(a[0], b[0], c[0]),
                    Math.fma(a[1], b[1], c[1]),
                    Math.fma(a[2], b[2], c[2]),
                    Math.fma(a[3], b[3], c[3])
            };
        }

        private float[] readRegister(int index) {
            if (index < TEMP_BASE) {
                return input[index - INPUT_BASE];
            }
            if (index < CONST_BASE) {
                return temp[index - TEMP_BASE];
            }
            return floatConstants[index - CONST_BASE];
        }

        private void writeDest(int dstIndex, int descriptor, float[] value) {
            int mask = bits(descriptor, 0, 4);
            float[] target = dstIndex < TEMP_BASE ? output[dstIndex] : temp[dstIndex - TEMP_BASE];
            if ((mask & 0b1000) != 0) {
                target[0] = value[0];
            }
            if ((mask & 0b0100) != 0) {
                target[1] = value[1];
            }
            if ((mask & 0b0010) != 0) {
                target[2] = value[2];
            }
            if ((mask & 0b0001) != 0) {
                target[3] = value[3];
            }
        }

        private static int bits(int word, int lowestBit, int width) {
            return (word >>> lowestBit) & ((1 << width) - 1);
        }

        private static float[] swizzle(float[] v, int selector) {
            float[] result = new float[4];
            for (int component = 0; component < 4; component++) {
                int sourceComponent = (selector >>> (2 * (3 - component))) & 0x3;
                result[component] = v[sourceComponent];
            }
            return result;
        }

        private static float[] negate(float[] v) {
            return new float[]{-v[0], -v[1], -v[2], -v[3]};
        }

        private static float[] add(float[] a, float[] b) {
            return new float[]{a[0] + b[0], a[1] + b[1], a[2] + b[2], a[3] + b[3]};
        }

        private static float[] mul(float[] a, float[] b) {
            return new float[]{a[0] * b[0], a[1] * b[1], a[2] * b[2], a[3] * b[3]};
        }

        private static float[] max(float[] a, float[] b) {
            return new float[]{Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])};
        }

        private static float[] min(float[] a, float[] b) {
            return new float[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.min(a[3], b[3])};
        }

        private static float[] compareGeVector(float[] a, float[] b) {
            return new float[]{a[0] >= b[0] ? 1f : 0f, a[1] >= b[1] ? 1f : 0f, a[2] >= b[2] ? 1f : 0f, a[3] >= b[3] ? 1f : 0f};
        }

        private static float[] compareLtVector(float[] a, float[] b) {
            return new float[]{a[0] < b[0] ? 1f : 0f, a[1] < b[1] ? 1f : 0f, a[2] < b[2] ? 1f : 0f, a[3] < b[3] ? 1f : 0f};
        }

        private static float[] broadcast(float value) {
            return new float[]{value, value, value, value};
        }

        private interface FloatUnary {
            float apply(float x);
        }

        private static float[] apply(float[] a, FloatUnary f) {
            return new float[]{f.apply(a[0]), f.apply(a[1]), f.apply(a[2]), f.apply(a[3])};
        }

        private static float dot3(float[] a, float[] b) {
            return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        }

        private static float dot4(float[] a, float[] b) {
            return a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        }

        /// `DPH`: SRC1 tratado como homogêneo (3dbrew: "SRC1 treated as homogeneous (1.0
        /// appended)") — só os 3 primeiros componentes de `a` participam, `a[3]` é forçado a 1.0.
        private static float dotH(float[] a, float[] b) {
            return a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + 1.0f * b[3];
        }
    }
}
