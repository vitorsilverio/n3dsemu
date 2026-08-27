package dev.vitorsilverio.n3dsemu;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.core.ExclusiveMonitor;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.n3dsemu.core.N3dsCp15;
import dev.vitorsilverio.n3dsemu.gpu.FrameBufferState;
import dev.vitorsilverio.n3dsemu.gpu.PicaRenderer;
import dev.vitorsilverio.n3dsemu.input.InputScript;
import dev.vitorsilverio.n3dsemu.input.InputState;
import dev.vitorsilverio.n3dsemu.kernel.HandleTable;
import dev.vitorsilverio.n3dsemu.kernel.MemoryManager;
import dev.vitorsilverio.n3dsemu.kernel.ProcessObject;
import dev.vitorsilverio.n3dsemu.kernel.Scheduler;
import dev.vitorsilverio.n3dsemu.kernel.SvcTable;
import dev.vitorsilverio.n3dsemu.kernel.ThreadObject;
import dev.vitorsilverio.n3dsemu.loader.Image3dsx;
import dev.vitorsilverio.n3dsemu.memory.MemoryMap;
import dev.vitorsilverio.n3dsemu.memory.N3dsAddressSpace;
import dev.vitorsilverio.n3dsemu.service.AptService;
import dev.vitorsilverio.n3dsemu.service.CfgUService;
import dev.vitorsilverio.n3dsemu.service.FsUserService;
import dev.vitorsilverio.n3dsemu.service.GspGpuService;
import dev.vitorsilverio.n3dsemu.service.HidService;
import dev.vitorsilverio.n3dsemu.service.PtmUService;
import dev.vitorsilverio.n3dsemu.service.ServiceRegistry;
import dev.vitorsilverio.n3dsemu.service.SrvService;

import java.io.PrintStream;

/// Hospedeiro do ARM11 em modo aplicação (marco M1 da RFC-N3DSEMU): monta memória + CPU +
/// tabela de SVCs a partir de um `.3dsx` já carregado, headless (sem gráfico — RFC D4, G4 em
/// diante). Espelha a estrutura de `VersatilePbMachine` do `virtual-arm-box`.
public final class N3dsMachine {
    /// Backend de execução do CPU core — mesmo enum conceitual do `armbox`/`virtual-arm-box`.
    public enum Backend {
        JIT, INTERPRETED, CHECK
    }

    private static final int BLOCK_CACHE_ENTRIES = 8192;
    private static final int HOT_THRESHOLD = 3;
    /// Blocos por fatia do laço principal do `Main` — mesmo padrão de "fatia" do
    /// `virtual-arm-box`.
    public static final int RUN_SLICE_BLOCKS = 256;

    private static final int REGISTER_SP = 13;

    /// Identificadores plausíveis fixos do único processo/thread principal do guest (RFC-
    /// N3DSEMU: sem `svcCreateProcess` — RFC D1, um processo só) — ver {@link ProcessObject}/
    /// {@link ThreadObject}.
    private static final int MAIN_PROCESS_ID = 0;
    private static final int MAIN_THREAD_ID = 1;
    /// Prioridade plausível fixa da thread principal — `0x30`, o valor típico usado por
    /// aplicativos 3DS reais (menor número = maior prioridade; 3dbrew/observação de binários
    /// reais, não documentado como uma constante única e oficial).
    private static final int MAIN_THREAD_PRIORITY = 0x30;

    /// Ponteiro inicial de pilha da thread PRINCIPAL (placeholder — o Horizon real recebe a
    /// pilha do processo via `svcCreateProcess`/`Exheader`, fora do escopo desta HLE, RFC D1: um
    /// processo só, sem `svcCreateProcess`). Usa o topo da região do heap geral
    /// (`MemoryMap.GENERAL_HEAP_BASE` + `GENERAL_HEAP_SIZE`, 16 MiB de folga), só para o crt0 do
    /// libctru ter uma pilha válida. Threads NOVAS (`svcCreateThread`, G2 PR2) recebem a pilha
    /// que o próprio guest aloca e passa como argumento — não usam este placeholder.
    private static final int INITIAL_STACK_POINTER = MemoryMap.GENERAL_HEAP_BASE + MemoryMap.GENERAL_HEAP_SIZE;

    private final ArmCore core;
    private final JitRuntime runtime;
    private final SvcTable svcTable;
    private final InputState inputState;
    private final AddressSpace memory;
    private final GspGpuService gspGpuService;

    private N3dsMachine(ArmCore core, JitRuntime runtime, SvcTable svcTable, InputState inputState,
                         AddressSpace memory, GspGpuService gspGpuService) {
        this.core = core;
        this.runtime = runtime;
        this.svcTable = svcTable;
        this.inputState = inputState;
        this.memory = memory;
        this.gspGpuService = gspGpuService;
    }

