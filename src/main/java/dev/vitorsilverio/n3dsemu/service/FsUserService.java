package dev.vitorsilverio.n3dsemu.service;

import dev.vitorsilverio.n3dsemu.ipc.IpcRequest;
import dev.vitorsilverio.n3dsemu.ipc.IpcResponse;
import dev.vitorsilverio.n3dsemu.kernel.Result;

import java.io.PrintStream;

/// `fs:USER`, mínimo do marco M3 (RFC-N3DSEMU G3 — "não inclui": sem RomFS e sem `sdmc:` real,
/// só o suficiente para `fsInit()` do libctru não falhar). Qualquer abertura de arquivo (fora da
/// lista abaixo) devolve erro de "não encontrado" através do `default` de
/// {@link AbstractService#respondUnknown} — nunca lança.
public final class FsUserService extends AbstractService {
    public static final String NAME = "fs:USER";

    private static final int CMD_INITIALIZE = 0x801;
    private static final int CMD_INITIALIZE_WITH_SDK_VERSION = 0x861;
    private static final int CMD_SET_PRIORITY = 0x862;
    private static final int CMD_GET_PRIORITY = 0x863;

    private static final int PLACEHOLDER_PRIORITY = 0;

    public FsUserService(PrintStream log) {
        super(log);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void handleRequest(IpcRequest request, IpcResponse response) {
        switch (request.commandId()) {
            case CMD_INITIALIZE, CMD_INITIALIZE_WITH_SDK_VERSION, CMD_SET_PRIORITY -> handleTrivialSuccess(request, response);
            case CMD_GET_PRIORITY -> handleGetPriority(request, response);
            default -> respondUnknown(request, response);
        }
    }

    private void handleGetPriority(IpcRequest request, IpcResponse response) {
        response.header(request.commandId(), 2, 0);
        response.result(Result.SUCCESS);
        response.normalParam(1, PLACEHOLDER_PRIORITY);
    }

    private void handleTrivialSuccess(IpcRequest request, IpcResponse response) {
        response.header(request.commandId(), 1, 0);
        response.result(Result.SUCCESS);
    }
}
