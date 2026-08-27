package dev.vitorsilverio.n3dsemu.loader;

/// Resultado do carregamento e relocação de um `.3DSX`: os três segmentos, já com os
/// ponteiros internos corrigidos para {@code loadBase}, prontos para serem copiados em
/// sequência (`code` + `rodata` + `dataWithBss`) na memória do guest a partir de
/// {@code loadBase}.
public record Image3dsx(
        /// Endereço de carga ({@link Loader3dsx#LOAD_BASE}, sempre `0x00100000` no 3DS real).
        int loadBase,
        /// Ponto de entrada — no formato 3DSX é sempre igual a {@code loadBase}.
        int entryPoint,
        /// Conteúdo do segmento de código (`.text`), já relocado.
        byte[] code,
        /// Conteúdo do segmento `.rodata`, já relocado.
        byte[] rodata,
        /// Conteúdo do segmento `.data` seguido do `.bss` zerado (tamanho igual ao
        /// `dataSegSize` do cabeçalho, que já inclui o BSS), já relocado.
        byte[] dataWithBss,
        /// Bytes brutos e completos do arquivo `.3DSX` original, sem relocação (RFC-N3DSEMU
        /// G6.2 — `fs:USER#OpenFileDirectly` serve este array diretamente: o self-mount de
        /// RomFS de um `.3dsx` (`romfsMountSelf`) abre o PRÓPRIO arquivo via `ARCHIVE_SDMC` e
        /// lê nele em offsets absolutos; ver Javadoc de `FsUserService`).
        byte[] rawFile) {

    /// Tamanho total da imagem carregada (`code` + `rodata` + `dataWithBss`), útil para
    /// dimensionar a região de memória do guest.
    public int totalSize() {
        return code.length + rodata.length + dataWithBss.length;
    }
}
