package dev.vitorsilverio.n3dsemu.kernel;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpsrRegister;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.swi.CpuState;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;

import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/// Tabela de SVCs do kernel Horizon (RFC-N3DSEMU D2). Nesta task (G1, marco M1) **nenhuma**
/// SVC tem implementação real: toda chamada é interceptada pelo {@link SwiDispatcher} do
/// arm-jitter (mesmo mecanismo que gbaemu/ndsemu usam para a BIOS), registrada num trace
/// circular (para o `--trace-svc` do `Main`) e sempre lança {@link UnsupportedSvcException} —
/// a implementação de verdade é a G2.
///
/// **Achado real (não é um bug desta task, documentado aqui porque explica o código
/// abaixo):** `ArmDecoder`/`ThumbDecoder` do arm-jitter (compartilhados com gbaemu/ndsemu)
/// decodificam o imediato de 24 bits de `SWI`/`SVC` em modo ARM como `(raw & 0xFFFFFF) >>>
/// 16` — a convenção do BIOS do GBA/NDS, onde o número da SWI mora nos 8 bits ALTOS do campo
/// (GBATEK). O kernel Horizon do 3DS usa a convenção OPOSTA: `svc 0x21` grava `0x21` direto
/// no campo de 24 bits (confirmado via `objdump` no `libctru.a` real — `ef000021`), então o
/// valor que chegaria por {@code swi.immediate()}/o parâmetro do dispatcher seria sempre 0
/// para qualquer SVC real do 3DS (o byte baixo, onde mora o número, é descartado pelo `>>>
/// 16` antes de qualquer código do host ver o valor). Em THUMB o problema não existe — o
/// campo de `SVC` de 8 bits já É o número, sem shift —, mas o `ARM11_MPCORE` (B5.2: ARMv6K
/// COM Thumb-1 clássico, só SEM Thumb-2 largo) pode rodar código em qualquer um dos dois
/// estados. Mudar o decoder compartilhado quebraria a convenção GBA/NDS para
/// gbaemu/ndsemu/armbox (G3: sem breaking change) — em vez disso, {@link #handle} relê a
/// instrução `svc` crua da memória do guest (endereço = `pc - 4` em ARM ou `pc - 2` em THUMB,
/// já que {@code state.pc()} é sempre o PC SEQUENCIAL, avançado antes do dispatch) e extrai o
/// byte baixo do imediato bruto — a convenção real do Horizon, igual nos dois estados.
public final class SvcTable {
    private static final int DEFAULT_TRACE_CAPACITY = 32;
    private static final int ARM_INSTRUCTION_SIZE = 4;
    private static final int THUMB_INSTRUCTION_SIZE = 2;
    private static final int HORIZON_SVC_NUMBER_MASK = 0xFF;
    private static final int REGISTER_R0 = 0;
    /// Um `Result` do Horizon é `0` quando bem-sucedido (qualquer valor não-zero é um
    /// código de erro — bits de descrição/módulo/nível, ver 3dbrew: Error codes). Gravado em
    /// `r0` antes de {@link UnsupportedSvcException} propagar (ver Javadoc da classe): sem
    /// isso, o chamador recebe o `r0` de ENTRADA (o próprio argumento da chamada, não um
    /// `Result`) como se fosse o retorno, e quase sempre o interpreta como um erro — o
    /// código do libctru entra em ramos de tratamento de falha quase imediatamente,
    /// divergindo de qualquer sequência real de `svc`s de inicialização. `0` mantém a
    /// execução no caminho feliz o quanto der, sem implementar NENHUMA semântica real da
    /// SVC (nenhum handle é criado, nenhuma memória é tocada) — daí não contradizer "Não
    /// inclui: nenhuma svc implementada de verdade" da task.
    private static final int RESULT_SUCCESS = 0;

    private final AddressSpace memory;
    private final PrintStream traceLog;
    private final boolean traceEnabled;
    private final int traceCapacity;
    private final Deque<SvcCall> recentCalls = new ArrayDeque<>();
    private ArmCore core;

    public SvcTable(AddressSpace memory, PrintStream traceLog, boolean traceEnabled) {
        this(memory, traceLog, traceEnabled, DEFAULT_TRACE_CAPACITY);
    }

    public SvcTable(AddressSpace memory, PrintStream traceLog, boolean traceEnabled, int traceCapacity) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.traceLog = Objects.requireNonNull(traceLog, "traceLog");
        this.traceEnabled = traceEnabled;
        this.traceCapacity = traceCapacity;
    }

    /// Liga esta tabela ao {@link ArmCore} que a criou (necessário para gravar `r0` antes de
    /// lançar — ver {@link #RESULT_SUCCESS}). Chamado pelo `N3dsMachine` logo após construir
    /// o core, já que o {@link SwiDispatcher} precisa existir ANTES do core (dependência
    /// circular do construtor).
    public void attach(ArmCore core) {
        this.core = Objects.requireNonNull(core, "core");
    }

    /// Cria o {@link SwiDispatcher} que encaminha toda `svc` para {@link #handle}.
    public SwiDispatcher dispatcher() {
        SwiDispatcher dispatcher = SwiDispatcher.empty();
        dispatcher.fallbackWithNumber(this::handle);
        return dispatcher;
    }

    private CpuState handle(int decoderImmediate, CpuState state) {
        int svcNumber = realSvcNumber(state);
        SvcCall call = new SvcCall(svcNumber, HorizonSvcNames.nameOf(svcNumber), state.r0(), state.r1(), state.pc());
        recentCalls.addLast(call);
        while (recentCalls.size() > traceCapacity) {
            recentCalls.removeFirst();
        }
        if (traceEnabled) {
            traceLog.println(call.format());
        }
        core.setRegister(REGISTER_R0, RESULT_SUCCESS);
        throw new UnsupportedSvcException(call);
    }

    // Ver Javadoc da classe: relê a instrução crua em vez de confiar no imediato já
    // pré-deslocado (convenção GBA/NDS) que o decoder compartilhado entrega.
    private int realSvcNumber(CpuState state) {
        boolean thumb = (state.cpsr() & CpsrRegister.THUMB_FLAG) != 0;
        if (thumb) {
            int instructionAddress = state.pc() - THUMB_INSTRUCTION_SIZE;
            return memory.read16(instructionAddress) & HORIZON_SVC_NUMBER_MASK;
        }
        int instructionAddress = state.pc() - ARM_INSTRUCTION_SIZE;
        return memory.read32(instructionAddress) & HORIZON_SVC_NUMBER_MASK;
    }

    /// Até as últimas {@code traceCapacity} chamadas observadas, da mais antiga para a mais
    /// recente — usado pelo `Main` para imprimir o trace ao sair (código 3).
    public List<SvcCall> recentCalls() {
        return List.copyOf(recentCalls);
    }
}
