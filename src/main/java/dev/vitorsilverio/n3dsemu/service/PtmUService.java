package dev.vitorsilverio.n3dsemu.service;

import dev.vitorsilverio.n3dsemu.ipc.IpcRequest;
import dev.vitorsilverio.n3dsemu.ipc.IpcResponse;
import dev.vitorsilverio.n3dsemu.kernel.Result;

import java.io.PrintStream;

/// `ptm:u`, mínimo do marco M3 (RFC-N3DSEMU G3): nível de bateria e estado de carga FIXOS —
/// "triviais e exigidos por alguns exemplos" (spec da task), nenhum jogo/homebrew do corpus
/// toma decisão real a partir do valor.
public final class PtmUService extends AbstractService {
    public static final String NAME = "ptm:u";

    private static final int CMD_GET_BATTERY_LEVEL = 0x5;
    private static final int CMD_GET_BATTERY_CHARGE_STATE = 0x6;

    /// `PTM_BATTERY_LEVEL` (0-5, 5=cheia) — valor plausível fixo, ver Javadoc da classe.
    private static final int BATTERY_LEVEL_FULL = 5;
    private static final int CHARGING = 1;

    public PtmUService(PrintStream log) {
        super(log);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void handleRequest(IpcRequest request, IpcResponse response) {
        switch (request.commandId()) {
            case CMD_GET_BATTERY_LEVEL -> handleFixedValue(request, response, BATTERY_LEVEL_FULL);
            case CMD_GET_BATTERY_CHARGE_STATE -> handleFixedValue(request, response, CHARGING);
            default -> respondUnknown(request, response);
        }
    }

    private void handleFixedValue(IpcRequest request, IpcResponse response, int value) {
        response.header(request.commandId(), 2, 0);
        response.result(Result.SUCCESS);
        response.normalParam(1, value);
    }
}
