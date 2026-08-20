package dev.vitorsilverio.n3dsemu.gpu.tev;

/// Traduz uma {@link TevConfig} para o texto-fonte GLSL de um *fragment shader* (RFC-N3DSEMU
/// G5/PR3, decisão D5: "TEV traduzido para SPIR-V com cache"). Função **pura**
/// (`TevConfig → String`) — compilar para SPIR-V de verdade (`shaderc`) e cachear por
/// configuração é responsabilidade de quem consome o texto gerado (`VulkanRenderer`), não desta
/// classe (RFC/task: "escreva o gerador... e teste-o comparando a string gerada").
///
/// Cada estágio vira uma chamada a uma função `tevStageN(vec4 previous, vec4 previousBuffer)`
/// que resolve as 3 fontes de cor/alpha, aplica o operando (RGB/RGBA/canal isolado/inverso) e a
/// operação de combinação — na mesma ordem que o hardware real processa (3dbrew: os estágios
/// alimentam `previous` para o próximo, e o penúltimo alimenta o "buffer" quando
/// `TEXENV_UPDATE_BUFFER` pede).
public final class TevGlslGenerator {
    private TevGlslGenerator() {
    }

    /// Gera o `fragment shader` completo (`#version 450`) para `config`. `in vec4 fragColor` é a
    /// cor interpolada do vértice (`PRIMARY_COLOR`); texturas (`TEXTURE0`-`TEXTURE3`) referenciam
    /// `sampler2D` binding `0`-`3` — a ligação real do descritor Vulkan a essas *bindings* é
    /// trabalho de PR futura (RFC/task: "texturas podem virar PR4"), este gerador já emite o GLSL
    /// correto para quando isso existir.
    public static String generate(TevConfig config) {
        StringBuilder glsl = new StringBuilder();
        glsl.append("#version 450\n");
        glsl.append("layout(location = 0) in vec4 fragColor;\n");
        glsl.append("layout(location = 0) out vec4 outColor;\n");
        glsl.append("layout(binding = 0) uniform sampler2D tex0;\n");
        glsl.append("layout(binding = 1) uniform sampler2D tex1;\n");
        glsl.append("layout(binding = 2) uniform sampler2D tex2;\n");
        glsl.append("layout(binding = 3) uniform sampler2D tex3;\n");
        glsl.append("\n");
        glsl.append("void main() {\n");
        glsl.append("    vec4 previous = vec4(1.0);\n");
        glsl.append("    vec4 previousBuffer = ").append(colorLiteral(config.bufferColorRgba8())).append(";\n");
        glsl.append("    vec4 constantColor = vec4(1.0);\n");
        for (int i = 0; i < config.stages().length; i++) {
            appendStage(glsl, config.stages()[i], i);
        }
        glsl.append("    outColor = previous;\n");
        glsl.append("}\n");
        return glsl.toString();
    }

    private static void appendStage(StringBuilder glsl, TevConfig.Stage stage, int index) {
        glsl.append("    { // estágio ").append(index).append('\n');
        glsl.append("        vec3 src0 = ").append(source(stage.colorSource()[0], true)).append(".rgb;\n");
        glsl.append("        vec3 src1 = ").append(source(stage.colorSource()[1], true)).append(".rgb;\n");
        glsl.append("        vec3 src2 = ").append(source(stage.colorSource()[2], true)).append(".rgb;\n");
        glsl.append("        float asrc0 = ").append(source(stage.alphaSource()[0], false)).append(".a;\n");
        glsl.append("        float asrc1 = ").append(source(stage.alphaSource()[1], false)).append(".a;\n");
        glsl.append("        float asrc2 = ").append(source(stage.alphaSource()[2], false)).append(".a;\n");
        glsl.append("        vec3 rgb = ").append(combineRgb(stage.colorCombine(), "src0", "src1", "src2"))
                .append(" * ").append(1 << stage.colorScaleShift()).append(".0;\n");
        glsl.append("        float a = ").append(combineAlpha(stage.alphaCombine(), "asrc0", "asrc1", "asrc2"))
                .append(" * ").append(1 << stage.alphaScaleShift()).append(".0;\n");
        glsl.append("        previous = vec4(rgb, a);\n");
        glsl.append("    }\n");
    }

    private static String source(TevConfig.Source source, boolean forColor) {
        return switch (source) {
            case PRIMARY_COLOR, FRAGMENT_PRIMARY_COLOR, FRAGMENT_SECONDARY_COLOR -> "fragColor";
            case TEXTURE0 -> "texture(tex0, vec2(0.0))";
            case TEXTURE1 -> "texture(tex1, vec2(0.0))";
            case TEXTURE2 -> "texture(tex2, vec2(0.0))";
            case TEXTURE3 -> "texture(tex3, vec2(0.0))";
            case PREVIOUS_BUFFER -> "previousBuffer";
            case CONSTANT -> "constantColor";
            case PREVIOUS -> "previous";
            default -> "vec4(0.0)";
        };
    }

    private static String combineRgb(TevConfig.CombinerOp op, String a, String b, String c) {
        return switch (op) {
            case REPLACE -> a;
            case MODULATE -> a + " * " + b;
            case ADD -> a + " + " + b;
            case ADD_SIGNED -> a + " + " + b + " - vec3(0.5)";
            case INTERPOLATE -> "mix(" + b + ", " + a + ", " + c + ")";
            case SUBTRACT -> a + " - " + b;
            case DOT3 -> "vec3(4.0 * dot(" + a + " - vec3(0.5), " + b + " - vec3(0.5)))";
            case MULTIPLY_ADD -> a + " * " + b + " + " + c;
            case ADD_MULTIPLY -> "(" + a + " + " + b + ") * " + c;
        };
    }

    private static String combineAlpha(TevConfig.CombinerOp op, String a, String b, String c) {
        return switch (op) {
            case REPLACE -> a;
            case MODULATE -> a + " * " + b;
            case ADD -> a + " + " + b;
            case ADD_SIGNED -> a + " + " + b + " - 0.5";
            case INTERPOLATE -> "mix(" + b + ", " + a + ", " + c + ")";
            case SUBTRACT -> a + " - " + b;
            case DOT3 -> a; // Dot3 não se aplica a alpha (3dbrew) — passa a fonte 0 adiante.
            case MULTIPLY_ADD -> a + " * " + b + " + " + c;
            case ADD_MULTIPLY -> "(" + a + " + " + b + ") * " + c;
        };
    }

    private static String colorLiteral(int rgba8) {
        float r = ((rgba8 >>> 0) & 0xFF) / 255f;
        float g = ((rgba8 >>> 8) & 0xFF) / 255f;
        float b = ((rgba8 >>> 16) & 0xFF) / 255f;
        float a = ((rgba8 >>> 24) & 0xFF) / 255f;
        return "vec4(" + r + ", " + g + ", " + b + ", " + a + ")";
    }
}
