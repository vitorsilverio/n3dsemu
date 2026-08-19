#version 450

// RFC-N3DSEMU G5/PR2: geometria já sombreada pelo VertexShaderInterpreter (Java) — a divisão de
// perspectiva já foi aplicada do lado de fora, `inPos` já é NDC. Este shader de vértice do
// hospedeiro é só a passagem adiante (o trabalho real da ISA do PICA200 já aconteceu na CPU).
layout(location = 0) in vec2 inPos;
layout(location = 1) in vec4 inColor;

layout(location = 0) out vec4 fragColor;

void main() {
    gl_Position = vec4(inPos, 0.0, 1.0);
    fragColor = inColor;
}
