package dev.vitorsilverio.n3dsemu.gpu;

/// Um vértice já processado pelo vertex shader interpretado e com a divisão de perspectiva
/// (`clip / clip.w`) já aplicada (RFC-N3DSEMU G5/PR2) — o que {@link PicaRenderer#drawTriangles}
/// recebe. `ndcX`/`ndcY` estão em NDC (-1..1); `color` é RGBA em `0..1` (saída do registrador de
/// cor do shader, ainda **sem TEV** — PR3 substitui isso por uma textura/combinador real).
public record ShadedVertex(float ndcX, float ndcY, float r, float g, float b, float a) {
}
