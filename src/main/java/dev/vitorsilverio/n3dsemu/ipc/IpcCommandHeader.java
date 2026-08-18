package dev.vitorsilverio.n3dsemu.ipc;

/// Codificação do cabeçalho de comando IPC do Horizon (RFC-N3DSEMU G3 —
/// <a href="https://www.3dbrew.org/wiki/IPC">3dbrew: IPC</a>) e do descritor de "Handle
/// Translation" usado para mover/copiar handles pelo buffer de comando. Classe utilitária pura
/// (sem estado) — {@link IpcRequest}/{@link IpcResponse} são quem lê/escreve o buffer de
/// verdade, usando estas constantes/funções para não espalhar os deslocamentos de bits em
/// vários pontos do código (regra "sem números mágicos").
public final class IpcCommandHeader {
    private IpcCommandHeader() {
    }

    // ── palavra 0: cabeçalho de comando ─────────────────────────────────────────────────────
    private static final int COMMAND_ID_SHIFT = 16;
    private static final int NORMAL_PARAMS_SHIFT = 6;
    private static final int NORMAL_PARAMS_MASK = 0x3F;
    private static final int TRANSLATE_PARAMS_MASK = 0x3F;

    public static int commandId(int header) {
        return header >>> COMMAND_ID_SHIFT;
    }

    public static int normalParamCount(int header) {
        return (header >>> NORMAL_PARAMS_SHIFT) & NORMAL_PARAMS_MASK;
    }

    public static int translateParamCount(int header) {
        return header & TRANSLATE_PARAMS_MASK;
    }

    public static int pack(int commandId, int normalParamCount, int translateParamCount) {
        return (commandId << COMMAND_ID_SHIFT)
                | ((normalParamCount & NORMAL_PARAMS_MASK) << NORMAL_PARAMS_SHIFT)
                | (translateParamCount & TRANSLATE_PARAMS_MASK);
    }

    // ── descritor de tradução de handles ("Handle Translation", tipo 0) ────────────────────
    // 3dbrew IPC: bits 1-3 = tipo (0 = handles), bit 4 = "close handles" (usado por handles
    // MOVIDAS, ao contrário de copiadas), bits 26-31 = quantidade de handles menos um. Esta
    // HLE tem um único "processo" (RFC D1) — não há kernel real fazendo a distinção
    // mover/copiar entre tabelas de processos diferentes, então sempre usamos a forma "move"
    // (bit 4 setado): o cliente (libctru) nunca inspeciona esse bit, só lê os valores nos
    // deslocamentos que já espera.
    private static final int HANDLE_DESCRIPTOR_MOVE_BIT = 0x10;
    private static final int HANDLE_DESCRIPTOR_COUNT_SHIFT = 26;

    /// Descritor de tradução para `handleCount` handles consecutivas (a primeira delas na
    /// palavra seguinte a este descritor).
    public static int moveHandleDescriptor(int handleCount) {
        if (handleCount < 1) {
            throw new IllegalArgumentException("handleCount deve ser >= 1: " + handleCount);
        }
        return HANDLE_DESCRIPTOR_MOVE_BIT | ((handleCount - 1) << HANDLE_DESCRIPTOR_COUNT_SHIFT);
    }
}
