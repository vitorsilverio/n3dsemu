package dev.vitorsilverio.n3dsemu.gpu;

/// Uma textura do guest já lida da memória e **desembaralhada** (a ordem de blocos 8×8 em curva de
/// Morton do PICA200, ver {@link Texture}) para `RGBA8` linear — RFC-N3DSEMU G5/PR4. É o que
/// {@link PicaRenderer#setTexture} entrega ao renderer: decodificar é trabalho do lado da GPU
/// emulada, não do backend gráfico.
public record PicaTexture(int width, int height, byte[] rgba8) {
}
