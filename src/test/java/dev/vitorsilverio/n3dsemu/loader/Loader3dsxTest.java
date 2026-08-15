package dev.vitorsilverio.n3dsemu.loader;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Testa {@link Loader3dsx} contra um `.3DSX` sintético (relocações conhecidas de antemão) e
/// contra o `.3dsx` real do `testdata/` (`templates/application`, compilado com devkitARM
/// r68 + libctru 2.7.0 — ver `testdata/README.md`).
class Loader3dsxTest {
    private static final Path REAL_3DSX = Path.of("testdata/application.3dsx");

    @Test
    void magicAusenteFalha() {
        byte[] file = new byte[64];
        assertThrows(Bad3dsxException.class, () -> new Loader3dsx().load(file));
    }

    @Test
    void arquivoMenorQueCabecalhoFalha() {
        byte[] file = new byte[16];
        assertThrows(Bad3dsxException.class, () -> new Loader3dsx().load(file));
    }

    @Test
    void bssMaiorQueDataFalha() {
        byte[] file = new byte[32];
        ByteBuffer buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0, 0x58534433); // "3DSX"
        buffer.putShort(4, (short) 0x20); // headerSize
        buffer.putShort(6, (short) 8);    // relocHdrSize
        buffer.putInt(0x10, 4); // codeSegSize
        buffer.putInt(0x14, 0); // rodataSegSize
        buffer.putInt(0x18, 4); // dataSegSize
        buffer.putInt(0x1C, 8); // bssSize > dataSegSize
        assertThrows(Bad3dsxException.class, () -> new Loader3dsx().load(file));
    }

    /// Monta um `.3dsx` sintético mínimo (sem extensão SMDH/RomFS) com uma relocação
    /// absoluta no segmento code (aponta para o início do rodata) e uma relativa no segmento
    /// data (aponta de volta para o início do code), e confere que ambas foram corrigidas
    /// para {@link Loader3dsx#LOAD_BASE}.
    @Test
    void aplicaRelocacaoAbsolutaERelativa() {
        int codeSegSize = 8;   // 2 palavras
        int rodataSegSize = 4; // 1 palavra
        int dataSegSize = 8;   // inclui bss
        int bssSize = 4;

        SyntheticBuilder builder = new SyntheticBuilder(codeSegSize, rodataSegSize, dataSegSize, bssSize);
        builder.codeWord(0, 0x1111_1111); // palavra comum, não relocada
        // combinedOffset = codeSegSize (início do rodata), subType=0 (absoluta).
        builder.codeWord(1, codeSegSize);
        builder.absoluteReloc(SyntheticBuilder.SEGMENT_CODE, /* skip */ 1, /* patch */ 1);

        builder.rodataWord(0, 0xCAFE_BABE); // marcador, não relocado

        // combinedOffset = 0 (início do code), subType=0 (relativa, preserva sinal).
        builder.dataWord(0, 0);
        builder.relativeReloc(SyntheticBuilder.SEGMENT_DATA, /* skip */ 0, /* patch */ 1);

        Image3dsx image = new Loader3dsx().load(builder.build());

        assertEquals(Loader3dsx.LOAD_BASE, image.loadBase());
        assertEquals(Loader3dsx.LOAD_BASE, image.entryPoint());
        assertEquals(0x1111_1111, wordAt(image.code(), 0));
        // code[1] deve apontar para o início do rodata carregado.
        int rodataBase = Loader3dsx.LOAD_BASE + codeSegSize;
        assertEquals(rodataBase, wordAt(image.code(), 1));
        assertEquals(0xCAFE_BABE, wordAt(image.rodata(), 0));
        // data[0] é relativo: início do code (LOAD_BASE) menos o endereço da própria palavra
        // (LOAD_BASE + codeSegSize + rodataSegSize + 0).
        int dataPointerAddress = Loader3dsx.LOAD_BASE + codeSegSize + rodataSegSize;
        assertEquals(Loader3dsx.LOAD_BASE - dataPointerAddress, wordAt(image.dataWithBss(), 0));
        // bss (segunda metade do segmento data) fica zerada.
        assertEquals(0, wordAt(image.dataWithBss(), 1));
        assertEquals(codeSegSize + rodataSegSize + dataSegSize, image.totalSize());
    }

    @Test
    void carregaApplicationReal() throws IOException {
        byte[] file = Files.readAllBytes(REAL_3DSX);
        Image3dsx image = new Loader3dsx().load(file);

        assertEquals(Loader3dsx.LOAD_BASE, image.loadBase());
        assertEquals(Loader3dsx.LOAD_BASE, image.entryPoint());
        assertEquals(0xe53c, image.code().length);
        assertEquals(0xe44, image.rodata().length);
        assertEquals(0x4980, image.dataWithBss().length);
    }

    private static int wordAt(byte[] segment, int wordIndex) {
        return ByteBuffer.wrap(segment, wordIndex * 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /// Monta um `.3dsx` sintético (sem extensão SMDH/RomFS: `headerSize = 0x20`,
    /// `relocHdrSize = 8`, duas tabelas por segmento) byte a byte, para os testes exercitarem
    /// o parser e o algoritmo de relocação sem depender de um binário real.
    private static final class SyntheticBuilder {
        static final int SEGMENT_CODE = 0;
        static final int SEGMENT_RODATA = 1;
        static final int SEGMENT_DATA = 2;

        private final int codeSegSize;
        private final int rodataSegSize;
        private final int dataSegSize;
        private final int bssSize;
        private final byte[] code;
        private final byte[] rodata;
        private final byte[] dataFile;
        // [segmento][tabela] -> lista de (skip,patch) já serializada em bytes.
        private final ByteArrayOutputStream[][] relocEntries = new ByteArrayOutputStream[3][2];
        private final int[][] relocCounts = new int[3][2];

        SyntheticBuilder(int codeSegSize, int rodataSegSize, int dataSegSize, int bssSize) {
            this.codeSegSize = codeSegSize;
            this.rodataSegSize = rodataSegSize;
            this.dataSegSize = dataSegSize;
            this.bssSize = bssSize;
            this.code = new byte[codeSegSize];
            this.rodata = new byte[rodataSegSize];
            this.dataFile = new byte[dataSegSize - bssSize];
            for (int s = 0; s < 3; s++) {
                for (int t = 0; t < 2; t++) {
                    relocEntries[s][t] = new ByteArrayOutputStream();
                }
            }
        }

        void codeWord(int wordIndex, int value) {
            putWord(code, wordIndex, value);
        }

        void rodataWord(int wordIndex, int value) {
            putWord(rodata, wordIndex, value);
        }

        void dataWord(int wordIndex, int value) {
            putWord(dataFile, wordIndex, value);
        }

        void absoluteReloc(int segment, int skip, int patch) {
            addReloc(segment, 0, skip, patch);
        }

        void relativeReloc(int segment, int skip, int patch) {
            addReloc(segment, 1, skip, patch);
        }

        private void addReloc(int segment, int table, int skip, int patch) {
            relocCounts[segment][table]++;
            putU16(relocEntries[segment][table], skip);
            putU16(relocEntries[segment][table], patch);
        }

        byte[] build() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            putU32(out, 0x58534433); // "3DSX"
            putU16(out, 0x20);       // headerSize (sem extensão)
            putU16(out, 8);          // relocHdrSize (2 tabelas)
            putU32(out, 0);          // formatVer
            putU32(out, 0);          // flags
            putU32(out, codeSegSize);
            putU32(out, rodataSegSize);
            putU32(out, dataSegSize);
            putU32(out, bssSize);

            for (int s = 0; s < 3; s++) {
                for (int t = 0; t < 2; t++) {
                    putU32(out, relocCounts[s][t]);
                }
            }

            writeBytes(out, code);
            writeBytes(out, rodata);
            writeBytes(out, dataFile);

            for (int s = 0; s < 3; s++) {
                for (int t = 0; t < 2; t++) {
                    writeBytes(out, relocEntries[s][t].toByteArray());
                }
            }
            return out.toByteArray();
        }

        private static void putWord(byte[] segment, int wordIndex, int value) {
            ByteBuffer.wrap(segment, wordIndex * 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
        }

        private static void putU16(ByteArrayOutputStream out, int value) {
            out.write(value & 0xFF);
            out.write((value >>> 8) & 0xFF);
        }

        private static void putU32(ByteArrayOutputStream out, int value) {
            out.write(value & 0xFF);
            out.write((value >>> 8) & 0xFF);
            out.write((value >>> 16) & 0xFF);
            out.write((value >>> 24) & 0xFF);
        }

        private static void writeBytes(ByteArrayOutputStream out, byte[] data) {
            try {
                out.write(data);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
