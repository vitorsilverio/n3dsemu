package dev.vitorsilverio.n3dsemu.gpu;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

import java.util.ArrayList;
import java.util.List;

/// Decodifica e processa a fila de comandos **GX** (`gxCmdQueue_s`/`GX_ProcessCommandList` do
/// libctru, RFC-N3DSEMU G5/PR3) — a camada acima da lista de comandos PICA200 crua que
/// {@link CommandListParser} já sabia ler desde a PR1. É esta fila que
/// `GSPGPU_TriggerCmdReqQueue` (3dbrew) sinaliza: até esta PR, `gsp::Gpu` só CONTAVA os disparos
/// (RFC/task G3: "descarta tudo") — sem ler a fila de verdade, nenhuma lista de comando PICA200
/// chegava a ser interpretada, e `simple_tri` nunca desenhava nada de verdade mesmo com o resto
/// da G5 pronto.
///
/// Layout (transcrito nesta sessão via `WebFetch` da página <a
/// href="https://www.3dbrew.org/wiki/GSP_Shared_Memory">3dbrew: GSP Shared Memory</a>, seção da
/// fila de comandos): cabeçalho de 8 bytes (`commandIndex`/`totalCommands`/`status`/`haltFlag` no
/// primeiro word + código de resultado nos 3 bytes seguintes) seguido de até
/// {@value #MAX_ENTRIES} entradas de 32 bytes cada — o mesmo `gxCmdEntry_s` de `gx.h`
/// (`C:\devkitPro\libctru\include\3ds\gpu\gx.h`, fonte real do lado do cliente).
public final class GxCommandQueue {
    private static final int OFFSET_COMMAND_INDEX = 0x0;
    /// **Número de comandos PENDENTES**, não um índice final (achado real da G5.2, conferido
    /// contra `gspSubmitGxCommand` no `libctru/source/services/gspgpu.c`: o cliente lê
    /// `totalCommands=(hdr>>8)&0xFF`, escreve no slot `(commandIndex+totalCommands)%15`, INCREMENTA
    /// este campo e só chama `GSPGPU_TriggerCmdReqQueue()` quando o valor pós-incremento é `1`,
    /// isto é, quando a fila estava VAZIA). Cabe ao GSP DECREMENTAR o campo à medida que consome:
    /// sem isso o contador nunca volta a zero, o cliente nunca mais dispara a IPC de trigger e a
    /// fila congela para sempre depois do primeiro quadro.
    private static final int OFFSET_TOTAL_COMMANDS = 0x1;
    /// Cabeçalho de **0x20 bytes** (`gspSubmitGxCommand` real: `dst=&sharedGspCmdBuf[8*(1+slot)]`
    /// em palavras de 32 bits → byte `0x20 + slot*0x20`). A G5/PR3 usava `0x8` — lia a palavra 2 do
    /// cabeçalho como se fosse o começo da primeira entrada, e o `type` decodificado saía sempre
    /// `0` (`RequestDma`) em vez do comando real.
    private static final int OFFSET_ENTRIES = 0x20;
    private static final int ENTRY_SIZE_BYTES = 0x20;
    /// 15 slots — valor LITERAL do `%15` de `gspSubmitGxCommand` (o `0x200` por cliente comporta
    /// 15 entradas de 32 bytes depois do cabeçalho de 32 bytes, com 0 bytes de sobra).
    private static final int MAX_ENTRIES = 15;

    private static final int ENTRY_OFFSET_TYPE = 0x0;
    private static final int ENTRY_OFFSET_ARG1 = 0x4;
    private static final int ENTRY_OFFSET_ARG2 = 0x8;

    private static final int TYPE_REQUEST_DMA = 0;
    private static final int TYPE_PROCESS_COMMAND_LIST = 1;
    private static final int TYPE_MEMORY_FILL = 2;
    private static final int TYPE_DISPLAY_TRANSFER = 3;
    private static final int TYPE_TEXTURE_COPY = 4;
    private static final int TYPE_FLUSH_CACHE_REGIONS = 5;
    private static final int U8_MASK = 0xFF;

    /// Índices de `GSPGPU_Event` (`gspgpu.h` real: `PSC0=0,PSC1=1,VBlank0=2,VBlank1=3,PPF=4,
    /// P3D=5,DMA=6`) que cada tipo de comando GX sinaliza ao terminar — o mesmo valor que
    /// `GspGpuService#pushInterrupt` já usa para VBlank (`2`).
    public static final int EVENT_PSC0 = 0;
    public static final int EVENT_PPF = 4;
    public static final int EVENT_P3D = 5;
    public static final int EVENT_DMA = 6;

