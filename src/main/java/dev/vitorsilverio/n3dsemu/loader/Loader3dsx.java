package dev.vitorsilverio.n3dsemu.loader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Leitor do formato `.3DSX` (<a href="https://www.3dbrew.org/wiki/3DSX_Format">3dbrew</a>),
/// incluindo a aplicação das tabelas de relocação.
///
/// O layout do cabeçalho vem da especificação do 3dbrew. A **ordem exata das seções no
/// arquivo** e a **semântica de aplicação das relocações** (o que "skip"/"patch" significam
/// ao percorrer um segmento, e a fórmula de cada tipo) não estão documentadas lá — foram
/// transcritas de duas fontes primárias e verificadas contra o `application.3dsx` real de
/// `testdata/`: o escritor de referência `3dsxtool.cpp` de `devkitPro/3dstools` (ordem real
/// das seções: cabeçalho → contagens de relocação → segmentos → entradas de relocação — a
/// página do 3dbrew lista "segmentos, depois relocações" mas não deixa essa ordem explícita)
/// e o carregador de referência do Homebrew Launcher, `app_bootloader/source/3dsx.c` de
/// `smealum/ninjhax2.x` (algoritmo de aplicação — mesmo mecanismo usado por todo carregador
/// de `.3dsx` real, incl. `3dslink`/hbmenu).
///
/// ### Algoritmo de relocação
/// Cada segmento (code/rodata/data) tem duas tabelas de relocação (absoluta e relativa; ver
/// {@link #KNOWN_RELOC_TABLES}). Cada entrada é um par `(skip, patch)`: pula `skip` palavras
/// de 4 bytes sem tocar, depois corrige `patch` palavras consecutivas. A palavra ANTES de
/// corrigida contém, nos 28 bits baixos, um deslocamento **combinado** dentro do layout
/// code+rodata+data (0 = início do código, `codeSegSize` = início do rodata, e assim por
/// diante) — por construção esse deslocamento combinado já é exatamente `endereço -
/// loadBase`, então traduzi-lo é simplesmente somar {@link #LOAD_BASE} (o carregador de
/// referência faz isso com uma busca por segmento porque monta os três ponteiros
/// separadamente antes de saber que eles são contíguos; aqui, como os três segmentos SÃO
/// carregados contíguos, a soma direta é equivalente e mais simples). Os 4 bits altos são o
/// `subType`: só usado no tipo relativo, para decidir se o bit de sinal do resultado é
/// preservado (`subType=0`) ou zerado (`subType=1`).
public final class Loader3dsx {
    private static final int MAGIC = 0x58534433; // "3DSX" little-endian

    private static final int OFF_MAGIC = 0x0;
    private static final int OFF_HEADER_SIZE = 0x4;
    private static final int OFF_RELOC_HDR_SIZE = 0x6;
    private static final int OFF_CODE_SEG_SIZE = 0x10;
    private static final int OFF_RODATA_SEG_SIZE = 0x14;
    private static final int OFF_DATA_SEG_SIZE = 0x18;
    private static final int OFF_BSS_SIZE = 0x1C;
    private static final int HEADER_SIZE_MIN = 0x20;

    /// Quantas tabelas de relocação por segmento este loader interpreta: absoluta (índice 0)
    /// e relativa (índice 1). Um `relocHdrSize` maior (tabelas extras, formato futuro) é
    /// aceito — as tabelas extras são lidas (para não desalinhar o parsing) e ignoradas,
    /// mesmo comportamento do carregador de referência.
    private static final int KNOWN_RELOC_TABLES = 2;
    private static final int RELOC_TABLE_ABSOLUTE = 0;
    private static final int RELOC_TABLE_RELATIVE = 1;

    private static final int SEGMENT_COUNT = 3;

    private static final int WORD_SIZE = 4;
    private static final int RELOC_ENTRY_SIZE = 4; // 2 x u16 (skip, patch)
    private static final int RELOC_ENCODED_ADDRESS_MASK = 0x0FFF_FFFF;
    private static final int RELOC_SUBTYPE_SHIFT = 28;
    private static final int RELOC_SUBTYPE_CLEAR_SIGN_BIT = 1;
    private static final int RELOC_SIGN_BIT = 0x8000_0000;

