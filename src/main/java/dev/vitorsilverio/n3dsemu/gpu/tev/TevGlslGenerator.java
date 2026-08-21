package dev.vitorsilverio.n3dsemu.gpu.tev;

/// Traduz uma {@link TevConfig} para o texto-fonte GLSL de um *fragment shader* (RFC-N3DSEMU
/// G5, decisão D5: "TEV traduzido para SPIR-V com cache"). Função **pura**
/// (`TevConfig → String`) — compilar para SPIR-V de verdade (`shaderc`) e cachear por
/// configuração é responsabilidade de quem consome o texto gerado (`VulkanRenderer`), não desta
/// classe (RFC/task: "escreva o gerador... e teste-o comparando a string gerada").
///
/// Cada estágio resolve 3 fontes de cor e 3 de alpha, aplica o **operando** de cada uma
/// (seleção de canal e/ou complemento `1-x` — ignorá-los é a armadilha explícita da task G5),
/// combina e escala. O resultado alimenta `previous` para o estágio seguinte, e os 4 primeiros
/// estágios podem gravar no *buffer* de cor combinada (`previousBuffer`) conforme
/// `GPUREG_TEXENV_UPDATE_BUFFER`.
///
/// O PICA200 trabalha em ponto fixo de 8 bits por canal, então todo resultado intermediário é
/// **saturado em `[0,1]`** — sem isso um `Add` estoura e o `Interpolate` seguinte usa um peso fora
/// da faixa.
public final class TevGlslGenerator {
    private TevGlslGenerator() {
    }

    // Modificadores de fonte de COR (Citra `TevStageConfig::ColorModifier`).
    private static final int COLOR_SOURCE_COLOR = 0;
    private static final int COLOR_ONE_MINUS_SOURCE_COLOR = 1;
    private static final int COLOR_SOURCE_ALPHA = 2;
    private static final int COLOR_ONE_MINUS_SOURCE_ALPHA = 3;
    private static final int COLOR_SOURCE_RED = 4;
    private static final int COLOR_ONE_MINUS_SOURCE_RED = 5;
    private static final int COLOR_SOURCE_GREEN = 8;
    private static final int COLOR_ONE_MINUS_SOURCE_GREEN = 9;
    private static final int COLOR_SOURCE_BLUE = 12;
    private static final int COLOR_ONE_MINUS_SOURCE_BLUE = 13;

    // Modificadores de fonte de ALPHA (Citra `TevStageConfig::AlphaModifier`).
    private static final int ALPHA_SOURCE_ALPHA = 0;
    private static final int ALPHA_ONE_MINUS_SOURCE_ALPHA = 1;
    private static final int ALPHA_SOURCE_RED = 2;
    private static final int ALPHA_ONE_MINUS_SOURCE_RED = 3;
    private static final int ALPHA_SOURCE_GREEN = 4;
    private static final int ALPHA_ONE_MINUS_SOURCE_GREEN = 5;
    private static final int ALPHA_SOURCE_BLUE = 6;
    private static final int ALPHA_ONE_MINUS_SOURCE_BLUE = 7;

    private static final int NUM_TEXTURE_UNITS = 4;

    /// Gera o `fragment shader` completo (`#version 450`) para `config`. Entradas interpoladas:
    /// `fragColor` (a cor do vértice = `PRIMARY_COLOR`) e `fragTexCoord0`-`2`. As unidades de
    /// textura são `sampler2D` nos *bindings* `0`-`3`; a unidade 3 (procedural no hardware real)
    /// amostra a mesma coordenada da unidade 0, o que basta para os casos não-procedurais.
    public static String generate(TevConfig config) {
        StringBuilder glsl = new StringBuilder();
        glsl.append("#version 450\n");
        glsl.append("layout(location = 0) in vec4 fragColor;\n");
        glsl.append("layout(location = 1) in vec2 fragTexCoord0;\n");
        glsl.append("layout(location = 2) in vec2 fragTexCoord1;\n");
        glsl.append("layout(location = 3) in vec2 fragTexCoord2;\n");
        glsl.append("layout(location = 0) out vec4 outColor;\n");
        for (int unit = 0; unit < NUM_TEXTURE_UNITS; unit++) {
            glsl.append("layout(binding = ").append(unit).append(") uniform sampler2D tex")
                    .append(unit).append(";\n");
        }
        glsl.append('\n');
        glsl.append("void main() {\n");
        glsl.append("    vec4 previous = vec4(0.0);\n");
        glsl.append("    vec4 previousBuffer = ").append(colorLiteral(config.bufferColorRgba8())).append(";\n");
        glsl.append("    vec4 nextBuffer = previousBuffer;\n");
        for (int i = 0; i < config.stages().size(); i++) {
            appendStage(glsl, config, i);
        }
        appendAlphaTest(glsl, config.alphaTest());
        glsl.append("    outColor = previous;\n");
        glsl.append("}\n");
        return glsl.toString();
    }

