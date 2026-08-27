package dev.vitorsilverio.n3dsemu.service;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.n3dsemu.ipc.IpcRequest;
import dev.vitorsilverio.n3dsemu.ipc.IpcResponse;
import dev.vitorsilverio.n3dsemu.kernel.Result;

import java.io.PrintStream;
import java.util.Objects;

/// Sessão de um arquivo aberto por `fs:USER#OpenFileDirectly` (RFC-N3DSEMU G6.2 —
/// `libctru/source/services/fs.c`, subserviço `FSFILE:`): não é resolvida por um nome fixo como
/// `hid:USER`/`gsp::Gpu` — cada abertura ganha uma instância própria, registrada em
/// {@link ServiceRegistry} sob um nome sintético só seu (ver Javadoc de {@link FsUserService}).
///
/// **Simplificação deliberada (ver Javadoc de {@link FsUserService}): serve sempre os bytes
/// brutos do `.3dsx` em execução**, nunca um arquivo de verdade do cartão SD — esta emulação não
/// tem cartão SD, e todo uso real do corpus atual (`romfsMountSelf`) é self-mount.
public final class FsFileService extends AbstractService {
    private static final int CMD_READ = 0x802;
    private static final int CMD_GET_SIZE = 0x804;
    private static final int CMD_CLOSE = 0x808;

    private final String name;
    private final byte[] content;
    private final AddressSpace memory;
    private final ServiceRegistry registry;

    public FsFileService(PrintStream log, String name, byte[] content, AddressSpace memory, ServiceRegistry registry) {
        super(log);
        this.name = Objects.requireNonNull(name, "name");
        this.content = Objects.requireNonNull(content, "content");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void handleRequest(IpcRequest request, IpcResponse response) {
        switch (request.commandId()) {
            case CMD_READ -> handleRead(request, response);
            case CMD_GET_SIZE -> handleGetSize(request, response);
            case CMD_CLOSE -> handleClose(request, response);
            default -> respondUnknown(request, response);
        }
    }

    /// `FSFILE_Read(offset, buffer, size)`: `normal64(0)`=offset, `normal[2]`=size, buffer de
    /// saída no primeiro (único) grupo traduzido — mesma posição física de um buffer estático
    /// (descritor, depois ponteiro), embora o descritor real seja do tipo "buffer mapeado"
    /// (3dbrew IPC), não "buffer estático"; `IpcRequest#staticBufferPointer` lê pela posição,
    /// não pelo tipo do descritor, então serve aqui sem mudança. Leitura além do fim do
    /// conteúdo trunca em vez de lançar (mesmo comportamento de uma leitura parcial real).
    private void handleRead(IpcRequest request, IpcResponse response) {
        long offset = request.normalParam64(0);
        int size = request.normalParam(2);
        int outAddress = request.staticBufferPointer(0);
        int bytesRead = 0;
        if (offset >= 0 && offset < content.length) {
            bytesRead = (int) Math.min(size, content.length - offset);
            for (int i = 0; i < bytesRead; i++) {
                memory.write8(outAddress + i, content[(int) offset + i]);
            }
        }
        log.println("[" + name + "] Read offset=" + offset + " size=" + size + " lido=" + bytesRead);
        response.header(request.commandId(), 2, 0);
        response.result(Result.SUCCESS);
        response.normalParam(1, bytesRead);
    }

    private void handleGetSize(IpcRequest request, IpcResponse response) {
        response.header(request.commandId(), 3, 0);
        response.result(Result.SUCCESS);
        response.normalParam64(1, content.length);
    }

    private void handleClose(IpcRequest request, IpcResponse response) {
        registry.unregister(name);
        response.header(request.commandId(), 1, 0);
        response.result(Result.SUCCESS);
    }
}
