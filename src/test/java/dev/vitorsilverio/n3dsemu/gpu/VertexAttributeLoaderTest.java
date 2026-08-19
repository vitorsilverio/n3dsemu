package dev.vitorsilverio.n3dsemu.gpu;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// RFC-N3DSEMU G5/PR2: monta registradores de formato de vértice à mão (sem GPU, sem guest real)
/// e confere os atributos lidos contra valores conhecidos escritos numa memória fake — mesma
/// técnica de teste do PR1 (`CommandListParserTest`).
class VertexAttributeLoaderTest {
    private static final int BASE_ADDRESS_PHYSICAL = 0x1000;
    private static final int VERTEX_STRIDE_BYTES = 28; // 3 floats (posição) + 4 floats (cor)

    @Test
    void loadsInterleavedPositionAndColorAttributesForEachVertex() {
        PicaRegisters registers = new PicaRegisters();
        configureInterleavedVertexFormat(registers);
        FakeAddressSpace memory = new FakeAddressSpace();
        writeVertex(memory, 0, new float[]{1f, 0f, 0f}, new float[]{1f, 0f, 0f, 1f});
        writeVertex(memory, 1, new float[]{0f, 1f, 0f}, new float[]{0f, 1f, 0f, 1f});
        writeVertex(memory, 2, new float[]{0f, 0f, 1f}, new float[]{0f, 0f, 1f, 1f});

        VertexAttributeLoader loader = new VertexAttributeLoader(registers);
        assertEquals(BASE_ADDRESS_PHYSICAL, loader.baseAddress());
        assertEquals(3, loader.numVertices());

        float[][] v0 = loader.load(memory, 0);
        assertArrayEqualsPrefix(new float[]{1f, 0f, 0f}, v0[0], 3);
        assertArrayEqualsPrefix(new float[]{1f, 0f, 0f, 1f}, v0[1], 4);

        float[][] v2 = loader.load(memory, 2);
        assertArrayEqualsPrefix(new float[]{0f, 0f, 1f}, v2[0], 3);
        assertArrayEqualsPrefix(new float[]{0f, 0f, 1f, 1f}, v2[1], 4);
    }

    @Test
    void decodesFloatFormatAndComponentCountFromFormatRegisters() {
        PicaRegisters registers = new PicaRegisters();
        configureInterleavedVertexFormat(registers);

        VertexAttributeLoader loader = new VertexAttributeLoader(registers);
        assertEquals(VertexAttributeLoader.Format.FLOAT, loader.format(0));
        assertEquals(3, loader.numComponents(0));
        assertEquals(VertexAttributeLoader.Format.FLOAT, loader.format(1));
        assertEquals(4, loader.numComponents(1));
    }

    private static void configureInterleavedVertexFormat(PicaRegisters registers) {
        // 0x200: base_address (bits 1-28) * 16 = endereço físico — ver Javadoc de
        // VertexAttributeLoader#baseAddress.
        registers.write(0x200, (BASE_ADDRESS_PHYSICAL / 16) << 1, 0xF);

        // 0x201 FORMAT_LOW: atributo0 = FLOAT/3 componentes (nibble 0xB), atributo1 = FLOAT/4
        // componentes (nibble 0xF).
        int attr0 = 0x3 | (0x2 << 2); // format=FLOAT(3), size-1=2 (3 componentes)
        int attr1 = 0x3 | (0x3 << 2); // format=FLOAT(3), size-1=3 (4 componentes)
        registers.write(0x201, attr0 | (attr1 << 4), 0xF);

        // 0x203-0x205: loader 0 — data_offset=0, comp0=atributo0, comp1=atributo1,
        // byte_count(stride)=28, component_count=2.
        registers.write(0x203, 0, 0xF);
        registers.write(0x204, 0x10, 0xF); // comp0=0, comp1=1
        registers.write(0x205, (VERTEX_STRIDE_BYTES << 16) | (2 << 28), 0xF);

        registers.write(0x228, 3, 0xF); // num_vertices
    }

    private static void writeVertex(FakeAddressSpace memory, int index, float[] position, float[] color) {
        int base = BASE_ADDRESS_PHYSICAL + index * VERTEX_STRIDE_BYTES;
        for (int i = 0; i < 3; i++) {
            memory.write32(base + i * 4, Float.floatToIntBits(position[i]));
        }
        for (int i = 0; i < 4; i++) {
            memory.write32(base + 12 + i * 4, Float.floatToIntBits(color[i]));
        }
    }

    private static void assertArrayEqualsPrefix(float[] expected, float[] actual, int length) {
        for (int i = 0; i < length; i++) {
            assertEquals(expected[i], actual[i], 1e-6f, "componente " + i);
        }
    }

    /// `AddressSpace` mínimo, byte-a-byte, endereçado por `Map<Integer,Byte>` — suficiente para
    /// um teste determinístico sem GPU nem guest real (mesmo espírito de
    /// `GuestFrameBufferReader`, que também só usa `read8`).
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
