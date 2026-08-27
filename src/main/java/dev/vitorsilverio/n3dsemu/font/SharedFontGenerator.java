package dev.vitorsilverio.n3dsemu.font;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// Gera a fonte compartilhada de `APT:GetSharedFont` (task G6.5 — ver
/// `arm-jitter/tasks/trilha-g-3ds/g6.5-apt-inquire-checknew3ds-sharedfont.md`), no formato
/// **BCFNT em memória** ("CFNU", 3dbrew: [BCFNT](https://www.3dbrew.org/wiki/BCFNT)) — só a
/// versão 3 (a real usada pelo Old3DS), com endereços já ABSOLUTOS (não relativos a arquivo,
/// diferente de um `.bcfnt` em disco): esta HLE nunca precisa "relocar" a fonte como o hardware
/// real faz (`APT_GetSharedFont` real recalcula os offsets para o endereço onde o kernel decidiu
/// mapear a página, ver `RelocateSharedFont` do Citra/lime3ds) porque {@link #build} já sabe o
/// endereço final de antemão ({@code baseAddress}, escolhido fixo por
/// {@code MemoryMap#SHARED_FONT_BASE}) — grava os offsets absolutos direto, sem uma segunda
/// passada de "descobrir e then somar delta" como o hardware real precisa fazer (ver Javadoc da
/// task para o raciocínio completo).
///
/// **Fonte dos glifos**: renderizada a partir do TTF `Noto Sans` (licença SIL Open Font License
/// 1.1, embutido em `src/main/resources/fonts/NotoSans.ttf`+`OFL.txt`) via `java.awt.Font` — sem
/// nenhuma dependência do dump `shared_font.bin` de um 3DS real (decisão do usuário, ver memória
/// `n3dsemu-g4-font-decision` e a task G6.5: usar fonte de código aberto em vez de hardware real).
/// Cobre só ASCII imprimível (`0x20`-`0x7E`, 95 glifos) — suficiente para o corpus homebrew desta
/// trilha (`C2D_TextParse`/console, nenhum exemplo usa fora de ASCII).
public final class SharedFontGenerator {
    private SharedFontGenerator() {
    }

    private static final String FONT_RESOURCE = "/fonts/NotoSans.ttf";
    private static final float POINT_SIZE = 16f;

    private static final int FIRST_CODE_POINT = 0x20;
    private static final int LAST_CODE_POINT = 0x7E;
    private static final int GLYPH_COUNT = LAST_CODE_POINT - FIRST_CODE_POINT + 1;
    /// Colunas da grade do atlas — só afeta o formato da textura (`num_columns`/`sheet_width`),
    /// sem significado arquitetural; 16 dá uma grade quase quadrada para 95 glifos (6 linhas).
    private static final int SHEET_COLUMNS = 16;
    private static final int SHEET_ROWS = (GLYPH_COUNT + SHEET_COLUMNS - 1) / SHEET_COLUMNS;

    /// `SheetFormat.A8` (3dbrew BCFNT, `enum SheetFormat`: índice 8) — 1 byte de alfa por pixel,
    /// sem cor (glifos são desenhados com a cor do próprio guest); o formato mais simples de
    /// gerar a partir de um canal alfa anti-aliased do AWT, sem perda de informação de cobertura.
    private static final int SHEET_FORMAT_A8 = 8;
    private static final int BYTES_PER_PIXEL_A8 = 1;

    private static final int CMAP_METHOD_DIRECT = 0;

    private static final int CFNT_HEADER_SIZE = 0x14;
    private static final int FINF_SECTION_SIZE = 0x20;
    private static final int CMAP_SECTION_SIZE = 0x18;
    private static final int CWDH_HEADER_SIZE = 0x10;
    private static final int TGLP_HEADER_SIZE = 0x20;
    private static final int SECTION_HEADER_SIZE = 8;

