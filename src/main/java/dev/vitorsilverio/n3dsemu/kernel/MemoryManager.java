package dev.vitorsilverio.n3dsemu.kernel;

import dev.vitorsilverio.n3dsemu.memory.MemoryMap;

import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;

/// Contabilidade de `svcControlMemory`/`svcQueryMemory` (RFC-N3DSEMU G2): **não há MMU** (RFC
/// D2) — toda a memória que este gerente rastreia já está fisicamente mapeada e com backing real
/// em `dev.vitorsilverio.n3dsemu.memory.N3dsAddressSpace` desde o boot. "Alocar" aqui é só
/// escolher/reservar uma sub-faixa livre dentro do heap geral ou do heap linear e anotar seu
/// estado/permissão; nenhum byte de memória é tocado, nenhuma página é remapeada de verdade.
///
/// Todos os endereços usados neste projeto (`MemoryMap`) são menores que `0x8000_0000`, então a
/// ordem natural de {@link Integer} já é a ordem sem sinal — o {@link TreeMap} não precisa de um
/// comparador especial.
public final class MemoryManager {
    private final TreeMap<Integer, MemoryRegion> regions = new TreeMap<>();
    private final int generalHeapBase;
    private final int generalHeapSize;
    private final int linearHeapBase;
    private final int linearHeapSize;

    /// @param codeBase        base do executável carregado (marcado {@link MemoryState#CODE} de
    ///                         largada, para `svcQueryMemory` sobre o próprio código responder algo
    ///                         plausível mesmo sem nenhuma `svcControlMemory` ainda)
    /// @param codeSize        tamanho do executável (já arredondado a página pelo chamador)
    /// @param generalHeapBase {@link MemoryMap#GENERAL_HEAP_BASE}
    /// @param generalHeapSize {@link MemoryMap#GENERAL_HEAP_SIZE}
    /// @param linearHeapBase  {@link MemoryMap#LINEAR_HEAP_BASE}
    /// @param linearHeapSize  {@link MemoryMap#LINEAR_HEAP_SIZE}
    public MemoryManager(int codeBase, int codeSize, int generalHeapBase, int generalHeapSize,
                          int linearHeapBase, int linearHeapSize) {
        this.generalHeapBase = generalHeapBase;
        this.generalHeapSize = generalHeapSize;
        this.linearHeapBase = linearHeapBase;
        this.linearHeapSize = linearHeapSize;
        put(new MemoryRegion(codeBase, codeSize, MemoryState.CODE, MemoryPermission.READ_EXECUTE));
    }

    /// `svcControlMemory`: resultado + endereço de saída (`r1`, só significativo em sucesso).
    public record ControlMemoryResult(Result result, int address) {
        static ControlMemoryResult failure(Result result) {
            return new ControlMemoryResult(result, 0);
        }
    }