    /// Base de carga fixa do 3DSX no 3DS (RFC-N3DSEMU §3 e §4): sempre `0x00100000`, sem
    /// ASLR nem pedido de endereço pelo arquivo. Também o ponto de entrada.
    public static final int LOAD_BASE = 0x0010_0000;

    /// Analisa e relocaliza `file`, devolvendo os três segmentos já com os ponteiros
    /// corrigidos para {@link #LOAD_BASE}.
    public Image3dsx load(byte[] file) {
        try {
            return parse(file);
        } catch (IndexOutOfBoundsException e) {
            throw new Bad3dsxException("arquivo 3DSX truncado ou com deslocamento inválido: " + e.getMessage());
        }
    }

    private Image3dsx parse(byte[] file) {
        if (file.length < HEADER_SIZE_MIN) {
            throw new Bad3dsxException("arquivo menor que o cabeçalho 3DSX mínimo (" + file.length + " bytes)");
        }
        ByteBuffer buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt(OFF_MAGIC) != MAGIC) {
            throw new Bad3dsxException("magic 3DSX ausente");
        }
        int headerSize = buffer.getShort(OFF_HEADER_SIZE) & 0xFFFF;
        int relocHdrSize = buffer.getShort(OFF_RELOC_HDR_SIZE) & 0xFFFF;
        if (headerSize < HEADER_SIZE_MIN) {
            throw new Bad3dsxException("headerSize menor que o mínimo (" + HEADER_SIZE_MIN + "): " + headerSize);
        }
        if (relocHdrSize == 0 || relocHdrSize % WORD_SIZE != 0) {
            throw new Bad3dsxException("relocHdrSize deve ser múltiplo positivo de 4: " + relocHdrSize);
        }
        int nRelocTables = relocHdrSize / WORD_SIZE;

        int codeSegSize = buffer.getInt(OFF_CODE_SEG_SIZE);
        int rodataSegSize = buffer.getInt(OFF_RODATA_SEG_SIZE);
        int dataSegSize = buffer.getInt(OFF_DATA_SEG_SIZE); // inclui BSS
        int bssSize = buffer.getInt(OFF_BSS_SIZE);
        if (codeSegSize < 0 || rodataSegSize < 0 || dataSegSize < 0 || bssSize < 0 || bssSize > dataSegSize) {
            throw new Bad3dsxException("tamanhos de segmento inconsistentes (code=" + codeSegSize
                    + " rodata=" + rodataSegSize + " data=" + dataSegSize + " bss=" + bssSize + ")");
        }
        // headerSize > HEADER_SIZE_MIN indica a extensão (SMDH/RomFS, offset 0x20-0x2C) —
        // fora do escopo desta task (RFC D3: RomFS não é escopo; SMDH não é necessário para
        // rodar). O campo só precisa ser respeitado para não desalinhar o parsing: o
        // conteúdo dos segmentos começa em headerSize, qualquer que seja o tamanho da
        // extensão.
        int dataFileSize = dataSegSize - bssSize;

        // Ordem real no arquivo (confirmada contra o `application.3dsx` real de testdata/ e
        // contra o escritor de referência, `3dsxtool.cpp` do devkitPro/3dstools — a página do
        // 3dbrew resume a lista de seções mas não deixa a ORDEM óbvia): cabeçalho, depois os
        // 3 pares de contagem de relocação (`RelocHdr`), SÓ DEPOIS os 3 segmentos, e por
        // último as entradas de relocação propriamente ditas.
        int cursor = headerSize;
        int[][] relocCounts = new int[SEGMENT_COUNT][nRelocTables];
        for (int segment = 0; segment < SEGMENT_COUNT; segment++) {
            for (int table = 0; table < nRelocTables; table++) {
                relocCounts[segment][table] = readU32(file, cursor);
                cursor += WORD_SIZE;
            }
        }

