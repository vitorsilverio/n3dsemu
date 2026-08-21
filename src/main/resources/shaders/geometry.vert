#version 450

// RFC-N3DSEMU G5/PR4: geometria já sombreada pelo VertexShaderInterpreter (Java) — a divisão de
// perspectiva já foi aplicada do lado de fora, `inPos` já é NDC. Este shader de vértice do
// hospedeiro é só a passagem adiante (o trabalho real da ISA do PICA200 já aconteceu na CPU); o
// fragment shader é GERADO a partir da configuração dos 6 estágios TEV (ver TevGlslGenerator).
layout(location = 0) in vec2 inPos;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec2 inTexCoord0;
layout(location = 3) in vec2 inTexCoord1;
layout(location = 4) in vec2 inTexCoord2;

layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec2 fragTexCoord0;
layout(location = 2) out vec2 fragTexCoord1;
layout(location = 3) out vec2 fragTexCoord2;

void main() {
    gl_Position = vec4(inPos, 0.0, 1.0);
    fragColor = inColor;
    fragTexCoord0 = inTexCoord0;
    fragTexCoord1 = inTexCoord1;
    fragTexCoord2 = inTexCoord2;
}
