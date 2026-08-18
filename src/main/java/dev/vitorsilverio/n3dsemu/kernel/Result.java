package dev.vitorsilverio.n3dsemu.kernel;

/// Código de resultado do kernel Horizon: um `u32` empacotado com quatro campos —
/// descrição, módulo, resumo e nível — nunca um literal hexadecimal solto no ponto de uso
/// (RFC-N3DSEMU/G2, regra "sem números mágicos").
///
/// **Layout confirmado por engenharia reversa, não de memória:** este projeto não tem acesso
/// a um result.h de referência, então o layout de bits foi validado batendo {@link #code()}
/// contra os dois códigos completos que o 3dbrew (`Error_codes`) publica por extenso —
/// `0xD8E007F7` (handle inválido) e `0xD8E007ED` (valor de enum inválido) — os dois decodificam
/// exatamente para `description=1015/1005`, `module=1` (Kernel), `summary=7` (Invalid argument)
/// e `level=27` (Permanent) sob a fórmula abaixo, o que fixa as quatro larguras de campo e os
/// deslocamentos.
///
/// @param description código específico do erro dentro do módulo (10 bits)
/// @param module       subsistema que gerou o erro (8 bits)
/// @param summary       categoria geral do erro (6 bits, com um vão reservado de 3 bits antes)
/// @param level         severidade (5 bits)
public record Result(int description, int module, int summary, int level) {
    private static final int DESCRIPTION_SHIFT = 0;
    private static final int MODULE_SHIFT = 10;
    private static final int SUMMARY_SHIFT = 21;
    private static final int LEVEL_SHIFT = 27;

    // ── Description (3dbrew: Error_codes, tabela "Description") ────────────────────────────
    public static final int DESCRIPTION_SUCCESS = 0;
    public static final int DESCRIPTION_MISALIGNED_ADDRESS = 1009;
    public static final int DESCRIPTION_MISALIGNED_SIZE = 1010;
    public static final int DESCRIPTION_OUT_OF_MEMORY = 1011;
    public static final int DESCRIPTION_INVALID_ENUM_VALUE = 1005;
    public static final int DESCRIPTION_INVALID_HANDLE = 1015;
    public static final int DESCRIPTION_NOT_FOUND = 1018;
    public static final int DESCRIPTION_OUT_OF_RANGE = 1021;

    // ── Module (3dbrew: Error_codes, tabela "Module") ───────────────────────────────────────
    public static final int MODULE_KERNEL = 1;

    // ── Summary (3dbrew: Error_codes, tabela "Summary") ─────────────────────────────────────
    public static final int SUMMARY_OUT_OF_RESOURCE = 3;
    public static final int SUMMARY_NOT_FOUND = 4;
    public static final int SUMMARY_INVALID_ARGUMENT = 7;

    // ── Level (3dbrew: Error_codes, tabela "Level") ─────────────────────────────────────────
    public static final int LEVEL_PERMANENT = 27;
    public static final int LEVEL_STATUS = 1;

    // ── constantes do código completo de timeout (G2 PR2, ver Javadoc de TIMEOUT) ──────────
    private static final int DESCRIPTION_TIMEOUT = 1022;
    private static final int MODULE_OS = 6;
    private static final int SUMMARY_STATUS_CHANGED = 10;

    /// Placeholder de descrição para "quem chamou não é o dono" (`svcReleaseMutex` sobre um
    /// mutex de outra thread) — reutiliza `SUMMARY_INVALID_ARGUMENT`/`LEVEL_PERMANENT`, mesmo
    /// padrão dos outros erros de uso indevido desta classe (`INVALID_HANDLE`/
    /// `INVALID_ENUM_VALUE`). **Valor de `description` não confirmado contra o 3dbrew nesta
    /// sessão** (sem acesso à wiki) — ao contrário de `TIMEOUT` abaixo, que foi decomposto de um
    /// código completo real conhecido.
    private static final int DESCRIPTION_NOT_LOCK_OWNER_PLACEHOLDER = 1016;

    /// `0` — nenhum campo setado. É o único valor de sucesso possível: qualquer campo
    /// diferente de zero torna o código um erro (ver {@link #isSuccess()}).
    public static final Result SUCCESS = new Result(DESCRIPTION_SUCCESS, 0, 0, 0);

    /// O handle informado não existe na tabela do processo (ver {@link HandleTable}).
    public static final Result INVALID_HANDLE =
            new Result(DESCRIPTION_INVALID_HANDLE, MODULE_KERNEL, SUMMARY_INVALID_ARGUMENT, LEVEL_PERMANENT);

