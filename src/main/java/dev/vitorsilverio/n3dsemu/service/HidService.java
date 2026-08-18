package dev.vitorsilverio.n3dsemu.service;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.n3dsemu.input.InputScript;
import dev.vitorsilverio.n3dsemu.input.InputState;
import dev.vitorsilverio.n3dsemu.ipc.IpcRequest;
import dev.vitorsilverio.n3dsemu.ipc.IpcResponse;
import dev.vitorsilverio.n3dsemu.kernel.EventObject;
import dev.vitorsilverio.n3dsemu.kernel.HandleTable;
import dev.vitorsilverio.n3dsemu.kernel.MemoryBlockObject;
import dev.vitorsilverio.n3dsemu.kernel.MemoryPermission;
import dev.vitorsilverio.n3dsemu.kernel.Result;
import dev.vitorsilverio.n3dsemu.kernel.ResetType;
import dev.vitorsilverio.n3dsemu.memory.MemoryMap;

import java.io.PrintStream;
import java.util.Objects;

/// `hid:USER` (RFC-N3DSEMU G3 —
/// <a href="https://www.3dbrew.org/wiki/HID_Shared_Memory">3dbrew: HID Shared Memory</a>).
/// Preenche a memória compartilhada de botões/círculo-analógico/toque a cada quadro simulado
/// (60&nbsp;Hz, {@link #advanceFrame} chamado pelo pulso do {@link GspGpuService} — mesma
/// cadência de VBlank, RFC/task: "preencher a cada quadro simulado"). Entrada vem de
/// {@link InputState}, alimentado pelo host (`N3dsMachine#pressButtons`/`--script`, sem GUI).
public final class HidService extends AbstractService {
    public static final String NAME = "hid:USER";

    private static final int CMD_GET_IPC_HANDLES = 0xA;

    // ── layout da memória compartilhada (3dbrew: HID_Shared_Memory) ────────────────────────
    private static final int SHARED_MEMORY_SIZE = MemoryMap.PAGE_SIZE;

    private static final int PAD_RING_INDEX_OFFSET = 0x10;
    private static final int PAD_STATE_OFFSET = 0x1C;
    private static final int PAD_CIRCLE_OFFSET = 0x20;
    private static final int PAD_RING_BUFFER_OFFSET = 0x28;
    private static final int PAD_RING_ENTRY_SIZE = 0x10;
    private static final int PAD_RING_ENTRY_COUNT = 8;

    private static final int TOUCH_RING_INDEX_OFFSET = 0xB8;
    private static final int TOUCH_CURRENT_OFFSET = 0xC0;
    private static final int TOUCH_RING_BUFFER_OFFSET = 0xC8;
    private static final int TOUCH_RING_ENTRY_SIZE = 0x8;
    private static final int TOUCH_RING_ENTRY_COUNT = 8;
    private static final int TOUCH_VALID_FLAG = 1;

    private final AddressSpace memory;
    private final HandleTable handles;
    private final InputState inputState;
    private final InputScript inputScript;

    private final int sharedMemoryHandle;
    private final EventObject pad0Event = new EventObject(ResetType.STICKY);
    private final EventObject pad1Event = new EventObject(ResetType.STICKY);
    private final EventObject accelerometerEvent = new EventObject(ResetType.STICKY);
    private final EventObject gyroscopeEvent = new EventObject(ResetType.STICKY);
    private final EventObject debugPadEvent = new EventObject(ResetType.STICKY);

    private int frameCounter;
    private int padRingIndex;
    private int touchRingIndex;