    /// Monta a máquina completa e posiciona o core no `entryPoint` de `image`, pronta para
    /// {@link #runSlice()}. Sem `--script` (ver a sobrecarga abaixo) — só injeção de input via
    /// {@link #pressButtons}/{@link #releaseButtons}/{@link #setTouch}/{@link #setCirclePad}.
    ///
    /// @param image           executável `.3dsx` já carregado/relocado
    /// @param backend         backend de execução do core
    /// @param diagnosticLog   destino do log de barramento aberto e do trace de SVC
    /// @param traceSvc        se `true`, cada `svc` interceptada é impressa em `diagnosticLog`
    public static N3dsMachine create(Image3dsx image, Backend backend, PrintStream diagnosticLog, boolean traceSvc) {
        return create(image, backend, diagnosticLog, traceSvc, null);
    }

    /// Como {@link #create(Image3dsx, Backend, PrintStream, boolean)}, mas com um
    /// {@link InputScript} opcional (RFC-N3DSEMU G3 — `--script=<arquivo>`) aplicado a cada
    /// VBlank simulado por {@link HidService#advanceFrame}.
    public static N3dsMachine create(Image3dsx image, Backend backend, PrintStream diagnosticLog, boolean traceSvc,
                                      InputScript inputScript) {
        return create(image, backend, diagnosticLog, traceSvc, inputScript, null);
    }

    /// Como a sobrecarga acima, mas com um {@link PicaRenderer} real (RFC-N3DSEMU G5/PR3) — sem
    /// ele, `gsp::Gpu` aplica/conta os registradores da GPU mas não desenha nada (mesmo
    /// comportamento de antes desta PR, usado pelo `--headless`/testes). `Main` (modo janela)
    /// passa o {@code VulkanRenderer} real aqui; é isto que faz `simple_tri` desenhar de verdade.
    public static N3dsMachine create(Image3dsx image, Backend backend, PrintStream diagnosticLog, boolean traceSvc,
                                      InputScript inputScript, PicaRenderer renderer) {
        PagedAddressSpace memory = N3dsAddressSpace.create(image, diagnosticLog);

        // B5.2: ARMv6K + VFPv2, sem Thumb-2 (o MPCore do 3DS é ARMv6K, não ARMv6T2).
        ArmArchitecture architecture = ArmArchitecture.ARM11_MPCORE;
        JitRuntime runtime = switch (backend) {
            case JIT -> JitRuntimeFactory.armThumb(BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, architecture);
            case INTERPRETED ->
                    JitRuntimeFactory.interpretedArmThumb(BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, architecture);
            case CHECK ->
                    JitRuntimeFactory.divergenceCheckingArmThumb(BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, architecture);
        };

        ThreadObject mainThread = ThreadObject.mainThread(MAIN_THREAD_ID, MAIN_THREAD_PRIORITY);
        HandleTable handles = new HandleTable(new ProcessObject(MAIN_PROCESS_ID), mainThread);
        MemoryManager memoryManager = new MemoryManager(
                MemoryMap.EXECUTABLE_BASE, paddedExecutableSize(image),
                MemoryMap.GENERAL_HEAP_BASE, MemoryMap.GENERAL_HEAP_SIZE,
                MemoryMap.LINEAR_HEAP_BASE, MemoryMap.LINEAR_HEAP_SIZE);
        Scheduler scheduler = new Scheduler();

        // RFC-N3DSEMU G3: registro de serviços + IPC. gsp::Gpu registra o pulso de VBlank do
        // sistema no Scheduler (ver Javadoc de GspGpuService) — pode acontecer aqui, ANTES de
        // scheduler.attach, porque registerPeriodicPulse não exige core anexado ainda (mesma
        // dependência circular já documentada nesta classe para SvcTable/attach).
        InputState inputState = new InputState();
        ServiceRegistry serviceRegistry = new ServiceRegistry();
        HidService hidService = new HidService(diagnosticLog, memory, handles, inputState, inputScript);
        GspGpuService gspGpuService = new GspGpuService(diagnosticLog, memory, handles, scheduler, hidService, renderer);
        serviceRegistry.register(new SrvService(diagnosticLog, handles, serviceRegistry));
        AptService aptService = new AptService(diagnosticLog, handles);
        serviceRegistry.register(aptService);
        // aptInit real (libctru) abre 3 sessões (APT:U/APT:S/APT:A) para o mesmo serviço do
        // lado do sistema — sem os aliases, GetServiceHandle("APT:S") falhava e aptInit
        // abortava antes do guest desenhar qualquer coisa (achado ao validar G4 com
        // hello-world.3dsx: janela abria em preto, sem texto).
        serviceRegistry.registerAlias("APT:S", aptService);
        serviceRegistry.registerAlias("APT:A", aptService);
        serviceRegistry.register(hidService);
        serviceRegistry.register(new FsUserService(diagnosticLog, handles, serviceRegistry, memory, image.rawFile()));
        serviceRegistry.register(gspGpuService);
        serviceRegistry.register(new CfgUService(diagnosticLog, memory));
        serviceRegistry.register(new PtmUService(diagnosticLog));

        SvcTable svcTable = new SvcTable(memory, diagnosticLog, traceSvc, handles, memoryManager, scheduler, serviceRegistry);
        ArmCore core = new ArmCore(memory, svcTable.dispatcher(), architecture);
        svcTable.attach(core);
        // RFC D1: monitor de exclusividade COMPARTILHADO instalado desde já, mesmo com um só
        // núcleo — para o segundo núcleo do MPCore entrar depois sem refactor.
        core.setExclusiveMonitor(new ExclusiveMonitor());
        N3dsCp15 cp15 = new N3dsCp15();
        core.setCoprocessorBus(cp15);

        core.configureExecutionState(image.entryPoint(), CpuMode.USER, InstructionSet.ARM, false, false);
        core.setRegister(REGISTER_SP, INITIAL_STACK_POINTER);
        // Registra a thread principal (já posicionada acima) como a corrente do escalonador —
        // depois deste ponto, toda troca de contexto entre threads (G2 PR2) passa pelo
        // Scheduler, nunca por manipulação direta do ArmCore.
        scheduler.attach(core, cp15, mainThread);

        return new N3dsMachine(core, runtime, svcTable, inputState, memory, gspGpuService);
    }

