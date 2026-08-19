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
                                   boolean[] boolConstants, int[] attributeToInputRegister, Screen screen,
                                   PicaRenderer renderer) {
        VertexAttributeLoader loader = new VertexAttributeLoader(registers);
        int offset = loader.vertexOffset();
        int count = loader.numVertices();
        List<Integer> indices = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            indices.add(offset + i);
        }
        draw(shader, executable, loader, memory, floatConstants, intConstants, boolConstants,
                attributeToInputRegister, indices, screen, renderer);
    }

    public static void drawElements(ShaderBinary shader, ShaderBinary.Executable executable, PicaRegisters registers,
                                     AddressSpace memory, float[][] floatConstants, int[][] intConstants,
                                     boolean[] boolConstants, int[] attributeToInputRegister, Screen screen,
                                     PicaRenderer renderer) {
        VertexAttributeLoader loader = new VertexAttributeLoader(registers);
        int count = loader.numVertices();
        List<Integer> indices = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            indices.add(loader.readIndex(memory, i));
        }
        draw(shader, executable, loader, memory, floatConstants, intConstants, boolConstants,
                attributeToInputRegister, indices, screen, renderer);
    }

    private static void draw(ShaderBinary shader, ShaderBinary.Executable executable, VertexAttributeLoader loader,
                              AddressSpace memory, float[][] floatConstants, int[][] intConstants,
                              boolean[] boolConstants, int[] attributeToInputRegister, List<Integer> vertexIndices,
                              Screen screen, PicaRenderer renderer) {
        ShaderBinary.OutputRegister position = findOutput(executable, ShaderBinary.OutputRegister.SEMANTIC_POSITION);
        ShaderBinary.OutputRegister color = findOutput(executable, ShaderBinary.OutputRegister.SEMANTIC_COLOR);

        List<ShadedVertex> shaded = new ArrayList<>(vertexIndices.size());
        for (int vertexIndex : vertexIndices) {
            float[][] attributes = loader.load(memory, vertexIndex);
            float[][] input = new float[VertexShaderInterpreter.NUM_INPUT_REGISTERS][4];
            for (int attributeId = 0; attributeId < attributeToInputRegister.length; attributeId++) {
                input[attributeToInputRegister[attributeId]] = attributes[attributeId];
            }

            float[][] output = VertexShaderInterpreter.run(shader, executable.mainOffset(), input, floatConstants,
                    intConstants, boolConstants);
            shaded.add(toShadedVertex(output[position.registerId()], output[color.registerId()]));
        }
        renderer.drawTriangles(screen, shaded);
    }

    private static ShaderBinary.OutputRegister findOutput(ShaderBinary.Executable executable, int semantic) {
        for (ShaderBinary.OutputRegister output : executable.outputRegisters()) {
            if (output.semanticType() == semantic) {
                return output;
            }
        }
        throw new IllegalArgumentException("shader não declara nenhuma saída de semântica " + semantic);
    }

    private static ShadedVertex toShadedVertex(float[] clipPosition, float[] color) {
        float w = clipPosition[3];
        float ndcX = clipPosition[0] / w;
        float ndcY = clipPosition[1] / w;
        return new ShadedVertex(ndcX, ndcY, color[0], color[1], color[2], color[3]);
    }
}
