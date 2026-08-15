package dev.vitorsilverio.n3dsemu.loader;

/// `.3DSX` inválido ou malformado (magic ausente, tamanhos inconsistentes, relocação fora
/// dos limites do segmento, arquivo truncado).
public final class Bad3dsxException extends RuntimeException {
    public Bad3dsxException(String message) {
        super(message);
    }
}
