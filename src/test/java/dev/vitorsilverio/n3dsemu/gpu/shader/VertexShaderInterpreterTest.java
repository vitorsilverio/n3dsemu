package dev.vitorsilverio.n3dsemu.gpu.shader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// RFC-N3DSEMU G5/PR2: roda o interpretador contra o `.shbin` REAL do `simple_tri`
/// (`vshader.v.pica`) com entradas conhecidas e confere a saída contra o valor calculado à mão —
/// exatamente a técnica de teste que a task pede ("determinístico, não precisa de GPU").
class VertexShaderInterpreterTest {
    private static final Path SIMPLE_TRI_SHBIN = Path.of("testdata/shaders/simple_tri.shbin");

    private static ShaderBinary load() throws IOException {
        return ShaderBinary.parse(Files.readAllBytes(SIMPLE_TRI_SHBIN));
    }

    /// `mov r0.xyz,inpos / mov r0.w,ones / outpos = projection*r0 / outclr = inclr` com
    /// `projection` = identidade: `outpos` deve sair igual a `(inpos.xyz, 1.0)` e `outclr` igual
    /// a `inclr`, sem nenhuma transformação real — o teste mais simples que exercita a cadeia
    /// inteira (leitura de input, MOV com swizzle/máscara, DP4 contra constante de runtime, MOV
    /// de saída, END).
    @Test
    void simpleTriWithIdentityProjectionPassesThroughPositionAndColor() throws IOException {
        ShaderBinary shader = load();
        ShaderBinary.Executable exec = shader.executables().get(0);

        float[][] input = new float[VertexShaderInterpreter.NUM_INPUT_REGISTERS][4];
        input[0] = new float[]{1f, 2f, 3f, 999f}; // inpos (v0) — w é ignorado pelo shader (forçado a 1.0)
        input[1] = new float[]{0.25f, 0.5f, 0.75f, 1.0f}; // inclr (v1)

        float[][] floatConstants = VertexShaderInterpreter.embeddedFloatConstants(exec);
        setIdentityProjection(floatConstants);

        float[][] output = VertexShaderInterpreter.run(shader, exec.mainOffset(), input, floatConstants,
                VertexShaderInterpreter.embeddedIntConstants(exec), VertexShaderInterpreter.embeddedBoolConstants(exec));

        ShaderBinary.OutputRegister position = exec.outputRegisters().get(0);
        ShaderBinary.OutputRegister color = exec.outputRegisters().get(1);
        float[] outpos = output[position.registerId()];
        float[] outclr = output[color.registerId()];

        assertEquals(1f, outpos[0], 1e-6f);
        assertEquals(2f, outpos[1], 1e-6f);
        assertEquals(3f, outpos[2], 1e-6f);
        assertEquals(1f, outpos[3], 1e-6f); // "ones" (myconst.y) via mov r0.w, ones

        assertEquals(0.25f, outclr[0], 1e-6f);
        assertEquals(0.5f, outclr[1], 1e-6f);
        assertEquals(0.75f, outclr[2], 1e-6f);
        assertEquals(1.0f, outclr[3], 1e-6f);
    }

    @Test
    void projectionMatrixIsActuallyAppliedNotJustPassthrough() throws IOException {
        ShaderBinary shader = load();
        ShaderBinary.Executable exec = shader.executables().get(0);

        float[][] input = new float[VertexShaderInterpreter.NUM_INPUT_REGISTERS][4];
        input[0] = new float[]{1f, 0f, 0f, 0f};
        input[1] = new float[]{1f, 1f, 1f, 1f};

        float[][] floatConstants = VertexShaderInterpreter.embeddedFloatConstants(exec);
        // Escala uniforme por 2 (matriz diagonal 2*I) — outpos deve sair (2,0,0,2) para
        // inpos=(1,0,0) (w forçado a 1 pelo shader, escalado por 2 na linha w).
        floatConstants[0] = new float[]{2f, 0f, 0f, 0f};
        floatConstants[1] = new float[]{0f, 2f, 0f, 0f};
        floatConstants[2] = new float[]{0f, 0f, 2f, 0f};
        floatConstants[3] = new float[]{0f, 0f, 0f, 2f};

        float[][] output = VertexShaderInterpreter.run(shader, exec.mainOffset(), input, floatConstants,
                VertexShaderInterpreter.embeddedIntConstants(exec), VertexShaderInterpreter.embeddedBoolConstants(exec));

        float[] outpos = output[exec.outputRegisters().get(0).registerId()];
        assertEquals(2f, outpos[0], 1e-6f);
        assertEquals(0f, outpos[1], 1e-6f);
        assertEquals(0f, outpos[2], 1e-6f);
        assertEquals(2f, outpos[3], 1e-6f);
    }

    private static void setIdentityProjection(float[][] floatConstants) {
        floatConstants[0] = new float[]{1f, 0f, 0f, 0f};
        floatConstants[1] = new float[]{0f, 1f, 0f, 0f};
        floatConstants[2] = new float[]{0f, 0f, 1f, 0f};
        floatConstants[3] = new float[]{0f, 0f, 0f, 1f};
    }
}
