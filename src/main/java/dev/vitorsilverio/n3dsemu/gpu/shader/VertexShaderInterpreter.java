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
/// **Escopo desta PR (G5/PR2): só os formatos 1/1u** (`ADD`/`DP3`/`DP4`/`DPH`/`MUL`/`SGE`/`SLT`/
/// `MAX`/`MIN`/`EX2`/`LG2`/`FLR`/`RCP`/`RSQ`/`MOVA`/`MOV`/`END`/`NOP`) — o suficiente para
/// `simple_tri` (único consumidor real disponível). **Não implementados** (lançam
/// `UnsupportedOperationException`): `DST`/`LITP` (formato 1, mas semântica especial), o formato
/// **1c** (`CMP`), **1i** (`DPHI`/`DSTI`/`SGEI`/`SLTI`), **5/5i** (`MAD`/`MADI`) e todo o controle
/// de fluxo (`CALL`/`IFU`/`IFC`/`LOOP`/`JMPC`/`JMPU`/`BREAK`/`BREAKC`) — o layout de bits exato
/// desses formatos não pôde ser cross-validado contra um `.shbin` real nesta sessão (só
/// transcrição de boa-fé do 3dbrew, com números inconsistentes entre buscas — ver histórico da
/// task G5 PR2) e nenhum exemplo do marco M5 os usa. Candidatos a uma PR de extensão quando um
/// consumidor real (ex.: `textured_cube`, que usa laços) aparecer.
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
        return new Run(shader, inputRegisters, floatConstants, intConstants, boolConstants).execute(mainOffset);
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
        private Boolean step(int opcode, int word, int pc) {
            nextPc = pc + 1;
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
            float[] src1 = readSource(src1Index, idx1, operandDescriptors[desc], true);
            float[] src2 = readSource(src2Index, 0, operandDescriptors[desc], false);
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
            float[] src1 = readSource(src1Index, idx1, operandDescriptors[desc], true);
            writeDest(dst, operandDescriptors[desc], op.apply(src1));
        }

        private void mova(int word) {
            int desc = bits(word, 0, 7);
            int src1Index = bits(word, 12, 7);
            int idx1 = bits(word, 19, 2);
            float[] src1 = readSource(src1Index, idx1, operandDescriptors[desc], true);
            int descriptor = operandDescriptors[desc];
            int dstMask = bits(descriptor, 0, 4);
            if ((dstMask & 0b1000) != 0) {
                addressRegister[0] = (int) src1[0];
            }
            if ((dstMask & 0b0100) != 0) {
                addressRegister[1] = (int) src1[1];
            }
        }

        private float[] readSource(int registerIndex, int addressSelector, int descriptor, boolean isSrc1) {
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
            int selectorShift = isSrc1 ? 5 : 14;
            int selector = bits(descriptor, selectorShift, 8);
            boolean negate = ((descriptor >>> (isSrc1 ? 4 : 13)) & 1) != 0;
            float[] swizzled = swizzle(raw, selector);
            return negate ? negate(swizzled) : swizzled;
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