    /// Executa uma `svcControlMemory` (convenção de registradores real, ver Javadoc de
    /// {@code SvcTable#handleControlMemory}): `rawOperation` é o `MemOp` bruto (opcode + flag
    /// `LINEAR` + região preferida), `addr0`/`size` os parâmetros homônimos e `permission` a
    /// {@link MemoryPermission} pedida (só usada por `ALLOC`/`PROTECT`).
    public ControlMemoryResult controlMemory(int rawOperation, int addr0, int addr1, int size, int permission) {
        int opCode = MemoryOperation.opCode(rawOperation);
        boolean linear = MemoryOperation.isLinear(rawOperation);
        // Achado real (investigação 2026-08-16, ver tasks/FILA-EXECUCAO.md): a flag LINEAR só
        // decide o pool quando o chamador pede "kernel escolhe o endereço" (addr0==0) — no
        // Horizon real, um addr0 EXPLÍCITO já identifica sozinho a região de memória (a flag é
        // redundante nesse caso, e o crt0/libctru de fato NÃO a seta ao committar
        // 0x08000000==GENERAL_HEAP_BASE sem LINEAR). Escolher o pool só pela flag, ignorando
        // addr0, rejeitava com OUT_OF_RANGE um ALLOC legítimo no heap geral feito sem a flag.
        int poolBase;
        int poolSize;
        if (addr0 != 0) {
            boolean inLinearPool = fitsInPool(addr0, size, linearHeapBase, linearHeapSize);
            poolBase = inLinearPool ? linearHeapBase : generalHeapBase;
            poolSize = inLinearPool ? linearHeapSize : generalHeapSize;
        } else {
            poolBase = linear ? linearHeapBase : generalHeapBase;
            poolSize = linear ? linearHeapSize : generalHeapSize;
        }

        return switch (opCode) {
            case MemoryOperation.ALLOC -> commit(poolBase, poolSize, addr0, size,
                    MemoryState.PRIVATE, effectivePermission(permission));
            case MemoryOperation.RESERVE -> commit(poolBase, poolSize, addr0, size,
                    MemoryState.RESERVED, MemoryPermission.NONE);
            case MemoryOperation.FREE -> free(addr0, size);
            case MemoryOperation.PROTECT -> protect(addr0, size, effectivePermission(permission));
            // MAP/UNMAP: sem MMU, viram só uma anotação de estado (ver Javadoc da classe) —
            // nenhum jogo do corpus desta sessão os exercita (só ALLOC/FREE, na inicialização
            // do heap do libctru); documentado como simplificação, não uma implementação real
            // de aliasing de memória compartilhada (isso é `svcMapMemoryBlock`, G2 PR2/G3).
            case MemoryOperation.MAP -> remapAnnotationOnly(addr0, size, MemoryState.ALIASED,
                    effectivePermission(permission));
            case MemoryOperation.UNMAP -> remapAnnotationOnly(addr0, size, MemoryState.FREE, MemoryPermission.NONE);
            default -> ControlMemoryResult.failure(Result.INVALID_ENUM_VALUE);
        };
    }

    /// `svcQueryMemory`: a região que contém `address`, real (rastreada) ou um vão
    /// {@link MemoryState#FREE} sintetizado entre duas regiões rastreadas (ou até os limites do
    /// espaço de 32 bits) — nunca `null`, espelhando o hardware real onde toda consulta responde
    /// com algo.
    public MemoryRegion queryMemory(int address) {
        Map.Entry<Integer, MemoryRegion> floor = regions.floorEntry(address);
        if (floor != null && floor.getValue().contains(address)) {
            return floor.getValue();
        }
        int gapStart = floor != null ? floor.getValue().end() : 0;
        Map.Entry<Integer, MemoryRegion> ceiling = regions.ceilingEntry(address);
        // Sentinela do topo: `0xFFFFFFFF`, não `0x1_0000_0000` (uma unidade além do maior
        // endereço de 32 bits representável) — usar o valor exato faria `gapStart + gapSize`
        // dar a volta para `0` em aritmética de `int`, quebrando `MemoryRegion#contains` (que
        // assume `end() > base()` sem sinal). `0xFFFFFFFF` cabe como bit pattern de `-1`, e
        // `base + (0xFFFFFFFF - base)` fecha exatamente nele sem dar a volta.
        long gapEndExclusive = ceiling != null ? Integer.toUnsignedLong(ceiling.getKey()) : 0xFFFF_FFFFL;
        int gapSize = (int) (gapEndExclusive - Integer.toUnsignedLong(gapStart));
        return new MemoryRegion(gapStart, gapSize, MemoryState.FREE, MemoryPermission.NONE);
    }

