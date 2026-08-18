package dev.vitorsilverio.n3dsemu.ipc;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// Leitura tipada do buffer de comando IPC de um `svcSendSyncRequest` (RFC-N3DSEMU G3 —
/// 3dbrew: IPC). O buffer vive em TLS+`0x80` da thread corrente
/// ({@link dev.vitorsilverio.n3dsemu.memory.MemoryMap#TLS_COMMAND_BUFFER_OFFSET}); esta classe
/// só lê — a resposta é escrita no MESMO buffer por {@link IpcResponse}, construída depois de
/// todo campo de entrada relevante já ter sido lido (a escrita da resposta pisa nas mesmas
/// palavras da requisição).
///
/// Layout (palavra = 4 bytes, offset relativo ao início do buffer): palavra 0 = cabeçalho
/// ({@link IpcCommandHeader}); em seguida {@link #normalParamCount()} palavras de parâmetro
/// normal; em seguida {@link #translateParamCount()} palavras de parâmetro traduzido (handles/
/// buffers estáticos, cada grupo com uma palavra de descritor antes do(s) valor(es) — ver
/// {@link #firstTranslatedHandle()}/{@link #staticBufferPointer}).
public final class IpcRequest {
    private static final int BYTES_PER_WORD = 4;
    private static final int HEADER_WORD_INDEX = 0;

    private final AddressSpace memory;
    private final int bufferAddress;
    private final int header;

    public IpcRequest(AddressSpace memory, int bufferAddress) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.bufferAddress = bufferAddress;
        this.header = memory.read32(bufferAddress + HEADER_WORD_INDEX * BYTES_PER_WORD);
    }

    public int commandId() {
        return IpcCommandHeader.commandId(header);
    }

    public int normalParamCount() {
        return IpcCommandHeader.normalParamCount(header);
    }

    public int translateParamCount() {
        return IpcCommandHeader.translateParamCount(header);
    }

    /// Lê o parâmetro normal de índice `index` (0-based, primeira palavra depois do cabeçalho).
    public int normalParam(int index) {
        checkNormalIndex(index);
        return wordAt(1 + index);
    }

    /// Lê um par de parâmetros normais consecutivos (`index`/`index+1`) como um `s64` do
    /// Horizon (palavra baixa primeiro, convenção AAPCS já usada em `SvcTable`).
    public long normalParam64(int index) {
        long low = normalParam(index) & 0xFFFF_FFFFL;
        long high = normalParam(index + 1) & 0xFFFF_FFFFL;
        return (high << 32) | low;
    }

    /// Lê os `wordCount` parâmetros normais a partir de `startIndex` como uma string ASCII
    /// (little-endian, um byte por posição, parando no primeiro `NUL`) — o padrão usado por
    /// `srv:GetServiceHandle`/`srvRegisterService` para embutir o nome do serviço direto nas
    /// palavras normais, sem ponteiro (nomes de serviço têm no máximo 8 bytes, 3dbrew: `srv`).
    public String normalParamAscii(int startIndex, int wordCount) {
        byte[] bytes = new byte[wordCount * BYTES_PER_WORD];
        int length = 0;
        outer:
        for (int w = 0; w < wordCount; w++) {
            int word = normalParam(startIndex + w);
            for (int b = 0; b < BYTES_PER_WORD; b++) {
                byte value = (byte) (word >>> (8 * b));
                if (value == 0) {
                    break outer;
                }
                bytes[length++] = value;
            }
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    /// Palavra bruta de um parâmetro traduzido (0-based dentro da região traduzida, ver Javadoc
    /// da classe) — inclui palavras de descritor. Uso direto raro; prefira
    /// {@link #firstTranslatedHandle()}/{@link #staticBufferPointer}.
    public int translateWord(int index) {
        if (index < 0 || index >= translateParamCount()) {
            throw new IndexOutOfBoundsException(
                    "índice de parâmetro traduzido fora do intervalo: " + index + " (total=" + translateParamCount() + ")");
        }
        return wordAt(1 + normalParamCount() + index);
    }

    /// Lê a handle carregada pelo PRIMEIRO grupo de tradução (descritor na palavra traduzida
    /// 0, valor na palavra traduzida 1) — o padrão de todo comando desta task que recebe uma
    /// handle de entrada (ex.: `gsp::Gpu` `RegisterInterruptRelayQueue`, o evento de interrupção
    /// que a APLICAÇÃO cria e passa para o serviço sinalizar).
    public int firstTranslatedHandle() {
        return translateWord(1);
    }

    /// Lê o ponteiro (endereço no guest) de um buffer estático de saída, cujo descritor está na
    /// palavra traduzida `descriptorIndex` (3dbrew IPC: descritor de buffer estático, tipo 1 —
    /// o ponteiro é sempre a palavra seguinte ao descritor, mesmo padrão do grupo de handles).
    public int staticBufferPointer(int descriptorIndex) {
        return translateWord(descriptorIndex + 1);
    }

    private int wordAt(int wordIndex) {
        return memory.read32(bufferAddress + wordIndex * BYTES_PER_WORD);
    }

    private void checkNormalIndex(int index) {
        if (index < 0 || index >= normalParamCount()) {
            throw new IndexOutOfBoundsException(
                    "índice de parâmetro normal fora do intervalo: " + index + " (total=" + normalParamCount() + ")");
        }
    }
}