    /// Um campo do tipo enum (`MemOp`, `MemPerm`, `LimitableResource`...) trouxe um valor fora
    /// do conjunto válido.
    public static final Result INVALID_ENUM_VALUE =
            new Result(DESCRIPTION_INVALID_ENUM_VALUE, MODULE_KERNEL, SUMMARY_INVALID_ARGUMENT, LEVEL_PERMANENT);

    /// Endereço ou tamanho fora dos limites esperados (ex.: `svcControlMemory` fora do heap).
    public static final Result OUT_OF_RANGE =
            new Result(DESCRIPTION_OUT_OF_RANGE, MODULE_KERNEL, SUMMARY_INVALID_ARGUMENT, LEVEL_PERMANENT);

    /// Endereço não alinhado à página (`MemoryMap.PAGE_SIZE`).
    public static final Result MISALIGNED_ADDRESS =
            new Result(DESCRIPTION_MISALIGNED_ADDRESS, MODULE_KERNEL, SUMMARY_INVALID_ARGUMENT, LEVEL_PERMANENT);

    /// Tamanho não múltiplo de página.
    public static final Result MISALIGNED_SIZE =
            new Result(DESCRIPTION_MISALIGNED_SIZE, MODULE_KERNEL, SUMMARY_INVALID_ARGUMENT, LEVEL_PERMANENT);

    /// Não havia espaço livre suficiente no heap para `MEMOP_ALLOC`.
    public static final Result OUT_OF_MEMORY =
            new Result(DESCRIPTION_OUT_OF_MEMORY, MODULE_KERNEL, SUMMARY_OUT_OF_RESOURCE, LEVEL_PERMANENT);

    /// `svcControlMemory` `MEMOP_FREE` sobre um endereço/tamanho que não corresponde
    /// exatamente a uma alocação viva (esta HLE não suporta liberar sub-faixas — ver
    /// {@link MemoryManager}).
    public static final Result MEMORY_REGION_NOT_FOUND =
            new Result(DESCRIPTION_NOT_FOUND, MODULE_KERNEL, SUMMARY_NOT_FOUND, LEVEL_PERMANENT);

    /// `svcWaitSynchronization*`/`svcArbitrateAddress` expirou sem sincronizar. **Único código
    /// desta classe reconstruído a partir de um código completo REAL conhecido** (`0x09401BFE`,
    /// amplamente documentado como o "Result timeout" do Horizon) em vez de montado a partir da
    /// tabela "Description" do 3dbrew — decomposição verificada bit a bit (description=1022,
    /// module=6, summary=10, level=1) e reconstrói exatamente `0x09401BFE` de volta via
    /// {@link #code()}.
    public static final Result TIMEOUT =
            new Result(DESCRIPTION_TIMEOUT, MODULE_OS, SUMMARY_STATUS_CHANGED, LEVEL_STATUS);

    /// `svcReleaseMutex` chamado por uma thread que não é a dona atual (ver Javadoc do campo
    /// `DESCRIPTION_NOT_LOCK_OWNER_PLACEHOLDER` acima).
    public static final Result NOT_LOCK_OWNER =
            new Result(DESCRIPTION_NOT_LOCK_OWNER_PLACEHOLDER, MODULE_KERNEL, SUMMARY_INVALID_ARGUMENT, LEVEL_PERMANENT);

    /// Comando IPC desconhecido dentro de um serviço conhecido (RFC-N3DSEMU G3 — política
    /// deliberadamente oposta à da G2: nunca lançar, só logar e devolver um erro genérico para
    /// o guest seguir em frente). Mesmos campos de {@link #MEMORY_REGION_NOT_FOUND} — o
    /// significado ("não encontrado") é o mesmo, só o nome muda para refletir o uso em IPC.
    public static final Result SERVICE_COMMAND_NOT_IMPLEMENTED =
            new Result(DESCRIPTION_NOT_FOUND, MODULE_KERNEL, SUMMARY_NOT_FOUND, LEVEL_PERMANENT);

    /// Empacota os quatro campos no `u32` que vai para `r0`.
    public int code() {
        return (description << DESCRIPTION_SHIFT)
                | (module << MODULE_SHIFT)
                | (summary << SUMMARY_SHIFT)
                | (level << LEVEL_SHIFT);
    }

    /// `true` somente para {@link #SUCCESS} (`code() == 0`).
    public boolean isSuccess() {
        return code() == 0;
    }
}
