#version 450

// RFC-N3DSEMU G4: quad de tela cheia sem vertex buffer (posições geradas a partir do
// gl_VertexIndex, TRIANGLE_STRIP de 4 vértices) — o mesmo pipeline desenha as duas telas, uma
// por chamada de vkCmdDraw, cada uma com seu próprio push constant `rect` (retângulo em NDC de
// destino daquela tela dentro da janela).
layout(push_constant) uniform PushConstants {
    vec4 rect; // x0, y0, x1, y1 em NDC (-1..1)
} pc;

layout(location = 0) out vec2 unitCoord; // (0,0)..(1,1) dentro do quad, ANTES da rotação

void main() {
    vec2 corners[4] = vec2[](
        vec2(0.0, 0.0),
        vec2(1.0, 0.0),
        vec2(0.0, 1.0),
        vec2(1.0, 1.0)
    );
    vec2 unit = corners[gl_VertexIndex];
    unitCoord = unit;
    vec2 ndc = mix(pc.rect.xy, pc.rect.zw, unit);
    gl_Position = vec4(ndc, 0.0, 1.0);
}
