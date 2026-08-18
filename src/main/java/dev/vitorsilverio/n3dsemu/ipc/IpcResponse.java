package dev.vitorsilverio.n3dsemu.ipc;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.n3dsemu.kernel.Result;

import java.util.Objects;

/// Escrita tipada da resposta IPC, no MESMO buffer que {@link IpcRequest} leu (RFC-N3DSEMU G3
/// — ver Javadoc daquela classe). Convenção do Horizon: o parâmetro normal de índice 0 é sempre
/// o {@link Result} da chamada — por isso {@link #result} escreve ali, não num campo à parte.
///
/// Uso: {@link #header} primeiro (fixa quantos parâmetros normais/traduzidos esta resposta vai
/// ter — os métodos de escrita usam isso para calcular onde cada campo cai), depois
/// {@link #result}/{@link #normalParam}/{@link #translateHandles} na ordem em que os campos
/// aparecem no buffer. {@link #translateHandles} pode ser chamado várias vezes seguidas (ex.:
/// `hid:USER GetIPCHandles`, memória compartilhada + 5 eventos são dois grupos de handles
/// separados) — cada chamada avança um cursor interno para o próximo grupo.
public final class IpcResponse {
    private static final int BYTES_PER_WORD = 4;
    private static final int HEADER_WORD_INDEX = 0;
    private static final int RESULT_NORMAL_PARAM_INDEX = 0;

    private final AddressSpace memory;
    private final int bufferAddress;
    private int normalParamCount;
    private int translateWordCursor;

    public IpcResponse(AddressSpace memory, int bufferAddress) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.bufferAddress = bufferAddress;
    }

    /// Grava a palavra de cabeçalho e fixa `normalParamCount` para os métodos seguintes
    /// calcularem deslocamentos. `commandId` normalmente ecoa o da requisição (o cliente
    /// libctru não valida este campo na resposta, só lê os parâmetros — mas ecoar ajuda a
    /// depurar um trace).
    public void header(int commandId, int normalParamCount, int translateParamCount) {
        this.normalParamCount = normalParamCount;
        this.translateWordCursor = 0;
        writeWord(HEADER_WORD_INDEX, IpcCommandHeader.pack(commandId, normalParamCount, translateParamCount));
    }

    public void result(Result result) {
        normalParam(RESULT_NORMAL_PARAM_INDEX, result.code());
    }

    public void normalParam(int index, int value) {
        writeWord(1 + index, value);
    }

    public void normalParam64(int index, long value) {
        normalParam(index, (int) value);
        normalParam(index + 1, (int) (value >>> 32));
    }

    /// Escreve um grupo de handles traduzidas (descritor + valores, ver
    /// {@link IpcCommandHeader#moveHandleDescriptor}) na posição atual do cursor de tradução, e
    /// avança o cursor para o próximo grupo.
    public void translateHandles(int... handles) {
        if (handles.length == 0) {
            throw new IllegalArgumentException("translateHandles precisa de ao menos uma handle");
        }
        int descriptorWordIndex = 1 + normalParamCount + translateWordCursor;
        writeWord(descriptorWordIndex, IpcCommandHeader.moveHandleDescriptor(handles.length));
        for (int i = 0; i < handles.length; i++) {
            writeWord(descriptorWordIndex + 1 + i, handles[i]);
        }
        translateWordCursor += 1 + handles.length;
    }

    private void writeWord(int wordIndex, int value) {
        memory.write32(bufferAddress + wordIndex * BYTES_PER_WORD, value);
    }
}
