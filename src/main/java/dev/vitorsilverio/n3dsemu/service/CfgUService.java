package dev.vitorsilverio.n3dsemu.service;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.n3dsemu.ipc.IpcRequest;
import dev.vitorsilverio.n3dsemu.ipc.IpcResponse;
import dev.vitorsilverio.n3dsemu.kernel.Result;

import java.io.PrintStream;
import java.util.Objects;

/// `cfg:u`, mínimo do marco M3 (RFC-N3DSEMU G3 — `libctru/include/3ds/services/cfgu.h`).
/// `GetConfigInfoBlk2` é o único comando que a task pede: devolve zeros para qualquer bloco de
/// configuração pedido (idioma/modelo do console incluídos) — nenhum exemplo do corpus desta
/// task toma decisão de fluxo a partir do conteúdo, só precisa que a chamada não falhe.
public final class CfgUService extends AbstractService {
    public static final String NAME = "cfg:u";

    private static final int CMD_GET_CONFIG_INFO_BLK2 = 0x1;

    private final AddressSpace memory;

    public CfgUService(PrintStream log, AddressSpace memory) {
        super(log);
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void handleRequest(IpcRequest request, IpcResponse response) {
        if (request.commandId() == CMD_GET_CONFIG_INFO_BLK2) {
            handleGetConfigInfoBlk2(request, response);
        } else {
            respondUnknown(request, response);
        }
    }

    /// `CFGU_GetConfigInfoBlk2(u32 size, u32 blkID, u8* outData)`: normal[0]=size, normal[1]=
    /// blkID; `outData` chega como ponteiro de buffer estático (descritor na primeira palavra
    /// traduzida). Zera `size` bytes ali — ver Javadoc da classe.
    private void handleGetConfigInfoBlk2(IpcRequest request, IpcResponse response) {
        int size = request.normalParam(0);
        int blockId = request.normalParam(1);
        log.println("[cfg:u] GetConfigInfoBlk2 blockId=0x" + Integer.toHexString(blockId) + " size=" + size);
        if (request.translateParamCount() > 0) {
            int outAddress = request.staticBufferPointer(0);
            for (int i = 0; i < size; i++) {
                memory.write8(outAddress + i, 0);
            }
        }
        response.header(request.commandId(), 1, 0);
        response.result(Result.SUCCESS);
    }
}