    /// Cabeçalho extra de 0x80 bytes que precede a própria `CFNT` na cópia MAPEADA em memória
    /// (não existe num `.bcfnt` em disco) — 3dbrew: "the header is changed from CFNT to CFNU
    /// [...] all file offsets are changed to absolute in memory offsets"; layout de
    /// `status`/`region`/`decompressed_size`+preenchimento conferido contra
    /// `Module::LoadSharedFont` real do `lime3ds` (fork ativo do Citra, via `WebFetch`).
    private static final int SHARED_MEMORY_HEADER_SIZE = 0x80;
    private static final int HEADER_STATUS_LOADED = 2;
    private static final int HEADER_REGION_STANDARD = 1;

    private record Metrics(int cellWidth, int cellHeight, int ascent, int maxAdvance, int[] advance) {
    }

    /// Constrói a fonte compartilhada completa (cabeçalho de 0x80 + `CFNU`/`FINF`/`CMAP`/`CWDH`/
    /// `TGLP`+atlas), com todos os offsets internos já absolutos em relação a {@code baseAddress}
    /// — o endereço em que {@link dev.vitorsilverio.n3dsemu.service.AptService} vai mapear este
    /// array via `PagedAddressSpace#mapRam`. O array devolvido já vem com o tamanho múltiplo de
    /// `pageSize` (preenchido com zeros), pronto para `mapRam`.
    public static byte[] build(int baseAddress, int pageSize) {
        Font font = loadFont();
        Metrics metrics = measure(font);
        byte[] sheet = rasterizeSheet(font, metrics);

        int cfntStart = SHARED_MEMORY_HEADER_SIZE;
        int finfStart = cfntStart + CFNT_HEADER_SIZE;
        int cmapStart = finfStart + FINF_SECTION_SIZE;
        int cwdhStart = cmapStart + CMAP_SECTION_SIZE;
        int cwdhSectionSize = roundUpToMultipleOf4(CWDH_HEADER_SIZE + 3 * GLYPH_COUNT);
        int tglpStart = cwdhStart + cwdhSectionSize;
        int tglpSectionSize = TGLP_HEADER_SIZE + sheet.length;
        int contentEnd = tglpStart + tglpSectionSize;

        int totalSize = roundUpToMultipleOf(contentEnd, pageSize);
        ByteBuffer buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);

        writeSharedMemoryHeader(buffer, contentEnd - SHARED_MEMORY_HEADER_SIZE);
        writeCfntHeader(buffer, cfntStart, contentEnd - cfntStart);
        writeFinf(buffer, finfStart, metrics, baseAddress, tglpStart, cwdhStart, cmapStart);
        writeCmap(buffer, cmapStart);
        writeCwdh(buffer, cwdhStart, cwdhSectionSize, metrics);
        writeTglp(buffer, tglpStart, tglpSectionSize, metrics, baseAddress, sheet);

