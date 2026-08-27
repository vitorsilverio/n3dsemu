package dev.vitorsilverio.n3dsemu.service;

import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.n3dsemu.ipc.IpcCommandHeader;
import dev.vitorsilverio.n3dsemu.ipc.IpcRequest;
import dev.vitorsilverio.n3dsemu.ipc.IpcResponse;
import dev.vitorsilverio.n3dsemu.kernel.Result;
import dev.vitorsilverio.n3dsemu.memory.LoggingOpenBus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Testa `ptm:sysm` (tarefa G6.6): o único comando observado sendo chamado por um exemplo real
/// do corpus (`composite_scene`, via `__ctru_speedup_config` do crt0 do libctru) é
/// `PTMSYSM_ConfigureNew3DSCPU` — ver Javadoc de {@link PtmSysmService}.
class PtmSysmServiceTest {
    private static final int PAGE_SHIFT = 12;
    private static final int BUFFER_ADDRESS = 0x1FF9_0080;

    private record Harness(PagedAddressSpace memory, PtmSysmService service) {
    }

    private Harness newHarness() {
        PrintStream log = new PrintStream(new ByteArrayOutputStream());
        PagedAddressSpace memory = new PagedAddressSpace(PAGE_SHIFT, new LoggingOpenBus(log));
        memory.mapRam(BUFFER_ADDRESS & ~0xFFF, new byte[1 << PAGE_SHIFT]);
        return new Harness(memory, new PtmSysmService(log));
    }

    private void invoke(Harness h, int commandId, int normalParamCount, int... normalParams) {
        h.memory().write32(BUFFER_ADDRESS, IpcCommandHeader.pack(commandId, normalParamCount, 0));
        for (int i = 0; i < normalParams.length; i++) {
            h.memory().write32(BUFFER_ADDRESS + 4 * (1 + i), normalParams[i]);
        }
        IpcRequest request = new IpcRequest(h.memory(), BUFFER_ADDRESS);
        IpcResponse response = new IpcResponse(h.memory(), BUFFER_ADDRESS);
        h.service().handleRequest(request, response);
    }

    private int result(Harness h) {
        return h.memory().read32(BUFFER_ADDRESS + 4);
    }

    @Test
    void configureNew3dsCpuDevolveSucessoSemEfeito() {
        Harness h = newHarness();
        int cmdConfigureNew3dsCpu = 0x818;

        invoke(h, cmdConfigureNew3dsCpu, 1, 0x3);

        assertEquals(Result.SUCCESS.code(), result(h));
    }

    @Test
    void comandoDesconhecidoNuncaLancaEDevolveErroGenerico() {
        Harness h = newHarness();

        invoke(h, 0x9999, 0);

        assertEquals(Result.SERVICE_COMMAND_NOT_IMPLEMENTED.code(), result(h));
    }
}