    private ControlMemoryResult commit(int poolBase, int poolSize, int addr0, int size, int state, int permission) {
        if (size <= 0 || !isPageAligned(size)) {
            return ControlMemoryResult.failure(Result.MISALIGNED_SIZE);
        }
        if (addr0 != 0 && !isPageAligned(addr0)) {
            return ControlMemoryResult.failure(Result.MISALIGNED_ADDRESS);
        }
        int base;
        if (addr0 != 0) {
            if (!fitsInPool(addr0, size, poolBase, poolSize)) {
                return ControlMemoryResult.failure(Result.OUT_OF_RANGE);
            }
            if (overlapsAny(addr0, size)) {
                return ControlMemoryResult.failure(Result.OUT_OF_MEMORY);
            }
            base = addr0;
        } else {
            OptionalInt found = findFreeGap(poolBase, poolSize, size);
            if (found.isEmpty()) {
                return ControlMemoryResult.failure(Result.OUT_OF_MEMORY);
            }
            base = found.getAsInt();
        }
        put(new MemoryRegion(base, size, state, permission));
        return new ControlMemoryResult(Result.SUCCESS, base);
    }

    private ControlMemoryResult free(int addr0, int size) {
        MemoryRegion existing = regions.get(addr0);
        if (existing == null || existing.size() != size) {
            // Simplificação documentada: esta HLE só libera uma faixa que corresponda EXATAMENTE
            // a uma alocação viva (base e tamanho), não sub-faixas parciais.
            return ControlMemoryResult.failure(Result.MEMORY_REGION_NOT_FOUND);
        }
        regions.remove(addr0);
        return new ControlMemoryResult(Result.SUCCESS, 0);
    }

    private ControlMemoryResult protect(int addr0, int size, int permission) {
        MemoryRegion existing = regions.get(addr0);
        if (existing == null || existing.size() != size) {
            return ControlMemoryResult.failure(Result.MEMORY_REGION_NOT_FOUND);
        }
        put(new MemoryRegion(addr0, size, existing.state(), permission));
        return new ControlMemoryResult(Result.SUCCESS, addr0);
    }

    private ControlMemoryResult remapAnnotationOnly(int addr0, int size, int state, int permission) {
        if (addr0 == 0 || !isPageAligned(addr0)) {
            return ControlMemoryResult.failure(Result.MISALIGNED_ADDRESS);
        }
        if (size <= 0 || !isPageAligned(size)) {
            return ControlMemoryResult.failure(Result.MISALIGNED_SIZE);
        }
        if (state == MemoryState.FREE) {
            regions.remove(addr0);
        } else {
            put(new MemoryRegion(addr0, size, state, permission));
        }
        return new ControlMemoryResult(Result.SUCCESS, addr0);
    }

    private OptionalInt findFreeGap(int poolBase, int poolSize, int size) {
        int poolEnd = poolBase + poolSize;
        int cursor = poolBase;
        for (MemoryRegion region : regions.subMap(poolBase, poolEnd).values()) {
            if (region.base() - cursor >= size) {
                return OptionalInt.of(cursor);
            }
            cursor = Math.max(cursor, region.end());
        }
        if (poolEnd - cursor >= size) {
            return OptionalInt.of(cursor);
        }
        return OptionalInt.empty();
    }

    private boolean fitsInPool(int addr0, int size, int poolBase, int poolSize) {
        long endExclusive = (long) addr0 + size;
        return Integer.compareUnsigned(addr0, poolBase) >= 0 && endExclusive <= (long) poolBase + poolSize;
    }

    private boolean overlapsAny(int addr0, int size) {
        int endExclusive = addr0 + size;
        Map.Entry<Integer, MemoryRegion> floor = regions.floorEntry(addr0);
        if (floor != null && floor.getValue().end() > addr0) {
            return true;
        }
        Map.Entry<Integer, MemoryRegion> ceiling = regions.ceilingEntry(addr0);
        return ceiling != null && ceiling.getKey() < endExclusive;
    }

    private static int effectivePermission(int requested) {
        return requested == MemoryPermission.DONT_CARE ? MemoryPermission.READ_WRITE : requested;
    }

    private static boolean isPageAligned(int address) {
        return (address & (MemoryMap.PAGE_SIZE - 1)) == 0;
    }

    private void put(MemoryRegion region) {
        regions.put(region.base(), region);
    }
}
