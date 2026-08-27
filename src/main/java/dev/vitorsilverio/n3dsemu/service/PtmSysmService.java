package dev.vitorsilverio.n3dsemu.service;

import dev.vitorsilverio.n3dsemu.ipc.IpcRequest;
import dev.vitorsilverio.n3dsemu.ipc.IpcResponse;
import dev.vitorsilverio.n3dsemu.kernel.Result;

import java.io.PrintStream;

/// `ptm:sysm`, mínimo achado por uso real (tarefa G6.6): o único comando que o corpus de
/// exemplos chama é `PTMSYSM_ConfigureNew3DSCPU`, disparado uma única vez pelo crt0 do libctru
/// (`__ctru_speedup_config`, `os.c`) logo no início do boot, com o resultado ignorado pelo
/// chamador. Esta HLE só modela `ARM11_MPCORE`/Old3DS (RFC-N3DSEMU) — não há clock/L2 cache de
/// New3DS para configurar, então o comando é aceito sem efeito.
public final class PtmSysmService extends AbstractService {
    public static final String NAME = "ptm:sysm";

    private static final int CMD_CONFIGURE_NEW3DS_CPU = 0x818;

    public PtmSysmService(PrintStream log) {
        super(log);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void handleRequest(IpcRequest request, IpcResponse response) {
        switch (request.commandId()) {
            case CMD_CONFIGURE_NEW3DS_CPU -> handleConfigureNew3dsCpu(request, response);
            default -> respondUnknown(request, response);
        }
    }

    private void handleConfigureNew3dsCpu(IpcRequest request, IpcResponse response) {
        response.header(request.commandId(), 1, 0);
        response.result(Result.SUCCESS);
    }
}