    public HidService(PrintStream log, AddressSpace memory, HandleTable handles, InputState inputState,
                       InputScript inputScript) {
        super(log);
        this.memory = Objects.requireNonNull(memory, "memory");
        this.handles = Objects.requireNonNull(handles, "handles");
        this.inputState = Objects.requireNonNull(inputState, "inputState");
        this.inputScript = inputScript;
        this.sharedMemoryHandle = handles.create(
                MemoryBlockObject.serverOwned(SHARED_MEMORY_SIZE, MemoryPermission.READ_WRITE, MemoryPermission.READ));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void handleRequest(IpcRequest request, IpcResponse response) {
        if (request.commandId() == CMD_GET_IPC_HANDLES) {
            handleGetIpcHandles(request, response);
        } else {
            respondUnknown(request, response);
        }
    }

    /// `HIDUSER_GetHandles`: cria e devolve as 5 handles de evento + a handle da memória
    /// compartilhada (já criada no construtor, ver Javadoc da classe).
    private void handleGetIpcHandles(IpcRequest request, IpcResponse response) {
        int pad0Handle = handles.create(pad0Event);
        int pad1Handle = handles.create(pad1Event);
        int accelHandle = handles.create(accelerometerEvent);
        int gyroHandle = handles.create(gyroscopeEvent);
        int debugPadHandle = handles.create(debugPadEvent);

        response.header(request.commandId(), 1, 8);
        response.result(Result.SUCCESS);
        response.translateHandles(sharedMemoryHandle);
        response.translateHandles(pad0Handle, pad1Handle, accelHandle, gyroHandle, debugPadHandle);
    }

    /// Chamado a cada VBlank simulado (60&nbsp;Hz) pelo pulso do {@link GspGpuService} — ver
    /// Javadoc da classe. Aplica as ações de `--script` devidas neste quadro, grava o estado
    /// atual de {@link InputState} na memória compartilhada (se já mapeada — ver
    /// {@link MemoryBlockObject#hostMapped()}, o guest pode não ter chamado
    /// `svcMapMemoryBlock` ainda) e sinaliza o evento `PAD0`.
    public void advanceFrame() {
        if (inputScript != null) {
            inputScript.applyDueActions(frameCounter, inputState);
        }
        frameCounter++;

        Object resolvedBlock = handles.resolve(sharedMemoryHandle).orElse(null);
        if (resolvedBlock instanceof MemoryBlockObject block && block.hostMapped()) {
            writeSharedMemory(block.address());
        }
        pad0Event.signal();
    }

    private void writeSharedMemory(int base) {
        int heldMask = inputState.heldButtons();
        memory.write32(base + PAD_STATE_OFFSET, heldMask);
        memory.write16(base + PAD_CIRCLE_OFFSET, inputState.circlePadX());
        memory.write16(base + PAD_CIRCLE_OFFSET + 0x2, inputState.circlePadY());

        padRingIndex = (padRingIndex + 1) % PAD_RING_ENTRY_COUNT;
        memory.write32(base + PAD_RING_INDEX_OFFSET, padRingIndex);
        int padEntry = base + PAD_RING_BUFFER_OFFSET + padRingIndex * PAD_RING_ENTRY_SIZE;
        memory.write32(padEntry, heldMask);
        memory.write32(padEntry + 0x4, heldMask); // "pressed": aproximação, ver Javadoc da classe
        memory.write32(padEntry + 0x8, 0);
        memory.write16(padEntry + 0xC, inputState.circlePadX());
        memory.write16(padEntry + 0xE, inputState.circlePadY());

        boolean touching = inputState.touching();
        memory.write16(base + TOUCH_CURRENT_OFFSET, inputState.touchX());
        memory.write16(base + TOUCH_CURRENT_OFFSET + 0x2, inputState.touchY());
        memory.write32(base + TOUCH_CURRENT_OFFSET + 0x4, touching ? TOUCH_VALID_FLAG : 0);

        touchRingIndex = (touchRingIndex + 1) % TOUCH_RING_ENTRY_COUNT;
        memory.write32(base + TOUCH_RING_INDEX_OFFSET, touchRingIndex);
        int touchEntry = base + TOUCH_RING_BUFFER_OFFSET + touchRingIndex * TOUCH_RING_ENTRY_SIZE;
        memory.write16(touchEntry, inputState.touchX());
        memory.write16(touchEntry + 0x2, inputState.touchY());
        memory.write32(touchEntry + 0x4, touching ? TOUCH_VALID_FLAG : 0);
    }
}
