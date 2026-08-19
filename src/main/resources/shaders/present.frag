#version 450

// RFC-N3DSEMU G4: a rotação retrato→paisagem (a "armadilha número um do 3DS", ver task) é UMA
// linha de GLSL aqui, nunca um laço em Java (RFC/task) — a textura de entrada tem largura=240
// (eixo das LINHAS do framebuffer em retrato, origem embaixo — 3dbrew: "origin at the
// bottom-left of the screen in portrait orientation") e altura=colunas (eixo das COLUNAS,
// origem à esquerda, ordem de varredura do framebuffer). `unitCoord` é a posição (0,0)=canto
// superior-esquerdo..(1,1)=canto inferior-direito dentro do quad de destino em PAISAGEM.
layout(location = 0) in vec2 unitCoord;
layout(location = 0) out vec4 outColor;

layout(binding = 0) uniform sampler2D screenTexture;

void main() {
    vec2 texCoord = vec2(1.0 - unitCoord.y, unitCoord.x);
    outColor = texture(screenTexture, texCoord);
}
