package dev.vitorsilverio.n3dsemu.gpu.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/// Carregador do contêiner `.shbin` (DVLB/DVLP/DVLE, RFC-N3DSEMU G5/D5) — formato de saída do
/// `picasso` (montador de vertex shader do devkitPro). Transcrito de <a
/// href="https://www.3dbrew.org/wiki/SHBIN">3dbrew: SHBIN</a> e **validado nesta sessão
/// campo-a-campo** decodificando o `.shbin` real do exemplo `simple_tri`
/// (`C:\devkitPro\examples\3ds\graphics\gpu\simple_tri\source\vshader.v.pica`, compilado com o
/// `picasso.exe` instalado) e conferindo cada instrução/registrador/constante contra o fonte
/// `.v.pica` original — não é transcrição às cegas do wiki.
///
/// Um arquivo DVLB pode conter várias seções DVLE (vertex + geometry shader no mesmo binário);
/// esta task só lê vertex shader (`shaderType == 0`), a única usada pelo marco M5.
public record ShaderBinary(int[] programCode, int[] operandDescriptors, List<Executable> executables) {

    private static final byte[] MAGIC_DVLB = {'D', 'V', 'L', 'B'};
    private static final byte[] MAGIC_DVLP = {'D', 'V', 'L', 'P'};
    private static final byte[] MAGIC_DVLE = {'D', 'V', 'L', 'E'};

    private static final int CONSTANT_ENTRY_SIZE = 0x14;
    private static final int CONSTANT_TYPE_BOOL = 0;
    private static final int CONSTANT_TYPE_INT = 1;
    private static final int CONSTANT_TYPE_FLOAT = 2;

    private static final int OUTPUT_ENTRY_SIZE = 0x8;

    /// Uma seção DVLE (uma unidade executável — vertex shader) dentro do DVLB.
    ///
    /// `mainOffset`/`endMainOffset` são índices em `programCode` (words), não bytes.
    public record Executable(int shaderType, int mainOffset, int endMainOffset, int inputRegisterMask,
                              int outputRegisterMask, List<OutputRegister> outputRegisters,
                              List<ConstantFloat> floatConstants, List<ConstantInt> intConstants,
                              List<ConstantBool> boolConstants) {
        public static final int SHADER_TYPE_VERTEX = 0;
        public static final int SHADER_TYPE_GEOMETRY = 1;
    }

    /// Mapeamento semântico de um registrador de saída (3dbrew: tabela de registradores de saída
    /// do DVLE). `componentMask` usa a mesma convenção bit3=x,bit2=y,bit1=z,bit0=w do resto da
    /// ISA (RFC/3dbrew: "Mask (e.g., 5=xz)").
    public record OutputRegister(int semanticType, int registerId, int componentMask) {
        public static final int SEMANTIC_POSITION = 0;
        public static final int SEMANTIC_NORMALQUAT = 1;
        public static final int SEMANTIC_COLOR = 2;
        public static final int SEMANTIC_TEXCOORD0 = 3;
        public static final int SEMANTIC_TEXCOORD1 = 4;
        public static final int SEMANTIC_TEXCOORD2 = 5;
        public static final int SEMANTIC_TEXCOORD0_W = 6;
        public static final int SEMANTIC_VIEW = 8;
    }

    public record ConstantFloat(int registerId, float x, float y, float z, float w) {
    }

    public record ConstantInt(int registerId, int x, int y, int z, int w) {
    }

    public record ConstantBool(int registerId, boolean value) {
    }

