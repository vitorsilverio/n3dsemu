package dev.vitorsilverio.n3dsemu.gpu;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.n3dsemu.gpu.shader.ShaderBinary;
import dev.vitorsilverio.n3dsemu.gpu.shader.VertexShaderInterpreter;

import java.util.ArrayList;
import java.util.List;

/// Liga {@link VertexAttributeLoader} + {@link VertexShaderInterpreter} + {@link PicaRenderer}
/// (RFC-N3DSEMU G5/PR2): para cada vértice de um `DrawArrays`/`DrawElements`, carrega os
/// atributos da memória do guest, roda o vertex shader interpretado, extrai posição (com divisão
/// de perspectiva) e cor pela tabela de saída do `.shbin`, e entrega ao `PicaRenderer`.
///
/// **Simplificação documentada desta PR**: o mapeamento *atributo → registrador de entrada do
/// shader* (`v0`-`v15`) é recebido explicitamente do chamador (`attributeToInputRegister`), em
/// vez de decodificado dos registradores de permutação ao vivo da GPU real
/// (`GPUREG_VS_ATTRIBUTES_PERMUTATION_LOW/HIGH`, `0x2BB`/`0x2BC`) — essa integração com o
/// pipeline de comando ao vivo (incl. o upload do próprio `.shbin` via FIFO de registrador) fica
/// para a PR seguinte; identidade (`atributo i → vi`) cobre o caso comum e é o que os testes
/// desta PR usam.
public final class VertexPipeline {
    private VertexPipeline() {
    }

    public static void drawArrays(ShaderBinary shader, ShaderBinary.Executable executable, PicaRegisters registers,
                                   AddressSpace memory, float[][] floatConstants, int[][] intConstants,
                                   boolean[] boolConstants, int[] attributeToInputRegister, FixedAttributes fixedAttributes,
                                   Screen screen, PicaRenderer renderer) {
        VertexAttributeLoader loader = new VertexAttributeLoader(registers);
        int offset = loader.vertexOffset();
        int count = loader.numVertices();
        List<Integer> indices = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            indices.add(offset + i);
        }
        draw(shader, executable, loader, memory, floatConstants, intConstants, boolConstants,
                attributeToInputRegister, fixedAttributes, indices, screen, renderer);
    }

    public static void drawElements(ShaderBinary shader, ShaderBinary.Executable executable, PicaRegisters registers,
                                     AddressSpace memory, float[][] floatConstants, int[][] intConstants,
                                     boolean[] boolConstants, int[] attributeToInputRegister, FixedAttributes fixedAttributes,
                                     Screen screen, PicaRenderer renderer) {
        VertexAttributeLoader loader = new VertexAttributeLoader(registers);
        int count = loader.numVertices();
        List<Integer> indices = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            indices.add(loader.readIndex(memory, i));
        }
        draw(shader, executable, loader, memory, floatConstants, intConstants, boolConstants,
                attributeToInputRegister, fixedAttributes, indices, screen, renderer);
    }

    private static void draw(ShaderBinary shader, ShaderBinary.Executable executable, VertexAttributeLoader loader,
                              AddressSpace memory, float[][] floatConstants, int[][] intConstants,
                              boolean[] boolConstants, int[] attributeToInputRegister, FixedAttributes fixedAttributes,
                              List<Integer> vertexIndices, Screen screen, PicaRenderer renderer) {
        OutputMap outputMap = OutputMap.decode(loader.registers());

        List<ShadedVertex> shaded = new ArrayList<>(vertexIndices.size());
        for (int vertexIndex : vertexIndices) {
            float[][] attributes = loader.load(memory, vertexIndex);
            float[][] input = new float[VertexShaderInterpreter.NUM_INPUT_REGISTERS][4];
            int activeAttributes = Math.min(loader.numAttributes(), attributeToInputRegister.length);
            for (int attributeId = 0; attributeId < activeAttributes; attributeId++) {
                // Um atributo sem *loader* de array é FIXO: o valor vem dos registradores
                // `GPUREG_FIXEDATTRIB_*`, não da memória (achado real da G5.2 — a cor de
                // `simple_tri` é um atributo fixo branco, e sem este caminho o triângulo saía
                // preto/transparente, indistinguível de "não desenhou").
                input[attributeToInputRegister[attributeId]] = loader.isLoadedFromArray(attributeId)
                        ? attributes[attributeId]
                        : fixedAttributes.value(attributeId);
            }

            float[][] output = VertexShaderInterpreter.run(shader, executable.mainOffset(), input, floatConstants,
                    intConstants, boolConstants);
            shaded.add(toShadedVertex(outputMap, executable, output));
        }
        renderer.drawTriangles(screen, shaded);
    }

    /// Distribui a saída crua do shader por semântica (RFC-N3DSEMU G5/PR4) e monta o vértice
    /// final. Se a lista de comandos ainda não programou o `SH_OUTMAP` (banco de registradores
    /// zerado — o caso dos testes unitários que montam o estado à mão), cai na convenção do
    /// `picasso`/`citro3d` que a PR3 assumia: `o0`=posição, `o1`=cor, sem textura.
    private static ShadedVertex toShadedVertex(OutputMap outputMap, ShaderBinary.Executable executable,
                                                float[][] output) {
        if (outputMap.isUnprogrammed()) {
            return fromPicassoConvention(executable, output);
        }
        float[] bySemantic = outputMap.gather(output);
        return project(
                bySemantic[OutputMap.SEMANTIC_POSITION_X], bySemantic[OutputMap.SEMANTIC_POSITION_X + 1],
                bySemantic[OutputMap.SEMANTIC_POSITION_X + 3],
                new float[]{bySemantic[OutputMap.SEMANTIC_COLOR_R], bySemantic[OutputMap.SEMANTIC_COLOR_R + 1],
                        bySemantic[OutputMap.SEMANTIC_COLOR_R + 2], bySemantic[OutputMap.SEMANTIC_COLOR_R + 3]},
                new float[]{bySemantic[OutputMap.SEMANTIC_TEXCOORD0_U], bySemantic[OutputMap.SEMANTIC_TEXCOORD0_U + 1],
                        bySemantic[OutputMap.SEMANTIC_TEXCOORD1_U], bySemantic[OutputMap.SEMANTIC_TEXCOORD1_U + 1],
                        bySemantic[OutputMap.SEMANTIC_TEXCOORD2_U], bySemantic[OutputMap.SEMANTIC_TEXCOORD2_U + 1]});
    }

    private static ShadedVertex fromPicassoConvention(ShaderBinary.Executable executable, float[][] output) {
        float[] clipPosition = output[findOutput(executable, ShaderBinary.OutputRegister.SEMANTIC_POSITION)];
        float[] color = output[findOutput(executable, ShaderBinary.OutputRegister.SEMANTIC_COLOR)];
        return project(clipPosition[0], clipPosition[1], clipPosition[3], color, new float[6]);
    }

    private static int findOutput(ShaderBinary.Executable executable, int semantic) {
        for (ShaderBinary.OutputRegister output : executable.outputRegisters()) {
            if (output.semanticType() == semantic) {
                return output.registerId();
            }
        }
        throw new IllegalArgumentException("shader não declara nenhuma saída de semântica " + semantic);
    }

    private static ShadedVertex project(float clipX, float clipY, float clipW, float[] color, float[] texCoords) {
        return new ShadedVertex(clipX / clipW, clipY / clipW, color[0], color[1], color[2], color[3],
                texCoords[0], texCoords[1], texCoords[2], texCoords[3], texCoords[4], texCoords[5]);
    }
}
