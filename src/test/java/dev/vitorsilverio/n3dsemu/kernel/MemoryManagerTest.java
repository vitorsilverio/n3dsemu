package dev.vitorsilverio.n3dsemu.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testa {@link MemoryManager} isoladamente (sem `ArmCore`/`AddressSpace` real — a task deste
/// PR1 já observa que "mapear" aqui é só contabilidade, sem tocar em memória de verdade).
class MemoryManagerTest {
    private static final int PAGE_SIZE = 0x1000;
    private static final int CODE_BASE = 0x0010_0000;
    private static final int CODE_SIZE = PAGE_SIZE;
    private static final int GENERAL_HEAP_BASE = 0x0800_0000;
    private static final int GENERAL_HEAP_SIZE = 4 * PAGE_SIZE;
    private static final int LINEAR_HEAP_BASE = 0x1400_0000;
    private static final int LINEAR_HEAP_SIZE = 4 * PAGE_SIZE;

    private MemoryManager newManager() {
        return new MemoryManager(CODE_BASE, CODE_SIZE, GENERAL_HEAP_BASE, GENERAL_HEAP_SIZE, LINEAR_HEAP_BASE, LINEAR_HEAP_SIZE);
    }

    @Test
    void queryMemoryNaRegiaoDeCodigoDevolveEstadoCode() {
        MemoryManager manager = newManager();
        MemoryRegion region = manager.queryMemory(CODE_BASE);
        assertEquals(CODE_BASE, region.base());
        assertEquals(CODE_SIZE, region.size());
        assertEquals(MemoryState.CODE, region.state());
        assertEquals(MemoryPermission.READ_EXECUTE, region.permission());
    }

    @Test
    void queryMemoryEmUmVaoDevolveFreeAPartirDoFimDaRegiaoAnterior() {
        MemoryManager manager = newManager();
        int gapAddress = CODE_BASE + CODE_SIZE + 0x10;

        MemoryRegion region = manager.queryMemory(gapAddress);

        assertEquals(MemoryState.FREE, region.state());
        assertTrue(region.contains(gapAddress));
        assertEquals(CODE_BASE + CODE_SIZE, region.base());
    }

    @Test
    void queryMemoryEntreDuasAlocacoesDevolveOVaoExatoEntreElas() {
        MemoryManager manager = newManager();
        int size = PAGE_SIZE;
        // Duas alocações de tamanho fixo dentro do mesmo pool ficam contíguas por construção do
        // primeiro-encaixe (ver MemoryManager#findFreeGap) — para abrir um vão real entre elas,
        // a do meio é liberada depois.
        MemoryManager.ControlMemoryResult first = manager.controlMemory(
                MemoryOperation.ALLOC, 0, 0, size, MemoryPermission.READ_WRITE);
        MemoryManager.ControlMemoryResult middle = manager.controlMemory(
                MemoryOperation.ALLOC, 0, 0, size, MemoryPermission.READ_WRITE);
        MemoryManager.ControlMemoryResult last = manager.controlMemory(
                MemoryOperation.ALLOC, 0, 0, size, MemoryPermission.READ_WRITE);
        manager.controlMemory(MemoryOperation.FREE, middle.address(), 0, size, MemoryPermission.NONE);

        MemoryRegion gap = manager.queryMemory(middle.address());

        assertEquals(MemoryState.FREE, gap.state());
        assertEquals(first.address() + size, gap.base());
        assertEquals(last.address(), gap.end());
    }

    @Test
    void allocComEnderecoZeroEscolheEndereçoDentroDoPoolGeral() {
        MemoryManager manager = newManager();
        int size = 2 * PAGE_SIZE;

        MemoryManager.ControlMemoryResult result = manager.controlMemory(
                MemoryOperation.ALLOC, 0, 0, size, MemoryPermission.READ_WRITE);

        assertTrue(result.result().isSuccess());
        assertEquals(GENERAL_HEAP_BASE, result.address());

        MemoryRegion queried = manager.queryMemory(result.address());
        assertEquals(MemoryState.PRIVATE, queried.state());
        assertEquals(MemoryPermission.READ_WRITE, queried.permission());
        assertEquals(size, queried.size());
    }

    /// A flag `LINEAR` (sem endereço explícito) roteia para o pool do heap linear
    /// (`0x14000000` no hardware real, 3dbrew: Memory_layout) — achado real corrigido nesta
    /// sessão (G2, investigação 2026-08-16, ver `tasks/FILA-EXECUCAO.md`): a associação estava
    /// invertida, roteando `MEMOP_ALLOC_LINEAR` para o heap GERAL (`0x08000000`).
    @Test
    void allocComFlagLinearUsaOHeapLinear() {
        MemoryManager manager = newManager();
        int rawOperation = MemoryOperation.ALLOC | MemoryOperation.LINEAR_FLAG;

        MemoryManager.ControlMemoryResult result = manager.controlMemory(
                rawOperation, 0, 0, PAGE_SIZE, MemoryPermission.READ_WRITE);

        assertTrue(result.result().isSuccess());
        assertEquals(LINEAR_HEAP_BASE, result.address());
    }

    /// `MEMOP_ALLOC` com endereço EXPLÍCITO no heap linear, sem a flag `LINEAR` setada — o
    /// achado real desta sessão que motivou a detecção de pool por endereço em vez de só pela
    /// flag (`crt0`/libctru commitam `0x08000000` — o heap GERAL — sem a flag, mas outro
    /// endereço explícito dentro do range do heap linear precisa continuar funcionando mesmo
    /// sem a flag, já que endereço explícito é auto-suficiente no Horizon real).
    @Test
    void allocComEnderecoExplicitoNoHeapLinearFuncionaSemAFlag() {
        MemoryManager manager = newManager();

        MemoryManager.ControlMemoryResult result = manager.controlMemory(
                MemoryOperation.ALLOC, LINEAR_HEAP_BASE, 0, PAGE_SIZE, MemoryPermission.READ_WRITE);

        assertTrue(result.result().isSuccess());
        assertEquals(LINEAR_HEAP_BASE, result.address());
    }

    @Test
    void freeDevolveORegiaoParaOPoolEPermiteRealocar() {
        MemoryManager manager = newManager();
        int size = PAGE_SIZE;
        MemoryManager.ControlMemoryResult allocated = manager.controlMemory(
                MemoryOperation.ALLOC, 0, 0, size, MemoryPermission.READ_WRITE);

        MemoryManager.ControlMemoryResult freed = manager.controlMemory(
                MemoryOperation.FREE, allocated.address(), 0, size, MemoryPermission.NONE);
        assertTrue(freed.result().isSuccess());
        assertEquals(MemoryState.FREE, manager.queryMemory(allocated.address()).state());

        MemoryManager.ControlMemoryResult reallocated = manager.controlMemory(
                MemoryOperation.ALLOC, 0, 0, size, MemoryPermission.READ_WRITE);
        assertEquals(allocated.address(), reallocated.address());
    }

    @Test
    void freeDeFaixaQueNaoBateComUmaAlocacaoVivaDevolveErro() {
        MemoryManager manager = newManager();
        MemoryManager.ControlMemoryResult result = manager.controlMemory(
                MemoryOperation.FREE, GENERAL_HEAP_BASE, 0, PAGE_SIZE, MemoryPermission.NONE);
        assertEquals(Result.MEMORY_REGION_NOT_FOUND, result.result());
    }

    @Test
    void allocComTamanhoDesalinhadoDevolveMisalignedSize() {
        MemoryManager manager = newManager();
        MemoryManager.ControlMemoryResult result = manager.controlMemory(
                MemoryOperation.ALLOC, 0, 0, PAGE_SIZE + 1, MemoryPermission.READ_WRITE);
        assertEquals(Result.MISALIGNED_SIZE, result.result());
    }

    @Test
    void allocMaiorQueOPoolDevolveOutOfMemory() {
        MemoryManager manager = newManager();
        MemoryManager.ControlMemoryResult result = manager.controlMemory(
                MemoryOperation.ALLOC, 0, 0, GENERAL_HEAP_SIZE + PAGE_SIZE, MemoryPermission.READ_WRITE);
        assertEquals(Result.OUT_OF_MEMORY, result.result());
    }

    @Test
    void allocComPermissaoDontCareViraReadWrite() {
        MemoryManager manager = newManager();
        MemoryManager.ControlMemoryResult result = manager.controlMemory(
                MemoryOperation.ALLOC, 0, 0, PAGE_SIZE, MemoryPermission.DONT_CARE);
        assertTrue(result.result().isSuccess());
        assertEquals(MemoryPermission.READ_WRITE, manager.queryMemory(result.address()).permission());
    }

    @Test
    void operacaoDesconhecidaDevolveInvalidEnumValue() {
        MemoryManager manager = newManager();
        int unknownOpCode = 0x7F;
        MemoryManager.ControlMemoryResult result = manager.controlMemory(
                unknownOpCode, 0, 0, PAGE_SIZE, MemoryPermission.READ_WRITE);
        assertEquals(Result.INVALID_ENUM_VALUE, result.result());
        assertFalse(result.result().isSuccess());
    }
}
