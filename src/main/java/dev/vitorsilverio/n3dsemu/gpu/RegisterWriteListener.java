package dev.vitorsilverio.n3dsemu.gpu;

/// Observa cada escrita de registrador que {@link CommandListParser} aplica (RFC-N3DSEMU G5/PR3)
/// — usado por {@link dev.vitorsilverio.n3dsemu.gpu.shader.ShaderUpload} para capturar os
/// registradores que se comportam como FIFO (código do shader/*operand descriptors*/uniforms de
/// float): esses registradores TÊM o mesmo `registerId` a cada escrita da rajada, então
/// {@link PicaRegisters#write} sozinho (que só guarda o último valor) perde a sequência —
/// precisa de alguém olhando escrita a escrita, não só o estado final.
@FunctionalInterface
public interface RegisterWriteListener {
    /// Nenhuma observação (RFC/task: comportamento de antes desta PR, quando só
    /// {@link PicaRegisters} importava).
    RegisterWriteListener NONE = (registerId, value, byteMask) -> { };

    void onWrite(int registerId, int value, int byteMask);
}