        return buffer.array();
    }

    private static Font loadFont() {
        try (InputStream in = SharedFontGenerator.class.getResourceAsStream(FONT_RESOURCE)) {
            Objects.requireNonNull(in, "recurso de fonte ausente: " + FONT_RESOURCE);
            return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(Font.PLAIN, POINT_SIZE);
        } catch (IOException e) {
            throw new UncheckedIOException("falha lendo " + FONT_RESOURCE, e);
        } catch (java.awt.FontFormatException e) {
            throw new IllegalStateException(FONT_RESOURCE + " não é um TTF válido", e);
        }
    }

    private static Metrics measure(Font font) {
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scratch.createGraphics();
        try {
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            int[] advance = new int[GLYPH_COUNT];
            int maxAdvance = 0;
            for (int i = 0; i < GLYPH_COUNT; i++) {
                int width = fm.charWidth(FIRST_CODE_POINT + i);
                advance[i] = width;
                maxAdvance = Math.max(maxAdvance, width);
            }
            int cellHeight = fm.getAscent() + fm.getDescent();
            int cellWidth = Math.max(1, maxAdvance);
            return new Metrics(cellWidth, cellHeight, fm.getAscent(), maxAdvance, advance);
        } finally {
            g.dispose();
        }
    }

    /// Desenha os 95 glifos num único atlas ARGB e devolve só o canal alfa (cobertura
    /// anti-aliased do glifo), 1 byte por pixel — formato `A8` do TGLP.
    private static byte[] rasterizeSheet(Font font, Metrics metrics) {
        int sheetWidth = SHEET_COLUMNS * metrics.cellWidth();
        int sheetHeight = SHEET_ROWS * metrics.cellHeight();
        BufferedImage image = new BufferedImage(sheetWidth, sheetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(font);
            g.setColor(java.awt.Color.WHITE);
            for (int i = 0; i < GLYPH_COUNT; i++) {
                int column = i % SHEET_COLUMNS;
                int row = i / SHEET_COLUMNS;
                int cellX = column * metrics.cellWidth();
                int cellY = row * metrics.cellHeight();
                String glyph = new String(Character.toChars(FIRST_CODE_POINT + i));
                g.drawString(glyph, cellX, cellY + metrics.ascent());
            }
        } finally {
            g.dispose();
        }
        byte[] alpha = new byte[sheetWidth * sheetHeight * BYTES_PER_PIXEL_A8];
        int[] argb = image.getRGB(0, 0, sheetWidth, sheetHeight, null, 0, sheetWidth);
        for (int i = 0; i < argb.length; i++) {
            alpha[i] = (byte) (argb[i] >>> 24);
        }
        return alpha;
    }

    private static void writeSharedMemoryHeader(ByteBuffer buffer, int decompressedSize) {
        buffer.putInt(0, HEADER_STATUS_LOADED);
        buffer.putInt(4, HEADER_REGION_STANDARD);
        buffer.putInt(8, decompressedSize);
        // 0x0C..0x80: reservado/preenchimento, já zerado por ByteBuffer.allocate.
    }

    private static void writeCfntHeader(ByteBuffer buffer, int offset, int fileSize) {
        writeMagic(buffer, offset, "CFNU");
        buffer.putShort(offset + 0x04, (short) 0xFEFF); // endianness: little
        buffer.putShort(offset + 0x06, (short) CFNT_HEADER_SIZE);
        buffer.putInt(offset + 0x08, 0x0300_0000); // version (3dbrew: "observed to be 0x03000000")
        buffer.putInt(offset + 0x0C, fileSize);
        buffer.putInt(offset + 0x10, 4); // num_blocks: FINF+CMAP+CWDH+TGLP
    }

    private static void writeFinf(ByteBuffer buffer, int offset, Metrics metrics, int baseAddress,
                                   int tglpStart, int cwdhStart, int cmapStart) {
        int spaceAdvance = metrics.advance()[' ' - FIRST_CODE_POINT];
        writeMagic(buffer, offset, "FINF");
        buffer.putInt(offset + 0x04, FINF_SECTION_SIZE);
        buffer.put(offset + 0x08, (byte) 1); // font_type: sem consumidor nesta HLE, valor plausível fixo
        buffer.put(offset + 0x09, (byte) metrics.cellHeight()); // line_feed
        buffer.putShort(offset + 0x0A, (short) 0); // alter_char_index: glifo 0 (espaço) para código fora do CMAP
        buffer.put(offset + 0x0C, (byte) 0); // default_width.left
        buffer.put(offset + 0x0D, (byte) metrics.cellWidth()); // default_width.glyphWidth
        buffer.put(offset + 0x0E, (byte) spaceAdvance); // default_width.charWidth
        buffer.put(offset + 0x0F, (byte) 1); // encoding: UTF-16 (mesmo valor usado pela fonte real do sistema)
        buffer.putInt(offset + 0x10, baseAddress + tglpStart + SECTION_HEADER_SIZE);
        buffer.putInt(offset + 0x14, baseAddress + cwdhStart + SECTION_HEADER_SIZE);
        buffer.putInt(offset + 0x18, baseAddress + cmapStart + SECTION_HEADER_SIZE);
        buffer.put(offset + 0x1C, (byte) metrics.cellHeight());
        buffer.put(offset + 0x1D, (byte) metrics.cellWidth());
        buffer.put(offset + 0x1E, (byte) metrics.ascent());
        buffer.put(offset + 0x1F, (byte) 0); // reserved
    }

    private static void writeCmap(ByteBuffer buffer, int offset) {
        writeMagic(buffer, offset, "CMAP");
        buffer.putInt(offset + 0x04, CMAP_SECTION_SIZE);
        buffer.putShort(offset + 0x08, (short) FIRST_CODE_POINT);
        buffer.putShort(offset + 0x0A, (short) LAST_CODE_POINT);
        buffer.putShort(offset + 0x0C, (short) CMAP_METHOD_DIRECT);
        buffer.putShort(offset + 0x0E, (short) 0); // reserved
        buffer.putInt(offset + 0x10, 0); // next_cmap_offset: só um bloco, cobre todo o ASCII imprimível
        buffer.putShort(offset + 0x14, (short) 0); // método direto: indexOffset — glifo i == código i
        // 0x16..0x18: preenchimento a múltiplo de 4, já zerado.
    }

    private static void writeCwdh(ByteBuffer buffer, int offset, int sectionSize, Metrics metrics) {
        writeMagic(buffer, offset, "CWDH");
        buffer.putInt(offset + 0x04, sectionSize);
        buffer.putShort(offset + 0x08, (short) 0);
        buffer.putShort(offset + 0x0A, (short) (GLYPH_COUNT - 1));
        buffer.putInt(offset + 0x0C, 0); // next_cwdh_offset: só um bloco
        int widthsStart = offset + CWDH_HEADER_SIZE;
        for (int i = 0; i < GLYPH_COUNT; i++) {
            buffer.put(widthsStart + 3 * i, (byte) 0); // left
            buffer.put(widthsStart + 3 * i + 1, (byte) metrics.cellWidth()); // glyphWidth
            buffer.put(widthsStart + 3 * i + 2, (byte) metrics.advance()[i]); // charWidth
        }
    }

    private static void writeTglp(ByteBuffer buffer, int offset, int sectionSize, Metrics metrics,
                                   int baseAddress, byte[] sheet) {
        writeMagic(buffer, offset, "TGLP");
        buffer.putInt(offset + 0x04, sectionSize);
        buffer.put(offset + 0x08, (byte) metrics.cellWidth());
        buffer.put(offset + 0x09, (byte) metrics.cellHeight());
        buffer.put(offset + 0x0A, (byte) metrics.ascent()); // baseline_position
        buffer.put(offset + 0x0B, (byte) metrics.maxAdvance());
        buffer.putInt(offset + 0x0C, sheet.length);
        buffer.putShort(offset + 0x10, (short) 1); // num_sheets
        buffer.putShort(offset + 0x12, (short) SHEET_FORMAT_A8);
        buffer.putShort(offset + 0x14, (short) SHEET_COLUMNS);
        buffer.putShort(offset + 0x16, (short) SHEET_ROWS);
        buffer.putShort(offset + 0x18, (short) (SHEET_COLUMNS * metrics.cellWidth()));
        buffer.putShort(offset + 0x1A, (short) (SHEET_ROWS * metrics.cellHeight()));
        int sheetDataOffset = offset + TGLP_HEADER_SIZE;
        buffer.putInt(offset + 0x1C, baseAddress + sheetDataOffset);
        System.arraycopy(sheet, 0, buffer.array(), sheetDataOffset, sheet.length);
    }

    private static void writeMagic(ByteBuffer buffer, int offset, String magic) {
        byte[] bytes = magic.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < 4; i++) {
            buffer.put(offset + i, bytes[i]);
        }
    }

    private static int roundUpToMultipleOf4(int value) {
        return roundUpToMultipleOf(value, 4);
    }

    private static int roundUpToMultipleOf(int value, int multiple) {
        return (value + multiple - 1) / multiple * multiple;
    }
}
