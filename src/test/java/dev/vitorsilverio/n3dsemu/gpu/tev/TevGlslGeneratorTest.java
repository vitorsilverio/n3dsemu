package dev.vitorsilverio.n3dsemu.gpu.tev;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testa `TevConfig → String` comparando a saída gerada contra o que se espera para
/// configurações conhecidas (RFC-N3DSEMU G5: "compare a string gerada com uma esperada para
/// 2-3 configurações conhecidas — a do `simple_tri`, entre elas"). Não compila nada (sem
/// `shaderc`/GPU) — só a função pura.
class TevGlslGeneratorTest {
    private static final List<Integer> NO_MODIFIER = List.of(0, 0, 0);

    @Test
    void simpleTriConfigPassesFragColorThrough() {
        String glsl = TevGlslGenerator.generate(TevConfig.passthroughPrimaryColor());

        assertTrue(glsl.contains("#version 450"));
        assertTrue(glsl.contains("in vec4 fragColor"));
        assertTrue(glsl.contains("out vec4 outColor"));
        // estágio 0: replace com PRIMARY_COLOR -> a fonte usada é fragColor, sem textura.
        assertTrue(glsl.contains("src0 = fragColor.rgb"));
        assertTrue(glsl.contains("outColor = previous"));
        // sem teste de alpha configurado, nenhum `discard` é emitido.
        assertFalse(glsl.contains("discard"));
    }

    @Test
    void modulateStageMultipliesSources() {
        TevConfig config = uniform(stage(TevConfig.CombinerOp.MODULATE, NO_MODIFIER,
                TevConfig.Source.PRIMARY_COLOR, TevConfig.Source.TEXTURE0, TevConfig.Source.PREVIOUS));

        String glsl = TevGlslGenerator.generate(config);

        assertTrue(glsl.contains("rgb = clamp(src0 * src1, 0.0, 1.0)"));
        // A coordenada de textura agora é a INTERPOLADA do vértice, não `vec2(0.0)` fixo.
        assertTrue(glsl.contains("src1 = texture(tex0, fragTexCoord0).rgb"));
    }

    @Test
    void interpolateStageMixesWithThirdSourceAsFactor() {
        TevConfig config = uniform(stage(TevConfig.CombinerOp.INTERPOLATE, NO_MODIFIER,
                TevConfig.Source.TEXTURE0, TevConfig.Source.PRIMARY_COLOR, TevConfig.Source.CONSTANT));

        String glsl = TevGlslGenerator.generate(config);

        assertTrue(glsl.contains("rgb = clamp(mix(src1, src0, src2), 0.0, 1.0)"));
    }

    @Test
    void aplicaOsOperandosDeCadaFonte() {
        // operando 3 = `1 - alpha` (cor), operando 1 = `1 - alpha` (alpha).
        TevConfig config = uniform(stage(TevConfig.CombinerOp.REPLACE, List.of(3, 0, 0),
                TevConfig.Source.TEXTURE0, TevConfig.Source.PRIMARY_COLOR, TevConfig.Source.PREVIOUS));

        String glsl = TevGlslGenerator.generate(config);

        assertTrue(glsl.contains("src0 = vec3(1.0 - texture(tex0, fragTexCoord0).a)"), glsl);
    }

    @Test
    void emiteDiscardQuandoOTesteDeAlphaEstaLigado() {
        TevConfig base = TevConfig.passthroughPrimaryColor();
        TevConfig config = new TevConfig(base.stages(), 0, 0,
                new TevConfig.AlphaTest(true, TevConfig.CompareFunc.GREATER, 128));

        String glsl = TevGlslGenerator.generate(config);

        assertTrue(glsl.contains("discard"), glsl);
        assertTrue(glsl.contains("previous.a > 0.5019608"), glsl);
    }

    @Test
    void configuracoesIguaisSaoAMesmaChaveDeCache() {
        // O cache de shader do VulkanRenderer é um HashMap chaveado pela TevConfig inteira
        // (RFC/task G5) — sem `equals`/`hashCode` de verdade ele recompilaria a cada quadro.
        assertEquals(TevConfig.passthroughPrimaryColor(), TevConfig.passthroughPrimaryColor());
        assertEquals(TevConfig.passthroughPrimaryColor().hashCode(),
                TevConfig.passthroughPrimaryColor().hashCode());
    }

    private static TevConfig.Stage stage(TevConfig.CombinerOp op, List<Integer> colorOperand,
                                          TevConfig.Source source0, TevConfig.Source source1,
                                          TevConfig.Source source2) {
        List<TevConfig.Source> sources = List.of(source0, source1, source2);
        return new TevConfig.Stage(sources, colorOperand, op, sources, NO_MODIFIER, op, 0, 0, 0);
    }

    private static TevConfig uniform(TevConfig.Stage stage) {
        return new TevConfig(Collections.nCopies(6, stage), 0, 0, TevConfig.AlphaTest.DISABLED);
    }
}