    public static ShaderBinary parse(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        requireMagic(buffer, 0, MAGIC_DVLB, "DVLB");
        int dvleCount = buffer.getInt(4);
        List<Integer> dvleOffsets = new ArrayList<>(dvleCount);
        for (int i = 0; i < dvleCount; i++) {
            dvleOffsets.add(buffer.getInt(8 + 4 * i));
        }

        int dvlpOffset = 8 + 4 * dvleCount;
        requireMagic(buffer, dvlpOffset, MAGIC_DVLP, "DVLP");
        int binaryOffset = buffer.getInt(dvlpOffset + 0x8);
        int binarySizeWords = buffer.getInt(dvlpOffset + 0xC);
        int operandOffset = buffer.getInt(dvlpOffset + 0x10);
        int operandCount = buffer.getInt(dvlpOffset + 0x14);

        int codeStart = dvlpOffset + binaryOffset;
        int[] programCode = new int[binarySizeWords];
        for (int i = 0; i < binarySizeWords; i++) {
            programCode[i] = buffer.getInt(codeStart + 4 * i);
        }

        // 3dbrew: entradas de 8 bytes; só a primeira word (o padrão de swizzle em si) é
        // significativa — a segunda é padding de alinhamento (confirmado nesta sessão: todas as
        // segundas words do `simple_tri.shbin` real são 0).
        int opStart = dvlpOffset + operandOffset;
        int[] operandDescriptors = new int[operandCount];
        for (int i = 0; i < operandCount; i++) {
            operandDescriptors[i] = buffer.getInt(opStart + 8 * i);
        }

        List<Executable> executables = new ArrayList<>(dvleCount);
        for (int offset : dvleOffsets) {
            executables.add(parseExecutable(buffer, offset));
        }
        return new ShaderBinary(programCode, operandDescriptors, executables);
    }

    private static Executable parseExecutable(ByteBuffer buffer, int dvleOffset) {
        requireMagic(buffer, dvleOffset, MAGIC_DVLE, "DVLE");
        int shaderType = buffer.get(dvleOffset + 0x6) & 0xFF;
        int mainOffset = buffer.getInt(dvleOffset + 0x8);
        int endMainOffset = buffer.getInt(dvleOffset + 0xC);
        int inputMask = buffer.getShort(dvleOffset + 0x10) & 0xFFFF;
        int outputMask = buffer.getShort(dvleOffset + 0x12) & 0xFFFF;
        int constOffset = buffer.getInt(dvleOffset + 0x18);
        int constCount = buffer.getInt(dvleOffset + 0x1C);
        int outOffset = buffer.getInt(dvleOffset + 0x28);
        int outCount = buffer.getInt(dvleOffset + 0x2C);

        List<OutputRegister> outputRegisters = new ArrayList<>(outCount);
        int outBase = dvleOffset + outOffset;
        for (int i = 0; i < outCount; i++) {
            int base = outBase + OUTPUT_ENTRY_SIZE * i;
            int type = buffer.getShort(base) & 0xFFFF;
            int regId = buffer.getShort(base + 2) & 0xFFFF;
            int mask = buffer.getShort(base + 4) & 0xFFFF;
            outputRegisters.add(new OutputRegister(type, regId, mask));
        }

        List<ConstantFloat> floatConstants = new ArrayList<>();
        List<ConstantInt> intConstants = new ArrayList<>();
        List<ConstantBool> boolConstants = new ArrayList<>();
        int constBase = dvleOffset + constOffset;
        for (int i = 0; i < constCount; i++) {
            int base = constBase + CONSTANT_ENTRY_SIZE * i;
            int type = buffer.get(base) & 0xFF;
            int regId = buffer.get(base + 2) & 0xFF;
            switch (type) {
                case CONSTANT_TYPE_BOOL -> boolConstants.add(new ConstantBool(regId, buffer.get(base + 3) != 0));
                case CONSTANT_TYPE_INT -> intConstants.add(new ConstantInt(regId,
                        buffer.get(base + 4) & 0xFF, buffer.get(base + 5) & 0xFF,
                        buffer.get(base + 6) & 0xFF, buffer.get(base + 7) & 0xFF));
                case CONSTANT_TYPE_FLOAT -> floatConstants.add(new ConstantFloat(regId,
                        Float24.decode(buffer.getInt(base + 0x4) & 0xFFFFFF),
                        Float24.decode(buffer.getInt(base + 0x8) & 0xFFFFFF),
                        Float24.decode(buffer.getInt(base + 0xC) & 0xFFFFFF),
                        Float24.decode(buffer.getInt(base + 0x10) & 0xFFFFFF)));
                default -> throw new IllegalArgumentException("tipo de constante DVLE desconhecido: " + type);
            }
        }

        return new Executable(shaderType, mainOffset, endMainOffset, inputMask, outputMask,
                outputRegisters, floatConstants, intConstants, boolConstants);
    }

    private static void requireMagic(ByteBuffer buffer, int offset, byte[] expected, String name) {
        for (int i = 0; i < expected.length; i++) {
            if (buffer.get(offset + i) != expected[i]) {
                throw new IllegalArgumentException("assinatura " + name + " inválida no offset 0x"
                        + Integer.toHexString(offset));
            }
        }
    }
}
