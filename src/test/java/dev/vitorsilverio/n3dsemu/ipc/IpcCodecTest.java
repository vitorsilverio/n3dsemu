package dev.vitorsilverio.n3dsemu.ipc;

import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.n3dsemu.kernel.Result;
import dev.vitorsilverio.n3dsemu.memory.LoggingOpenBus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Testa o codec IPC (RFC-N3DSEMU G3 — 3dbrew: IPC) contra vetores montados à mão, sem
/// depender de nenhum `.3dsx` real — cabeçalho, parâmetros normais e parâmetros traduzidos
/// (handles), ida (request) e volta (response) sobre o MESMO buffer.
class IpcCodecTest {
    private static final int PAGE_SHIFT = 12;
    private static final int BUFFER_ADDRESS = 0x1FF9_0080; // TLS+0x80 típico

    private PagedAddressSpace newMemory() {
        PrintStream log = new PrintStream(new ByteArrayOutputStream());
        PagedAddressSpace memory = new PagedAddressSpace(PAGE_SHIFT, new LoggingOpenBus(log));
        memory.mapRam(BUFFER_ADDRESS & ~0xFFF, new byte[1 << PAGE_SHIFT]);
        return memory;
    }

    @Test
    void packEDecodeDoCabecalhoBatemParaQualquerCombinacao() {
        int header = IpcCommandHeader.pack(0x1234, 5, 3);

        assertEquals(0x1234, IpcCommandHeader.commandId(header));
        assertEquals(5, IpcCommandHeader.normalParamCount(header));
        assertEquals(3, IpcCommandHeader.translateParamCount(header));
    }

    @Test
    void moveHandleDescriptorParaUmaHandleUsaBitDeMoveSemContagem() {
        int descriptor = IpcCommandHeader.moveHandleDescriptor(1);

        assertEquals(0x10, descriptor);
    }

    @Test
    void moveHandleDescriptorParaVariasHandlesCodificaContagemMenosUmNosBitsAltos() {
        int descriptor = IpcCommandHeader.moveHandleDescriptor(5);

        assertEquals(0x10 | (4 << 26), descriptor);
    }

    @Test
    void requestLeCabecalhoEParametrosNormaisEscritosNoBuffer() {
        PagedAddressSpace memory = newMemory();
        memory.write32(BUFFER_ADDRESS, IpcCommandHeader.pack(0x5, 2, 0));
        memory.write32(BUFFER_ADDRESS + 4, 0xCAFEBABE);
        memory.write32(BUFFER_ADDRESS + 8, 0x1234);

        IpcRequest request = new IpcRequest(memory, BUFFER_ADDRESS);

        assertEquals(0x5, request.commandId());
        assertEquals(2, request.normalParamCount());
        assertEquals(0, request.translateParamCount());
        assertEquals(0xCAFEBABE, request.normalParam(0));
        assertEquals(0x1234, request.normalParam(1));
    }

    @Test
    void requestNormalParam64CombinaDuasPalavrasComoS64() {
        PagedAddressSpace memory = newMemory();
        memory.write32(BUFFER_ADDRESS, IpcCommandHeader.pack(0x1, 2, 0));
        memory.write32(BUFFER_ADDRESS + 4, 0x00000001); // low
        memory.write32(BUFFER_ADDRESS + 8, 0x00000002); // high

        IpcRequest request = new IpcRequest(memory, BUFFER_ADDRESS);

        assertEquals((2L << 32) | 1L, request.normalParam64(0));
    }

    @Test
    void requestNormalParamAsciiParaComNulEIgnoraLixoDepois() {
        PagedAddressSpace memory = newMemory();
        memory.write32(BUFFER_ADDRESS, IpcCommandHeader.pack(0x5, 2, 0));
        // "srv:" + NUL + lixo, empacotado little-endian em 2 palavras (8 bytes).
        memory.write8(BUFFER_ADDRESS + 4, 's');
        memory.write8(BUFFER_ADDRESS + 5, 'r');
        memory.write8(BUFFER_ADDRESS + 6, 'v');
        memory.write8(BUFFER_ADDRESS + 7, ':');
        memory.write8(BUFFER_ADDRESS + 8, 0);
        memory.write8(BUFFER_ADDRESS + 9, 'X');

        IpcRequest request = new IpcRequest(memory, BUFFER_ADDRESS);

        assertEquals("srv:", request.normalParamAscii(0, 2));
    }