    // ── injeção de input sem GUI (RFC-N3DSEMU G3) ───────────────────────────────────────────

    /// Marca os botões em `mask` (convenção `KEY_*`, ver
    /// {@link dev.vitorsilverio.n3dsemu.input.Keys}) como pressionados, até {@link #releaseButtons}.
    public void pressButtons(int mask) {
        inputState.pressButtons(mask);
    }

    public void releaseButtons(int mask) {
        inputState.releaseButtons(mask);
    }

    public void setTouch(int x, int y) {
        inputState.setTouch(x, y);
    }

    public void setCirclePad(int dx, int dy) {
        inputState.setCirclePad(dx, dy);
    }

    /// Executa uma fatia do laço principal ({@link #RUN_SLICE_BLOCKS} blocos). Propaga
    /// {@link dev.vitorsilverio.n3dsemu.kernel.UnsupportedSvcException} sem capturá-la — é o
    /// `Main` quem decide o que fazer ao encontrar a primeira `svc`.
    public long runSlice() {
        return core.runBlocks(runtime, RUN_SLICE_BLOCKS);
    }

    public ArmCore core() {
        return core;
    }

    public JitRuntime runtime() {
        return runtime;
    }

    public SvcTable svcTable() {
        return svcTable;
    }

    /// Memória do guest — usada pelo laço de apresentação (`Main`, G4) para ler os bytes do
    /// framebuffer ativo de cada tela (ver {@link #frameBufferState()}) antes de repassar a
    /// {@link dev.vitorsilverio.n3dsemu.gpu.PicaRenderer#presentScreen}.
    public AddressSpace memory() {
        return memory;
    }

    /// Buffer ativo mais recente de cada tela (`GSPGPU_SetBufferSwap`, G4) — ver Javadoc de
    /// {@link FrameBufferState}.
    public FrameBufferState frameBufferState() {
        return gspGpuService.frameBufferState();
    }

    /// Ver Javadoc de {@link GspGpuService#setVBlankListener} — o gancho que o laço de
    /// apresentação (`Main`, G4) usa para saber quando ler os framebuffers e apresentar.
    public void setVBlankListener(Runnable listener) {
        gspGpuService.setVBlankListener(listener);
    }

    // Mesmo arredondamento de N3dsAddressSpace#buildExecutableImage (PagedAddressSpace exige
    // backing múltiplo de pageSize) — usa N3dsAddressSpace#executableRegionSize (G3: já conta o
    // espaçamento entre segmentos, ver Javadoc daquele método) em vez de image.totalSize(), que
    // conta só a soma bruta dos 3 tamanhos do arquivo (sem o padding entre segmentos).
    private static int paddedExecutableSize(Image3dsx image) {
        int rawSize = N3dsAddressSpace.executableRegionSize(image);
        return (rawSize + MemoryMap.PAGE_SIZE - 1) & ~(MemoryMap.PAGE_SIZE - 1);
    }
}