    private static void appendStage(StringBuilder glsl, TevConfig config, int index) {
        TevConfig.Stage stage = config.stages().get(index);
        glsl.append("    { // estágio ").append(index).append('\n');
        glsl.append("        previousBuffer = nextBuffer;\n");
        glsl.append("        vec4 constantColor = ").append(colorLiteral(stage.constantColorRgba8())).append(";\n");
        for (int i = 0; i < 3; i++) {
            glsl.append("        vec3 src").append(i).append(" = ")
                    .append(colorOperand(stage.colorOperand().get(i), source(stage.colorSource().get(i))))
                    .append(";\n");
            glsl.append("        float asrc").append(i).append(" = ")
                    .append(alphaOperand(stage.alphaOperand().get(i), source(stage.alphaSource().get(i))))
                    .append(";\n");
        }
        glsl.append("        vec3 rgb = clamp(")
                .append(combineRgb(stage.colorCombine(), "src0", "src1", "src2"))
                .append(", 0.0, 1.0) * ").append(1 << stage.colorScaleShift()).append(".0;\n");
        glsl.append("        float a = clamp(")
                .append(combineAlpha(stage.alphaCombine(), "asrc0", "asrc1", "asrc2"))
                .append(", 0.0, 1.0) * ").append(1 << stage.alphaScaleShift()).append(".0;\n");
        glsl.append("        previous = clamp(vec4(rgb, a), 0.0, 1.0);\n");
        if (config.stageUpdatesBufferColor(index)) {
            glsl.append("        nextBuffer.rgb = previous.rgb;\n");
        }
        if (config.stageUpdatesBufferAlpha(index)) {
            glsl.append("        nextBuffer.a = previous.a;\n");
        }
        glsl.append("    }\n");
    }

    private static void appendAlphaTest(StringBuilder glsl, TevConfig.AlphaTest alphaTest) {
        if (!alphaTest.enabled()) {
            return;
        }
        String reference = literal(alphaTest.reference() / 255f);
        String keep = switch (alphaTest.function()) {
            case NEVER -> "false";
            case ALWAYS -> "true";
            case EQUAL -> "previous.a == " + reference;
            case NOT_EQUAL -> "previous.a != " + reference;
            case LESS -> "previous.a < " + reference;
            case LESS_OR_EQUAL -> "previous.a <= " + reference;
            case GREATER -> "previous.a > " + reference;
            case GREATER_OR_EQUAL -> "previous.a >= " + reference;
        };
        glsl.append("    if (!(").append(keep).append(")) { discard; }\n");
    }

    /// Expressão `vec4` da fonte crua, ANTES do operando.
    private static String source(TevConfig.Source source) {
        return switch (source) {
            case PRIMARY_COLOR, FRAGMENT_PRIMARY_COLOR, FRAGMENT_SECONDARY_COLOR -> "fragColor";
            case TEXTURE0 -> "texture(tex0, fragTexCoord0)";
            case TEXTURE1 -> "texture(tex1, fragTexCoord1)";
            case TEXTURE2 -> "texture(tex2, fragTexCoord2)";
            case TEXTURE3 -> "texture(tex3, fragTexCoord0)";
            case PREVIOUS_BUFFER -> "previousBuffer";
            case CONSTANT -> "constantColor";
            case PREVIOUS -> "previous";
            default -> "vec4(0.0)";
        };
    }

    private static String colorOperand(int operand, String value) {
        return switch (operand) {
            case COLOR_SOURCE_COLOR -> value + ".rgb";
            case COLOR_ONE_MINUS_SOURCE_COLOR -> "vec3(1.0) - " + value + ".rgb";
            case COLOR_SOURCE_ALPHA -> "vec3(" + value + ".a)";
            case COLOR_ONE_MINUS_SOURCE_ALPHA -> "vec3(1.0 - " + value + ".a)";
            case COLOR_SOURCE_RED -> "vec3(" + value + ".r)";
            case COLOR_ONE_MINUS_SOURCE_RED -> "vec3(1.0 - " + value + ".r)";
            case COLOR_SOURCE_GREEN -> "vec3(" + value + ".g)";
            case COLOR_ONE_MINUS_SOURCE_GREEN -> "vec3(1.0 - " + value + ".g)";
            case COLOR_SOURCE_BLUE -> "vec3(" + value + ".b)";
            case COLOR_ONE_MINUS_SOURCE_BLUE -> "vec3(1.0 - " + value + ".b)";
            default -> value + ".rgb";
        };
    }

    private static String alphaOperand(int operand, String value) {
        return switch (operand) {
            case ALPHA_SOURCE_ALPHA -> value + ".a";
            case ALPHA_ONE_MINUS_SOURCE_ALPHA -> "1.0 - " + value + ".a";
            case ALPHA_SOURCE_RED -> value + ".r";
            case ALPHA_ONE_MINUS_SOURCE_RED -> "1.0 - " + value + ".r";
            case ALPHA_SOURCE_GREEN -> value + ".g";
            case ALPHA_ONE_MINUS_SOURCE_GREEN -> "1.0 - " + value + ".g";
            case ALPHA_SOURCE_BLUE -> value + ".b";
            case ALPHA_ONE_MINUS_SOURCE_BLUE -> "1.0 - " + value + ".b";
            default -> value + ".a";
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
            case ADD_MULTIPLY -> "min(" + a + " + " + b + ", vec3(1.0)) * " + c;
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
            case ADD_MULTIPLY -> "min(" + a + " + " + b + ", 1.0) * " + c;
        };
    }

    private static String colorLiteral(int rgba8) {
        return "vec4(" + literal((rgba8 & 0xFF) / 255f) + ", " + literal(((rgba8 >>> 8) & 0xFF) / 255f) + ", "
                + literal(((rgba8 >>> 16) & 0xFF) / 255f) + ", " + literal(((rgba8 >>> 24) & 0xFF) / 255f) + ")";
    }

    /// Literal GLSL sempre com ponto decimal — `1` sozinho é `int` em GLSL e não converte
    /// implicitamente para `float` em todos os contextos.
    private static String literal(float value) {
        String text = Float.toString(value);
        return text.contains(".") || text.contains("e") || text.contains("E") ? text : text + ".0";
    }
}
