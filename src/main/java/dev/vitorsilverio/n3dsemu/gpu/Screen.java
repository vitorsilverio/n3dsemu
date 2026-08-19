package dev.vitorsilverio.n3dsemu.gpu;

/// As duas telas físicas do 3DS (RFC-N3DSEMU D6). O framebuffer de cada uma é armazenado
/// **em retrato**, varrido por coluna: {@link #columns()} colunas de {@link #ROWS} pixels cada
/// — a rotação para paisagem acontece só na apresentação (ver {@link PicaRenderer}), nunca
/// aqui.
public enum Screen {
    /// 400×240 (paisagem) — sem 3D estereoscópico neste marco, só o olho esquerdo (RFC D6).
    TOP(400),
    /// 320×240 (paisagem), centralizada sob a tela de cima no layout físico do console.
    BOTTOM(320);

    /// Altura fixa das duas telas em retrato = largura em paisagem (RFC D6).
    public static final int ROWS = 240;

    private final int columns;

    Screen(int columns) {
        this.columns = columns;
    }

    /// Número de colunas do framebuffer em retrato — igual à largura da tela em paisagem.
    public int columns() {
        return columns;
    }

    public int rows() {
        return ROWS;
    }
}
