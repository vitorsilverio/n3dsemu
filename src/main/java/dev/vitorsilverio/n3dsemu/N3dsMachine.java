package dev.vitorsilverio.n3dsemu;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.core.ExclusiveMonitor;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.n3dsemu.core.N3dsCp15;
import dev.vitorsilverio.n3dsemu.kernel.SvcTable;
import dev.vitorsilverio.n3dsemu.loader.Image3dsx;
import dev.vitorsilverio.n3dsemu.memory.MemoryMap;
import dev.vitorsilverio.n3dsemu.memory.N3dsAddressSpace;

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

    /// Ponteiro inicial de pilha (placeholder do esqueleto — RFC D2/D1: sem `svcCreateThread`
    /// real ainda, isso é G2). Usa o topo da região do heap "novo" (`MemoryMap.NEW_HEAP_BASE`
    /// + `NEW_HEAP_SIZE`, 16 MiB de folga), só para o crt0 do libctru ter uma pilha válida até
    /// a primeira `svc`. A G2, ao implementar `svcCreateThread`/o handoff real do Horizon,
    /// deve substituir isto por uma pilha alocada de verdade.
    private static final int INITIAL_STACK_POINTER = MemoryMap.NEW_HEAP_BASE + MemoryMap.NEW_HEAP_SIZE;

    private final ArmCore core;
    private final JitRuntime runtime;
    private final SvcTable svcTable;

    private N3dsMachine(ArmCore core, JitRuntime runtime, SvcTable svcTable) {
        this.core = core;
        this.runtime = runtime;
        this.svcTable = svcTable;
    }

    /// Monta a máquina completa e posiciona o core no `entryPoint` de `image`, pronta para
    /// {@link #runSlice()}.
    ///
    /// @param image           executável `.3dsx` já carregado/relocado
    /// @param backend         backend de execução do core
    /// @param diagnosticLog   destino do log de barramento aberto e do trace de SVC
    /// @param traceSvc        se `true`, cada `svc` interceptada é impressa em `diagnosticLog`
    public static N3dsMachine create(Image3dsx image, Backend backend, PrintStream diagnosticLog, boolean traceSvc) {
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

        SvcTable svcTable = new SvcTable(memory, diagnosticLog, traceSvc);
        ArmCore core = new ArmCore(memory, svcTable.dispatcher(), architecture);
        svcTable.attach(core);
        // RFC D1: monitor de exclusividade COMPARTILHADO instalado desde já, mesmo com um só
        // núcleo — para o segundo núcleo do MPCore entrar depois sem refactor.
        core.setExclusiveMonitor(new ExclusiveMonitor());
        core.setCoprocessorBus(new N3dsCp15());

        core.configureExecutionState(image.entryPoint(), CpuMode.USER, InstructionSet.ARM, false, false);
        core.setRegister(REGISTER_SP, INITIAL_STACK_POINTER);

        return new N3dsMachine(core, runtime, svcTable);
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
}
