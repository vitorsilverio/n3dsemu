package dev.vitorsilverio.n3dsemu.gpu;

import dev.vitorsilverio.n3dsemu.gpu.shader.Float24;

/// Valores **fixos** (default) dos atributos de vértice do PICA200 — RFC-N3DSEMU G5/PR4.
///
/// Nem todo atributo de entrada do vertex shader vem de um *array* na memória: o app pode declarar
/// um atributo como FIXO (`AttrInfo_AddFixed`/`C3D_FixedAttribSet` do citro3d) e a GPU entrega o
/// mesmo `vec4` para todos os vértices. Isso é programado por 4 registradores (3dbrew:
/// "GPU/Internal Registers"): {@value #REG_INDEX} recebe o índice do atributo (0-11) e
/// {@value #REG_DATA0}-{@value #REG_DATA2} recebem os 4 componentes empacotados em float24 — 3
/// palavras de 32 bits para 4 valores de 24 bits, na ordem `w`,`z`,`y`,`x` (mesmo empacotamento do
/// upload de uniforms float24, ver `ShaderUpload`).
///
/// **Achado real (G5.2)**: sem isto, o `simple_tri` desenhava um triângulo com cor
/// `(0,0,0,0)` — preto transparente sobre fundo preto, ou seja, invisível. O exemplo passa a
/// posição por *array* (`v0`) mas a COR por atributo fixo (`v1 = branco sólido`), então a falta
/// deste caminho é indistinguível de "nada foi desenhado".
///
/// O valor inicial de um atributo nunca programado é `(0,0,0,1)` — o default do hardware (Citra:
/// `input_default_attributes`), não zeros: um atributo de posição de 3 componentes conta com o
/// `w = 1` implícito.
public final class FixedAttributes implements RegisterWriteListener {
    /// `GPUREG_FIXEDATTRIB_INDEX`.
    public static final int REG_INDEX = 0x232;
    /// `GPUREG_FIXEDATTRIB_DATA0`-`DATA2`.
    public static final int REG_DATA0 = 0x233;
    public static final int REG_DATA2 = 0x235;

    /// Índice reservado para o modo de submissão IMEDIATA de vértices (3dbrew) — não é um
    /// atributo fixo; ignorado por este HLE (nenhum app do corpus usa modo imediato).
    private static final int IMMEDIATE_MODE_INDEX = 0xF;
    private static final int INDEX_MASK = 0xF;
    private static final int WORDS_PER_ATTRIBUTE = 3;

    private final float[][] values = new float[VertexAttributeLoader.NUM_ATTRIBUTES][];
    private final int[] pendingWords = new int[WORDS_PER_ATTRIBUTE];
    private int index = IMMEDIATE_MODE_INDEX;
    private int pendingCount;

    public FixedAttributes() {
        for (int attributeId = 0; attributeId < values.length; attributeId++) {
            values[attributeId] = defaultValue();
        }
    }

    private static float[] defaultValue() {
        return new float[]{0f, 0f, 0f, 1f};
    }

    @Override
    public void onWrite(int registerId, int value, int byteMask) {
        if (registerId == REG_INDEX) {
            index = value & INDEX_MASK;
            pendingCount = 0;
        } else if (registerId >= REG_DATA0 && registerId <= REG_DATA2) {
            pendingWords[pendingCount++] = value;
            if (pendingCount == WORDS_PER_ATTRIBUTE) {
                pendingCount = 0;
                if (index < values.length) {
                    values[index] = unpack(pendingWords);
                }
            }
        }
    }

    /// 4 valores float24 em 3 palavras de 32 bits, do componente `w` (bits altos da primeira
    /// palavra) ao `x` (bits baixos da terceira).
    private static float[] unpack(int[] words) {
        float w = Float24.decode(words[0] >>> 8);
        float z = Float24.decode(((words[0] & 0xFF) << 16) | ((words[1] >>> 16) & 0xFFFF));
        float y = Float24.decode(((words[1] & 0xFFFF) << 8) | ((words[2] >>> 24) & 0xFF));
        float x = Float24.decode(words[2] & 0xFFFFFF);
        return new float[]{x, y, z, w};
    }

    /// Valor fixo/default do atributo `attributeId` (cópia — o chamador monta o registro de
    /// entrada do shader com ele).
    public float[] value(int attributeId) {
        return values[attributeId].clone();
    }
}
