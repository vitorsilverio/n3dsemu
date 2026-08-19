package dev.vitorsilverio.n3dsemu.gpu.shader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// RFC-N3DSEMU G5/PR2: carrega o `.shbin` REAL do exemplo `simple_tri` (compilado pelo próprio
/// `picasso.exe`, ver `testdata/README.md`) e confere campo a campo contra o `.v.pica` de origem
/// (`C:\devkitPro\examples\3ds\graphics\gpu\simple_tri\source\vshader.v.pica`) — a técnica de
/// teste que a task pede ("determinístico, não precisa de GPU").
class ShaderBinaryTest {
    private static final Path SIMPLE_TRI_SHBIN = Path.of("testdata/shaders/simple_tri.shbin");

    private static ShaderBinary load() throws IOException {
        return ShaderBinary.parse(Files.readAllBytes(SIMPLE_TRI_SHBIN));
    }

    @Test
    void parsesProgramCodeAndOperandDescriptorCounts() throws IOException {
        ShaderBinary shader = load();

        // vshader.v.pica: mov,mov,dp4,dp4,dp4,dp4,mov,end = 8 instruções; picasso gera 7
        // descritores de operando distintos (algumas instruções compartilham o mesmo padrão de
        // swizzle/negação).
        assertEquals(8, shader.programCode().length);
        assertEquals(7, shader.operandDescriptors().length);
    }

    @Test
    void decodesKnownInstructionOpcodes() throws IOException {
        ShaderBinary shader = load();
        int[] code = shader.programCode();

        assertEquals(0x13, opcode(code[0])); // mov r0.xyz, inpos
        assertEquals(0x13, opcode(code[1])); // mov r0.w, ones
        assertEquals(0x02, opcode(code[2])); // dp4 outpos.x, projection[0], r0
        assertEquals(0x02, opcode(code[3]));
        assertEquals(0x02, opcode(code[4]));
        assertEquals(0x02, opcode(code[5]));
        assertEquals(0x13, opcode(code[6])); // mov outclr, inclr
        assertEquals(0x22, opcode(code[7])); // end
    }

    @Test
    void hasSingleVertexExecutableWithExpectedEntryPoint() throws IOException {
        ShaderBinary shader = load();

        assertEquals(1, shader.executables().size());
        ShaderBinary.Executable exec = shader.executables().get(0);
        assertEquals(ShaderBinary.Executable.SHADER_TYPE_VERTEX, exec.shaderType());
        assertEquals(0, exec.mainOffset());
        assertEquals(8, exec.endMainOffset());
    }

    @Test
    void outputRegisterTableMapsPositionAndColor() throws IOException {
        ShaderBinary shader = load();
        List<ShaderBinary.OutputRegister> outputs = shader.executables().get(0).outputRegisters();

        assertEquals(2, outputs.size());
        ShaderBinary.OutputRegister position = outputs.get(0);
        assertEquals(ShaderBinary.OutputRegister.SEMANTIC_POSITION, position.semanticType());
        assertEquals(0, position.registerId());
        assertEquals(0b1111, position.componentMask());

        ShaderBinary.OutputRegister color = outputs.get(1);
        assertEquals(ShaderBinary.OutputRegister.SEMANTIC_COLOR, color.semanticType());
        assertEquals(1, color.registerId());
        assertEquals(0b1111, color.componentMask());
    }

    @Test
    void constantTableDecodesEmbeddedFloatLiteralsFromConstfDirective() throws IOException {
        ShaderBinary shader = load();
        List<ShaderBinary.ConstantFloat> constants = shader.executables().get(0).floatConstants();

        // .constf myconst(0.0, 1.0, -1.0, 0.1) -> c95 (alocado no topo do banco pelo picasso)
        // .constf myconst2(0.3, 0.0, 0.0, 0.0) -> c94
        assertEquals(2, constants.size());
        ShaderBinary.ConstantFloat myconst = constants.get(0);
        assertEquals(95, myconst.registerId());
        assertEquals(0.0f, myconst.x(), 1e-6f);
        assertEquals(1.0f, myconst.y(), 1e-6f);
        assertEquals(-1.0f, myconst.z(), 1e-6f);
        assertEquals(0.1f, myconst.w(), 1e-3f);

        ShaderBinary.ConstantFloat myconst2 = constants.get(1);
        assertEquals(94, myconst2.registerId());
        assertEquals(0.3f, myconst2.x(), 1e-3f);
    }

    @Test
    void requiresDvlbMagic() {
        byte[] garbage = new byte[64];
        assertTrue(assertThrowsIllegalArgument(garbage).getMessage().contains("DVLB"));
    }

    private static IllegalArgumentException assertThrowsIllegalArgument(byte[] data) {
        try {
            ShaderBinary.parse(data);
        } catch (IllegalArgumentException e) {
            return e;
        }
        throw new AssertionError("esperava IllegalArgumentException");
    }

    private static int opcode(int word) {
        return (word >>> 26) & 0x3F;
    }
}