    @Test
    void requestTranslateWordEFirstTranslatedHandleLeemAposOsParametrosNormais() {
        PagedAddressSpace memory = newMemory();
        memory.write32(BUFFER_ADDRESS, IpcCommandHeader.pack(0x13, 1, 2));
        memory.write32(BUFFER_ADDRESS + 4, 0xAA); // normal[0]
        memory.write32(BUFFER_ADDRESS + 8, IpcCommandHeader.moveHandleDescriptor(1)); // translate[0]
        memory.write32(BUFFER_ADDRESS + 12, 0x77); // translate[1] = handle

        IpcRequest request = new IpcRequest(memory, BUFFER_ADDRESS);

        assertEquals(0x77, request.firstTranslatedHandle());
    }

    @Test
    void requestStaticBufferPointerLeAPalavraSeguinteAoDescritor() {
        PagedAddressSpace memory = newMemory();
        memory.write32(BUFFER_ADDRESS, IpcCommandHeader.pack(0x1, 2, 2));
        memory.write32(BUFFER_ADDRESS + 12, 0x2); // translate[0] = descritor de buffer estático
        memory.write32(BUFFER_ADDRESS + 16, 0xDEAD0000); // translate[1] = ponteiro

        IpcRequest request = new IpcRequest(memory, BUFFER_ADDRESS);

        assertEquals(0xDEAD0000, request.staticBufferPointer(0));
    }

    @Test
    void responseEscreveCabecalhoResultadoEParametrosNormaisNosDeslocamentosCertos() {
        PagedAddressSpace memory = newMemory();
        IpcResponse response = new IpcResponse(memory, BUFFER_ADDRESS);

        response.header(0x5, 3, 0);
        response.result(Result.SUCCESS);
        response.normalParam(1, 0x11);
        response.normalParam(2, 0x22);

        assertEquals(IpcCommandHeader.pack(0x5, 3, 0), memory.read32(BUFFER_ADDRESS));
        assertEquals(Result.SUCCESS.code(), memory.read32(BUFFER_ADDRESS + 4));
        assertEquals(0x11, memory.read32(BUFFER_ADDRESS + 8));
        assertEquals(0x22, memory.read32(BUFFER_ADDRESS + 12));
    }

    @Test
    void responseTranslateHandlesEscreveDescritorEValoresEAvancaCursorParaOProximoGrupo() {
        PagedAddressSpace memory = newMemory();
        IpcResponse response = new IpcResponse(memory, BUFFER_ADDRESS);

        response.header(0xA, 1, 8);
        response.result(Result.SUCCESS);
        response.translateHandles(0x100); // grupo 1: 1 handle (memória compartilhada)
        response.translateHandles(0x201, 0x202, 0x203, 0x204, 0x205); // grupo 2: 5 handles (eventos)

        int base = BUFFER_ADDRESS + 4 * (1 + 1); // após header + 1 normal param
        assertEquals(IpcCommandHeader.moveHandleDescriptor(1), memory.read32(base));
        assertEquals(0x100, memory.read32(base + 4));
        assertEquals(IpcCommandHeader.moveHandleDescriptor(5), memory.read32(base + 8));
        assertEquals(0x201, memory.read32(base + 12));
        assertEquals(0x205, memory.read32(base + 28));
    }

    @Test
    void requestEResponseSobreOMesmoBufferRepresentamORoundTripDeUmaChamada() {
        PagedAddressSpace memory = newMemory();
        memory.write32(BUFFER_ADDRESS, IpcCommandHeader.pack(0x1, 0, 0));
        IpcRequest request = new IpcRequest(memory, BUFFER_ADDRESS);
        int commandId = request.commandId();

        IpcResponse response = new IpcResponse(memory, BUFFER_ADDRESS);
        response.header(commandId, 1, 0);
        response.result(Result.SUCCESS);

        assertEquals(1, IpcCommandHeader.normalParamCount(memory.read32(BUFFER_ADDRESS)));
        assertEquals(Result.SUCCESS.code(), memory.read32(BUFFER_ADDRESS + 4));
    }
}
