package dev.vitorsilverio.n3dsemu.gpu;

/// Um vértice já processado pelo vertex shader interpretado e com a divisão de perspectiva
/// (`clip / clip.w`) já aplicada (RFC-N3DSEMU G5/PR2) — o que {@link PicaRenderer#drawTriangles}
/// recebe. `ndcX`/`ndcY` estão em NDC (-1..1); `r`/`g`/`b`/`a` são a cor interpolada do vértice
/// (`PRIMARY_COLOR` do TEV); `u0`-`v2` são as três coordenadas de textura que o PICA200 interpola
/// (RFC-N3DSEMU G5/PR4), distribuídas por semântica pelo {@link OutputMap}.
public record ShadedVertex(float ndcX, float ndcY, float r, float g, float b, float a,
                            float u0, float v0, float u1, float v1, float u2, float v2) {

    /// Vértice sem coordenada de textura nenhuma — o caso do `simple_tri` e dos testes que só
    /// olham posição/cor.
    public ShadedVertex(float ndcX, float ndcY, float r, float g, float b, float a) {
        this(ndcX, ndcY, r, g, b, a, 0f, 0f, 0f, 0f, 0f, 0f);
    }
}
