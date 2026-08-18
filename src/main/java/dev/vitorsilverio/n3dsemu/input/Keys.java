package dev.vitorsilverio.n3dsemu.input;

import java.util.Map;

/// Máscaras de botão `KEY_*` (RFC-N3DSEMU G3 — `libctru/include/3ds/services/hid.h`,
/// transcrito literalmente: cada `KEY_*` é `BIT(n)` do enum real do header). Usado tanto pelo
/// `hid:USER` (grava o estado bruto na memória compartilhada) quanto pelo leitor de
/// `--script` ({@link InputScript}), que aceita o NOME do botão em vez de pedir o hex de cada
/// ação do arquivo.
public final class Keys {
    private Keys() {
    }

    public static final int KEY_A = 1;
    public static final int KEY_B = 1 << 1;
    public static final int KEY_SELECT = 1 << 2;
    public static final int KEY_START = 1 << 3;
    public static final int KEY_DRIGHT = 1 << 4;
    public static final int KEY_DLEFT = 1 << 5;
    public static final int KEY_DUP = 1 << 6;
    public static final int KEY_DDOWN = 1 << 7;
    public static final int KEY_R = 1 << 8;
    public static final int KEY_L = 1 << 9;
    public static final int KEY_X = 1 << 10;
    public static final int KEY_Y = 1 << 11;
    public static final int KEY_CPAD_RIGHT = 1 << 28;
    public static final int KEY_CPAD_LEFT = 1 << 29;
    public static final int KEY_CPAD_UP = 1 << 30;
    public static final int KEY_CPAD_DOWN = 1 << 31;

    private static final Map<String, Integer> BY_NAME = Map.ofEntries(
            Map.entry("A", KEY_A), Map.entry("B", KEY_B),
            Map.entry("SELECT", KEY_SELECT), Map.entry("START", KEY_START),
            Map.entry("DRIGHT", KEY_DRIGHT), Map.entry("DLEFT", KEY_DLEFT),
            Map.entry("DUP", KEY_DUP), Map.entry("DDOWN", KEY_DDOWN),
            Map.entry("R", KEY_R), Map.entry("L", KEY_L),
            Map.entry("X", KEY_X), Map.entry("Y", KEY_Y),
            Map.entry("CPAD_RIGHT", KEY_CPAD_RIGHT), Map.entry("CPAD_LEFT", KEY_CPAD_LEFT),
            Map.entry("CPAD_UP", KEY_CPAD_UP), Map.entry("CPAD_DOWN", KEY_CPAD_DOWN));

    /// Resolve um nome de botão (`"START"`, sem o prefixo `KEY_`) para a máscara real, ou um
    /// literal hex (`"0x8"`)/decimal se o nome não for reconhecido — flexibilidade útil para um
    /// arquivo de `--script` escrito à mão.
    public static int byName(String name) {
        Integer known = BY_NAME.get(name.toUpperCase(java.util.Locale.ROOT));
        if (known != null) {
            return known;
        }
        return Integer.decode(name);
    }
}
