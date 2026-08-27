package dev.vitorsilverio.n3dsemu.service;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.n3dsemu.ipc.IpcRequest;
import dev.vitorsilverio.n3dsemu.ipc.IpcResponse;
import dev.vitorsilverio.n3dsemu.kernel.HandleTable;
import dev.vitorsilverio.n3dsemu.kernel.Result;
import dev.vitorsilverio.n3dsemu.kernel.SessionObject;

import java.io.PrintStream;
import java.util.Objects;

/// `fs:USER`, mínimo do marco M3 (RFC-N3DSEMU G3) + `OpenArchive`/`OpenFileDirectly` (G6.2 —
/// causa 1 achada pela G6.1: `composite_scene`/`cubemap`/`gpusprites` chamavam `svcBreak(PANIC)`
/// por essas duas faltarem). Qualquer outra abertura de arquivo/comando (fora da lista abaixo)
/// devolve erro genérico através do `default` de {@link AbstractService#respondUnknown} — nunca
/// lança.
///
/// **Achado real (G6.2), confirmado no `romfs.h` real instalado
/// (`C:\devkitPro\libctru\include\3ds\romfs.h`) e no fonte de `cubemap`/`gpusprites` (que chamam
/// `romfsInit()`): para um `.3dsx` (este projeto nunca carrega CIA/NCCH), `romfsInit()` é
/// `romfsMountSelf`, que monta o RomFS EMBUTIDO NO PRÓPRIO ARQUIVO `.3DSX`.** O mecanismo real
/// (confirmado contra `libctru/source/romfs_dev.c` via `WebFetch`): o guest chama
/// `FSUSER_OpenFileDirectly(ARCHIVE_SDMC, ...)` para abrir o PRÓPRIO `.3dsx` a partir do cartão
/// SD, lê o cabeçalho 3DSX estendido desse arquivo (`FSFILE_Read`) para achar o offset do RomFS,
/// e só faz leituras brutas nesse MESMO arquivo daí em diante — o parsing da estrutura RomFS
/// (hash tables/diretórios/arquivos) é feito inteiramente pelo GUEST, nunca pelo host. Por isso
/// `OpenFileDirectly` aqui **ignora de propósito** `archiveId`/`archivePath`/`filePath`
/// (nenhuma delas é lida) e sempre devolve uma sessão que serve os bytes brutos do `.3dsx` em
/// execução (ver {@link FsFileService}) — esta emulação não tem cartão SD, e todo uso real do
/// corpus atual é self-mount. `OpenArchive`/`CloseArchive` não modelam arquivos de verdade (o
/// binário de `composite_scene` referencia essas duas por registro padrão do device `sdmc:` do
/// runtime do libctru, `archiveMountDevice`, mesmo sem uso explícito de arquivo no `main()`) —
/// sucesso genérico com uma handle de archive placeholder basta, nenhum comando desta task
/// opera "sobre" uma archive-handle.
public final class FsUserService extends AbstractService {
    public static final String NAME = "fs:USER";

    private static final int CMD_INITIALIZE = 0x801;
    private static final int CMD_OPEN_FILE_DIRECTLY = 0x803;
    private static final int CMD_OPEN_ARCHIVE = 0x80C;
    private static final int CMD_CLOSE_ARCHIVE = 0x80E;
    private static final int CMD_INITIALIZE_WITH_SDK_VERSION = 0x861;
    private static final int CMD_SET_PRIORITY = 0x862;
    private static final int CMD_GET_PRIORITY = 0x863;

    private static final int PLACEHOLDER_PRIORITY = 0;
    /// Handle de archive devolvida por `OpenArchive` — nunca inspecionada depois (nenhum
    /// comando desta task aceita uma archive-handle como entrada), ver Javadoc da classe.
    private static final long PLACEHOLDER_ARCHIVE_HANDLE = 1L;

    private final HandleTable handles;
    private final ServiceRegistry registry;
    private final AddressSpace memory;
    private final byte[] rawFile;
    private int nextFileSessionId;

    public FsUserService(PrintStream log, HandleTable handles, ServiceRegistry registry, AddressSpace memory,
                          byte[] rawFile) {
        super(log);
        this.handles = Objects.requireNonNull(handles, "handles");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.rawFile = Objects.requireNonNull(rawFile, "rawFile");
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
            case CMD_OPEN_ARCHIVE -> handleOpenArchive(request, response);
            case CMD_CLOSE_ARCHIVE -> handleTrivialSuccess(request, response);
            case CMD_OPEN_FILE_DIRECTLY -> handleOpenFileDirectly(request, response);
            default -> respondUnknown(request, response);
        }
    }

    private void handleGetPriority(IpcRequest request, IpcResponse response) {
        response.header(request.commandId(), 2, 0);
        response.result(Result.SUCCESS);
        response.normalParam(1, PLACEHOLDER_PRIORITY);
    }

    private void handleOpenArchive(IpcRequest request, IpcResponse response) {
        response.header(request.commandId(), 3, 0);
        response.result(Result.SUCCESS);
        response.normalParam64(1, PLACEHOLDER_ARCHIVE_HANDLE);
    }

    /// Ver "Achado real" no Javadoc da classe — devolve sempre uma sessão nova sobre os bytes
    /// brutos do `.3dsx` em execução, nome sintético próprio (uma instância de
    /// {@link FsFileService} por abertura, ao contrário dos serviços nomeados fixos como
    /// `hid:USER`).
    private void handleOpenFileDirectly(IpcRequest request, IpcResponse response) {
        String sessionName = "fs:file#" + nextFileSessionId++;
        registry.register(new FsFileService(log, sessionName, rawFile, memory, registry));
        int fileHandle = handles.create(new SessionObject(sessionName));
        log.println("[fs:USER] OpenFileDirectly -> " + sessionName + " (bytes=" + rawFile.length + ")");
        response.header(request.commandId(), 1, 2);
        response.result(Result.SUCCESS);
        response.translateHandles(fileHandle);
    }

    private void handleTrivialSuccess(IpcRequest request, IpcResponse response) {
        response.header(request.commandId(), 1, 0);
        response.result(Result.SUCCESS);
    }
}
