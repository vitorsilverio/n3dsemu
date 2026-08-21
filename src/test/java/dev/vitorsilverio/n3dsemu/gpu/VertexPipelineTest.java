package dev.vitorsilverio.n3dsemu.gpu;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.n3dsemu.gpu.shader.ShaderBinary;
import dev.vitorsilverio.n3dsemu.gpu.shader.VertexShaderInterpreter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// RFC-N3DSEMU G5/PR2: ponta a ponta, SEM GPU — registradores de formato de vértice montados à
/// mão + memória fake + `.shbin` REAL do `simple_tri` (mesma fixture de
/// `VertexShaderInterpreterTest`) → {@link VertexPipeline#drawArrays} → {@link RecordingRenderer}.
/// Exercita a cadeia inteira que a G5 promete: carregar atributos, interpretar o shader, aplicar
/// a divisão de perspectiva e entregar triângulos sombreados ao renderer.
class VertexPipelineTest {
    private static final Path SIMPLE_TRI_SHBIN = Path.of("testdata/shaders/simple_tri.shbin");
    private static final int BASE_ADDRESS = 0x2000;
    private static final int VERTEX_STRIDE_BYTES = 28; // 3 floats posição + 4 floats cor

    @Test
    void drawsTriangleWithIdentityProjectionAndPassthroughColor() throws IOException {
        ShaderBinary shader = ShaderBinary.parse(Files.readAllBytes(SIMPLE_TRI_SHBIN));
        ShaderBinary.Executable exec = shader.executables().get(0);

        PicaRegisters registers = new PicaRegisters();
        configureVertexFormat(registers);
        FakeAddressSpace memory = new FakeAddressSpace();
        float[][] positions = {{-1f, -1f, 0f}, {1f, -1f, 0f}, {0f, 1f, 0f}};
        float[][] colors = {{1f, 0f, 0f, 1f}, {0f, 1f, 0f, 1f}, {0f, 0f, 1f, 1f}};
        for (int i = 0; i < 3; i++) {
            writeVertex(memory, i, positions[i], colors[i]);
        }

        float[][] floatConstants = VertexShaderInterpreter.embeddedFloatConstants(exec);
        floatConstants[0] = new float[]{1f, 0f, 0f, 0f};
        floatConstants[1] = new float[]{0f, 1f, 0f, 0f};
        floatConstants[2] = new float[]{0f, 0f, 1f, 0f};
        floatConstants[3] = new float[]{0f, 0f, 0f, 1f};

        RecordingRenderer renderer = new RecordingRenderer();
        VertexPipeline.drawArrays(shader, exec, registers, memory, floatConstants,
                VertexShaderInterpreter.embeddedIntConstants(exec), VertexShaderInterpreter.embeddedBoolConstants(exec),
                new int[]{0, 1}, new FixedAttributes(), Screen.TOP, renderer);

        List<ShadedVertex> triangle = renderer.lastTriangles(Screen.TOP);
        assertEquals(3, triangle.size());
        for (int i = 0; i < 3; i++) {
            ShadedVertex vertex = triangle.get(i);
            assertEquals(positions[i][0], vertex.ndcX(), 1e-6f);
            assertEquals(positions[i][1], vertex.ndcY(), 1e-6f);
            assertEquals(colors[i][0], vertex.r(), 1e-6f);
            assertEquals(colors[i][1], vertex.g(), 1e-6f);
            assertEquals(colors[i][2], vertex.b(), 1e-6f);
            assertEquals(colors[i][3], vertex.a(), 1e-6f);
        }
    }

    private static void configureVertexFormat(PicaRegisters registers) {
        registers.write(0x200, (BASE_ADDRESS / 16) << 1, 0xF);
        int attr0 = 0x3 | (0x2 << 2); // FLOAT, 3 componentes
        int attr1 = 0x3 | (0x3 << 2); // FLOAT, 4 componentes
        registers.write(0x201, attr0 | (attr1 << 4), 0xF);
        // 0x202 bits 28-31 = max_attribute_index: 2 atributos ativos (posição + cor).
        registers.write(0x202, 1 << 28, 0xF);
        registers.write(0x203, 0, 0xF);
        registers.write(0x204, 0x10, 0xF); // comp0=atributo0, comp1=atributo1
        registers.write(0x205, (VERTEX_STRIDE_BYTES << 16) | (2 << 28), 0xF);
        registers.write(0x228, 3, 0xF); // num_vertices
    }

    private static void writeVertex(FakeAddressSpace memory, int index, float[] position, float[] color) {
        int base = BASE_ADDRESS + index * VERTEX_STRIDE_BYTES;
        for (int i = 0; i < 3; i++) {
            memory.write32(base + i * 4, Float.floatToIntBits(position[i]));
        }
        for (int i = 0; i < 4; i++) {
            memory.write32(base + 12 + i * 4, Float.floatToIntBits(color[i]));
        }
    }

    private static final class FakeAddressSpace implements AddressSpace {
        private final Map<Integer, Byte> bytes = new HashMap<>();

        @Override
        public int read8(int address) {
            return bytes.getOrDefault(address, (byte) 0) & 0xFF;
        }

        @Override
        public int read16(int address) {
            return read8(address) | (read8(address + 1) << 8);
        }

        @Override
        public int read32(int address) {
            return read16(address) | (read16(address + 2) << 16);
        }

        @Override
        public void write8(int address, int value) {
            bytes.put(address, (byte) value);
        }

        @Override
        public void write16(int address, int value) {
            write8(address, value & 0xFF);
            write8(address + 1, (value >>> 8) & 0xFF);
        }

        @Override
        public void write32(int address, int value) {
            write16(address, value & 0xFFFF);
            write16(address + 2, (value >>> 16) & 0xFFFF);
        }
    }
}
