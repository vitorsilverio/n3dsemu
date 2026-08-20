package dev.vitorsilverio.n3dsemu.gpu;

/// Decodifica uma **lista de comandos** PICA200 (RFC-N3DSEMU G5/D5) e aplica cada escrita em
/// {@link PicaRegisters}. Formato (<a
/// href="https://www.3dbrew.org/wiki/GPU/Internal_Registers">3dbrew: GPU/Internal Registers</a>,
/// transcrito, não inventado):
///
/// - Cada comando tem no mínimo 2 palavras de 32 bits: a **primeira** é o parâmetro, a
///   **segunda** é o cabeçalho (nessa ordem — 3dbrew: "the first word is the command parameter
///   and the second word constitutes the command header").
/// - Cabeçalho: bits 0-15 = índice do registrador destino, bits 16-19 = máscara de escrita por
///   byte, bits 20-27 = quantidade de palavras extras (pode ser zero), bit 31 = modo consecutivo.
/// - Sem modo consecutivo, as palavras extras são escritas repetidamente no MESMO registrador
///   (rajada — ex.: upload de binário de shader). Com modo consecutivo, o índice do registrador é
///   incrementado a cada palavra extra.
/// - Uma lista de comandos inteira é sempre alinhada a 16 bytes (3dbrew: "the lower 3 bits of the
///   size are cleared") — cabe ao chamador garantir isso; este parser só para quando não sobram
///   palavras suficientes para outro comando.
public final class CommandListParser {
    private static final int REGISTER_ID_MASK = 0xFFFF;
    private static final int BYTE_MASK_SHIFT = 16;
    private static final int BYTE_MASK_BITS = 0xF;
    private static final int EXTRA_COUNT_SHIFT = 20;
    private static final int EXTRA_COUNT_BITS = 0xFF;
    private static final int CONSECUTIVE_BIT = 1 << 31;

    private CommandListParser() {
    }

    /// Processa `words` do início ao fim, aplicando cada comando em `registers`. Palavras
    /// sobrando ao final (menos de 2, ou menos que a contagem de palavras extras do último
    /// cabeçalho) são ignoradas — mesmo comportamento de uma lista real preenchida com padding.
    public static void parse(int[] words, PicaRegisters registers) {
        parse(words, registers, RegisterWriteListener.NONE);
    }

    /// Como {@link #parse(int[], PicaRegisters)}, mas notifica `listener` a CADA escrita
    /// individual, na ordem em que acontecem (RFC-N3DSEMU G5/PR3) — necessário para os
    /// registradores-FIFO do upload de shader, onde o valor final guardado em `registers` não
    /// basta (ver Javadoc de {@link RegisterWriteListener}).
    public static void parse(int[] words, PicaRegisters registers, RegisterWriteListener listener) {
        int i = 0;
        while (i + 1 < words.length) {
            int parameter = words[i];
            int header = words[i + 1];
            i += 2;

            int registerId = header & REGISTER_ID_MASK;
            int byteMask = (header >>> BYTE_MASK_SHIFT) & BYTE_MASK_BITS;
            int extraCount = (header >>> EXTRA_COUNT_SHIFT) & EXTRA_COUNT_BITS;
            boolean consecutive = (header & CONSECUTIVE_BIT) != 0;

            registers.write(registerId, parameter, byteMask);
            listener.onWrite(registerId, parameter, byteMask);
            for (int extra = 0; extra < extraCount && i < words.length; extra++, i++) {
                int targetRegisterId = consecutive ? registerId + 1 + extra : registerId;
                registers.write(targetRegisterId, words[i], byteMask);
                listener.onWrite(targetRegisterId, words[i], byteMask);
            }
        }
    }
}
