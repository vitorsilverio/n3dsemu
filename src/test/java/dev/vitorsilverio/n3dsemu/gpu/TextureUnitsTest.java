package dev.vitorsilverio.n3dsemu.gpu;

import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.n3dsemu.memory.LoggingOpenBus;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Unidades de textura (`GPUREG_TEXUNIT*`) — RFC-N3DSEMU G5/PR4. Sem GPU: decodifica
/// registradores montados à mão e confere o desembaralho de Morton contra pixels conhecidos.
class TextureUnitsTest {
    private static final int PAGE_SHIFT = 12;
    private static final int TEXTURE_ADDRESS = 0x1800_0000;
    private static final int REG_CONFIG = 0x080;
    private static final int REG_DIMENSION_UNIT0 = 0x082;
    private static final int REG_ADDRESS_UNIT0 = 0x085;
    private static final int REG_FORMAT_UNIT0 = 0x08E;
    private static final int TILE = 8;

    @Test
    void unidadeDesligadaNaoProduzTextura() {
        PicaRegisters registers = new PicaRegisters();
        programTextureUnit0(registers, TILE, TILE, 0);
        registers.write(REG_CONFIG, 0, 0xF); // nenhuma unidade habilitada

        assertNull(TextureUnits.read(registers, newMemory(), 0));
    }

    @Test
    void formatoComprimidoAindaNaoESuportadoEDevolveNull() {
        PicaRegisters registers = new PicaRegisters();
        programTextureUnit0(registers, TILE, TILE, 12); // ETC1
        assertNull(TextureUnits.read(registers, newMemory(), 0));
    }

    @Test
    void dimensaoNaoMultiplaDeOitoERejeitada() {
        PicaRegisters registers = new PicaRegisters();
        programTextureUnit0(registers, 12, TILE, 0);
        assertNull(TextureUnits.read(registers, newMemory(), 0));
    }

    @Test
    void leRgba8DesembaralhandoAOrdemDeMorton() {
        PagedAddressSpace memory = newMemory();
        PicaRegisters registers = new PicaRegisters();
        programTextureUnit0(registers, TILE, TILE, 0); // RGBA8

        // Um único bloco 8×8: escreve um valor distinto no texel de Morton correspondente a
        // (x=1,y=0) e outro em (x=0,y=1). Se o desembaralho estiver errado, eles saem trocados —
        // é exatamente o sintoma de "quadradinhos embaralhados" que a task G5 descreve.
        writeTexel(memory, Texture.mortonIndexInTile(1, 0), 0xFF0000FF); // vermelho opaco
        writeTexel(memory, Texture.mortonIndexInTile(0, 1), 0x00FF00FF); // verde opaco

        PicaTexture texture = TextureUnits.read(registers, memory, 0);

        assertEquals(TILE, texture.width());
        assertEquals(TILE, texture.height());
        assertEquals(0xFF, pixelByte(texture, 1, 0, 0), "texel (1,0) deveria ser vermelho");
        assertEquals(0x00, pixelByte(texture, 1, 0, 1));
        assertEquals(0x00, pixelByte(texture, 0, 1, 0), "texel (0,1) deveria ser verde");
        assertEquals(0xFF, pixelByte(texture, 0, 1, 1));
    }

    @Test
    void cadaUnidadeLeDoSeuProprioBlocoDeRegistradores() {
        PagedAddressSpace memory = newMemory();
        PicaRegisters registers = new PicaRegisters();
        registers.write(REG_CONFIG, 0b111, 0xF);
        // Unidade 1: dimensão/endereço/formato em offsets próprios (0x092/0x095/0x096).
        registers.write(0x092, (TILE << 16) | TILE, 0xF);
        registers.write(0x095, TEXTURE_ADDRESS >>> 3, 0xF);
        registers.write(0x096, 0, 0xF);

        assertNull(TextureUnits.read(registers, memory, 0), "unidade 0 sem endereço não produz textura");
        assertEquals(TILE, TextureUnits.read(registers, memory, 1).width());
    }

    private static void programTextureUnit0(PicaRegisters registers, int width, int height, int format) {
        registers.write(REG_CONFIG, 0b111, 0xF);
        registers.write(REG_DIMENSION_UNIT0, (width << 16) | height, 0xF);
        registers.write(REG_ADDRESS_UNIT0, TEXTURE_ADDRESS >>> 3, 0xF);
        registers.write(REG_FORMAT_UNIT0, format, 0xF);
    }

    /// Escreve um texel `RGBA8` no índice `mortonIndex` do primeiro bloco. Na memória o PICA200
    /// guarda os componentes na ordem inversa do nome (`A,B,G,R`), então o valor `0xRRGGBBAA` é
    /// escrito com os bytes invertidos.
    private static void writeTexel(PagedAddressSpace memory, int mortonIndex, int rgba) {
        int base = TEXTURE_ADDRESS + mortonIndex * 4;
        memory.write8(base, rgba & 0xFF);
        memory.write8(base + 1, (rgba >>> 8) & 0xFF);
        memory.write8(base + 2, (rgba >>> 16) & 0xFF);
        memory.write8(base + 3, (rgba >>> 24) & 0xFF);
    }

    private static int pixelByte(PicaTexture texture, int x, int y, int channel) {
        return texture.rgba8()[(y * texture.width() + x) * 4 + channel] & 0xFF;
    }

    private static PagedAddressSpace newMemory() {
        PagedAddressSpace memory = new PagedAddressSpace(PAGE_SHIFT,
                new LoggingOpenBus(new PrintStream(OutputStream.nullOutputStream())));
        memory.mapRam(TEXTURE_ADDRESS, new byte[0x1000]);
        return memory;
    }
}
