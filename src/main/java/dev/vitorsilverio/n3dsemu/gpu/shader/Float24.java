package dev.vitorsilverio.n3dsemu.gpu.shader;

/// Decodifica o formato de ponto flutuante próprio do PICA200: 24 bits (1 sinal + 7 expoente,
/// *bias* 63 + 16 mantissa, mantissa implícita quando expoente != 0) — RFC-N3DSEMU G5/D5, RFC
/// menciona o formato "1-sign/7-exponent/16-mantissa" do 3dbrew. Confirmado bit-a-bit nesta
/// sessão contra a tabela de constantes de um `.shbin` REAL compilado pelo `picasso`
/// (`simple_tri`, `myconst(0.0, 1.0, -1.0, 0.1)`): os 24 bits ficam nos bits baixos de cada word
/// de 32 bits da tabela de constantes do DVLE (um componente por word nesse formato — diferente
/// do formato compactado usado no upload por FIFO da GPU real, fora do escopo desta PR).
public final class Float24 {
    private static final int MANTISSA_BITS = 16;
    private static final int EXPONENT_BITS = 7;
    private static final int EXPONENT_BIAS = 63;
    private static final int MANTISSA_MASK = (1 << MANTISSA_BITS) - 1;
    private static final int EXPONENT_MASK = (1 << EXPONENT_BITS) - 1;

    private Float24() {
    }

    /// `word24` usa só os 24 bits baixos (bit23=sinal, bits16-22=expoente, bits0-15=mantissa).
    public static float decode(int word24) {
        int mantissa = word24 & MANTISSA_MASK;
        int exponent = (word24 >>> MANTISSA_BITS) & EXPONENT_MASK;
        boolean negative = ((word24 >>> (MANTISSA_BITS + EXPONENT_BITS)) & 1) != 0;

        if (exponent == 0 && mantissa == 0) {
            return negative ? -0.0f : 0.0f;
        }
        // RFC/3dbrew: subnormais de entrada/saída são levados a +0 (sem zero negativo real no
        // PICA200) — não há suporte a denormais aqui, só o caso exponent==0 && mantissa==0 acima.
        float significand = 1.0f + (float) mantissa / (1 << MANTISSA_BITS);
        float value = significand * (float) Math.pow(2.0, exponent - EXPONENT_BIAS);
        return negative ? -value : value;
    }
}
