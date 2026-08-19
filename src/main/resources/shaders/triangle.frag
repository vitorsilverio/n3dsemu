#version 450

// RFC-N3DSEMU G5/PR2: sem TEV ainda (PR3) — a cor final é só a cor interpolada do vértice.
layout(location = 0) in vec4 fragColor;
layout(location = 0) out vec4 outColor;

void main() {
    outColor = fragColor;
}