    /// Recebe uma lista de comandos PICA200 crua (words de 32 bits) já lida da memória do guest —
    /// implementado por quem sabe interpretar a GPU de verdade (`GspGpuService`, RFC/task).
    @FunctionalInterface
    public interface CommandListSink {
        void onCommandList(int[] words);
    }

    private GxCommandQueue() {
    }

    /// Processa toda entrada pendente (`commandIndex` até `totalCommands-1`, RFC/task: "GSP
    /// incrementa `commandIndex` antes de tratar o comando") da fila em `queueBase` e avança
    /// `commandIndex` de volta na memória do guest. Retorna, na ordem de processamento, os
    /// eventos `GSPGPU_Event` que devem ser sinalizados — cabe ao chamador ({@link
    /// dev.vitorsilverio.n3dsemu.service.GspGpuService}) empurrá-los na fila de interrupções e
    /// sinalizar {@code interruptEvent}, exatamente como já faz para VBlank.
    ///
    /// **Comandos não-`ProcessCommandList`** (`MemoryFill`/`DisplayTransfer`/`TextureCopy`/
    /// `RequestDma`) são tratados como concluídos IMEDIATAMENTE, sem efeito de memória real (RFC/
    /// task, "Não inclui": sem essas transferências nesta task — `simple_tri` desenha DIRETO na
    /// textura de apresentação, ver Javadoc de `VulkanRenderer`, então o `DisplayTransfer` que um
    /// app real faria para levar o *color buffer* da PICA200 até o framebuffer exibido não tem
    /// efeito observável aqui). Sinalizar o evento mesmo sem executar o efeito é necessário: sem
    /// isso, `gxCmdQueueWait`/`gspWaitForEvent` do guest trava para sempre (mesma classe de bug já
    /// documentada para VBlank/`RegisterInterruptRelayQueue`, ver Javadoc de `GspGpuService`).
    public static List<Integer> processPending(AddressSpace memory, int queueBase, CommandListSink sink) {
        List<Integer> events = new ArrayList<>();
        int commandIndex = memory.read8(queueBase + OFFSET_COMMAND_INDEX) & U8_MASK;
        int pending = memory.read8(queueBase + OFFSET_TOTAL_COMMANDS) & U8_MASK;
        for (int processed = 0; processed < pending; processed++) {
            int entryBase = queueBase + OFFSET_ENTRIES + (commandIndex % MAX_ENTRIES) * ENTRY_SIZE_BYTES;
            int type = memory.read8(entryBase + ENTRY_OFFSET_TYPE) & U8_MASK;
            processEntry(memory, entryBase, type, sink, events);
            commandIndex = (commandIndex + 1) % MAX_ENTRIES;
        }
        memory.write8(queueBase + OFFSET_COMMAND_INDEX, commandIndex);
        // Devolve a fila VAZIA ao cliente: o `pending` lido acima foi todo consumido aqui (este
        // HLE processa a lista inteira de forma síncrona dentro da própria IPC de trigger, ao
        // contrário do GSP real, que é assíncrono). Sem zerar, `gspSubmitGxCommand` nunca voltaria
        // a ver `totalCommands==1` e nunca mais dispararia `GSPGPU_TriggerCmdReqQueue`.
        int remaining = (memory.read8(queueBase + OFFSET_TOTAL_COMMANDS) & U8_MASK) - pending;
        memory.write8(queueBase + OFFSET_TOTAL_COMMANDS, Math.max(0, remaining));
        return events;
    }

    private static void processEntry(AddressSpace memory, int entryBase, int type, CommandListSink sink,
                                      List<Integer> events) {
        switch (type) {
            case TYPE_PROCESS_COMMAND_LIST -> {
                int bufferAddress = memory.read32(entryBase + ENTRY_OFFSET_ARG1);
                int bufferSizeBytes = memory.read32(entryBase + ENTRY_OFFSET_ARG2);
                sink.onCommandList(readWords(memory, bufferAddress, bufferSizeBytes / Integer.BYTES));
                events.add(EVENT_P3D);
            }
            case TYPE_MEMORY_FILL -> events.add(EVENT_PSC0);
            case TYPE_DISPLAY_TRANSFER, TYPE_TEXTURE_COPY -> events.add(EVENT_PPF);
            case TYPE_REQUEST_DMA -> events.add(EVENT_DMA);
            case TYPE_FLUSH_CACHE_REGIONS -> { } // 3dbrew: não enfileirável/sem evento de conclusão próprio.
            default -> { } // tipo desconhecido: ignora, mesma postura conservadora do resto do HLE.
        }
    }

    private static int[] readWords(AddressSpace memory, int baseAddress, int wordCount) {
        int[] words = new int[Math.max(0, wordCount)];
        for (int i = 0; i < words.length; i++) {
            words[i] = memory.read32(baseAddress + 4 * i);
        }
        return words;
    }
}
