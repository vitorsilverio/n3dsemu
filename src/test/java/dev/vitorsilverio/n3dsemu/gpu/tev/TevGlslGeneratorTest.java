package dev.vitorsilverio.n3dsemu.gpu.tev;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testa `TevConfig → String` comparando a saída gerada contra o que se espera para
/// configurações conhecidas (RFC-N3DSEMU G5/PR3: "compare a string gerada com uma esperada para
/// 2-3 configurações conhecidas — a do `simple_tri`, entre elas"). Não compila nada (sem
/// `shaderc`/GPU) — só a função pura.
class TevGlslGeneratorTest {
    @Test
    void simpleTriConfigPassesFragColorThrough() {
        String glsl = TevGlslGenerator.generate(TevConfig.passthroughPrimaryColor());

        assertTrue(glsl.contains("#version 450"));
        assertTrue(glsl.contains("in vec4 fragColor"));
        assertTrue(glsl.contains("out vec4 outColor"));
        // estágio 0: replace com PRIMARY_COLOR -> a fonte usada é fragColor, sem textura.
        assertTrue(glsl.contains("src0 = fragColor.rgb"));
        assertTrue(glsl.contains("outColor = previous"));
    }

    @Test
    void modulateStageMultipliesSources() {
        TevConfig.Stage modulate = new TevConfig.Stage(
                new TevConfig.Source[]{TevConfig.Source.PRIMARY_COLOR, TevConfig.Source.TEXTURE0, TevConfig.Source.PREVIOUS},
                new int[]{0, 0, 0}, TevConfig.CombinerOp.MODULATE,
                new TevConfig.Source[]{TevConfig.Source.PRIMARY_COLOR, TevConfig.Source.TEXTURE0, TevConfig.Source.PREVIOUS},
                new int[]{0, 0, 0}, TevConfig.CombinerOp.MODULATE, 0, 0);
        TevConfig config = new TevConfig(new TevConfig.Stage[]{modulate, modulate, modulate, modulate, modulate, modulate}, 0, 0);

        String glsl = TevGlslGenerator.generate(config);

        assertTrue(glsl.contains("rgb = src0 * src1"));
        assertTrue(glsl.contains("src1 = texture(tex0, vec2(0.0)).rgb"));
    }

    @Test
    void interpolateStageMixesWithThirdSourceAsFactor() {
        TevConfig.Stage interpolate = new TevConfig.Stage(
                new TevConfig.Source[]{TevConfig.Source.TEXTURE0, TevConfig.Source.PRIMARY_COLOR, TevConfig.Source.CONSTANT},
                new int[]{0, 0, 0}, TevConfig.CombinerOp.INTERPOLATE,
                new TevConfig.Source[]{TevConfig.Source.TEXTURE0, TevConfig.Source.PRIMARY_COLOR, TevConfig.Source.CONSTANT},
                new int[]{0, 0, 0}, TevConfig.CombinerOp.INTERPOLATE, 0, 0);
        TevConfig config = new TevConfig(new TevConfig.Stage[]{interpolate, interpolate, interpolate, interpolate, interpolate, interpolate}, 0, 0);

        String glsl = TevGlslGenerator.generate(config);

        assertTrue(glsl.contains("rgb = mix(src1, src0, src2)"));
    }
}
