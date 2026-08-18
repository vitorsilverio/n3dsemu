package dev.vitorsilverio.n3dsemu.service;

import dev.vitorsilverio.n3dsemu.ipc.IpcRequest;
import dev.vitorsilverio.n3dsemu.ipc.IpcResponse;
import dev.vitorsilverio.n3dsemu.kernel.Result;

import java.io.PrintStream;
import java.util.Objects;

/// Base comum dos serviços desta task: log padronizado de "comando desconhecido" (RFC-N3DSEMU
/// G3 — ver Javadoc de {@link Service}, o contrato de nunca lançar) e a resposta genérica que o
/// acompanha, para não repetir o mesmo bloco em `srv:`/`APT:U`/`hid:USER`/`fs:USER`/`gsp::Gpu`/
/// `cfg:u`/`ptm:u`.
public abstract class AbstractService implements Service {
    private static final int UNKNOWN_RESPONSE_NORMAL_PARAM_COUNT = 1;
    private static final int UNKNOWN_RESPONSE_TRANSLATE_PARAM_COUNT = 0;

    protected final PrintStream log;

    protected AbstractService(PrintStream log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    /// Loga `serviço + comando + parâmetros` (a política de mapeamento da G3, oposta à da G2:
    /// nunca lançar) e escreve uma resposta de erro genérica de tamanho mínimo (só o `Result`)
    /// — suficiente para o guest não travar lendo lixo, mesmo sem saber o formato real da
    /// resposta esperada por este comando específico.
    protected final void respondUnknown(IpcRequest request, IpcResponse response) {
        log.printf("[%s] comando desconhecido 0x%04X normais=%d traduzidos=%d%n",
                name(), request.commandId(), request.normalParamCount(), request.translateParamCount());
        response.header(request.commandId(), UNKNOWN_RESPONSE_NORMAL_PARAM_COUNT, UNKNOWN_RESPONSE_TRANSLATE_PARAM_COUNT);
        response.result(Result.SERVICE_COMMAND_NOT_IMPLEMENTED);
    }
}