        byte[] code = readSegment(file, cursor, codeSegSize, "code");
        cursor += codeSegSize;
        byte[] rodata = readSegment(file, cursor, rodataSegSize, "rodata");
        cursor += rodataSegSize;
        byte[] dataWithBss = new byte[dataSegSize];
        readInto(file, cursor, dataWithBss, dataFileSize, "data");
        cursor += dataFileSize;

        byte[][] segments = {code, rodata, dataWithBss};
        int[] segmentFileOffsets = {0, codeSegSize, codeSegSize + rodataSegSize};

        for (int segment = 0; segment < SEGMENT_COUNT; segment++) {
            for (int table = 0; table < nRelocTables; table++) {
                int count = relocCounts[segment][table];
                if (table >= KNOWN_RELOC_TABLES) {
                    cursor += count * RELOC_ENTRY_SIZE;
                    continue;
                }
                cursor = applyRelocations(segments[segment], segmentFileOffsets[segment], table, file, cursor, count);
            }
        }
        return new Image3dsx(LOAD_BASE, LOAD_BASE, code, rodata, dataWithBss);
    }

    private int applyRelocations(byte[] segment, int segmentFileOffset, int table, byte[] file, int cursor,
                                  int count) {
        int wordIndex = 0;
        int wordCount = segment.length / WORD_SIZE;
        for (int i = 0; i < count; i++) {
            int skip = readU16(file, cursor);
            cursor += 2;
            int patch = readU16(file, cursor);
            cursor += 2;
            wordIndex += skip;
            for (int p = 0; p < patch && wordIndex < wordCount; p++, wordIndex++) {
                int byteOffset = wordIndex * WORD_SIZE;
                int rawWord = readWord(segment, byteOffset);
                int subType = rawWord >>> RELOC_SUBTYPE_SHIFT;
                int combinedOffset = rawWord & RELOC_ENCODED_ADDRESS_MASK;
                // Ver Javadoc da classe: soma direta porque os 3 segmentos são contíguos.
                int targetAddress = LOAD_BASE + combinedOffset;
                int value = switch (table) {
                    case RELOC_TABLE_ABSOLUTE -> targetAddress;
                    case RELOC_TABLE_RELATIVE -> {
                        int pointerAddress = LOAD_BASE + segmentFileOffset + byteOffset;
                        int relative = targetAddress - pointerAddress;
                        yield subType == RELOC_SUBTYPE_CLEAR_SIGN_BIT ? relative & ~RELOC_SIGN_BIT : relative;
                    }
                    default -> throw new IllegalStateException("tabela de relocação desconhecida: " + table);
                };
                writeWord(segment, byteOffset, value);
            }
        }
        return cursor;
    }

    private static byte[] readSegment(byte[] file, int offset, int length, String name) {
        byte[] out = new byte[length];
        readInto(file, offset, out, length, name);
        return out;
    }

    private static void readInto(byte[] file, int offset, byte[] dest, int length, String name) {
        if (offset < 0 || length < 0 || offset + length > file.length) {
            throw new Bad3dsxException("segmento " + name + " fora dos limites do arquivo (offset=" + offset
                    + " length=" + length + " fileSize=" + file.length + ")");
        }
        System.arraycopy(file, offset, dest, 0, length);
    }

    private static int readU32(byte[] file, int offset) {
        if (offset < 0 || offset + WORD_SIZE > file.length) {
            throw new Bad3dsxException("cabeçalho de relocação truncado no deslocamento " + offset);
        }
        return ByteBuffer.wrap(file, offset, WORD_SIZE).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static int readU16(byte[] file, int offset) {
        if (offset < 0 || offset + 2 > file.length) {
            throw new Bad3dsxException("entrada de relocação truncada no deslocamento " + offset);
        }
        return ByteBuffer.wrap(file, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
    }

    private static int readWord(byte[] segment, int offset) {
        return ByteBuffer.wrap(segment, offset, WORD_SIZE).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static void writeWord(byte[] segment, int offset, int value) {
        ByteBuffer.wrap(segment, offset, WORD_SIZE).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
    }
}
