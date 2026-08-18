package dev.vitorsilverio.n3dsemu.core;

import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;

/// CP15 mínimo do 3DS: só o registrador de TLS por thread (`TPIDRURO`, ARMv6K+ —
/// `MRC p15, 0, Rt, c13, c0, 3`), que o libctru lê para achar o `ThreadLocalStorage` de cada
/// thread (`getThreadLocalStorage`/`__aeabi_read_tp`).
///
/// A ausência deste registrador foi exatamente a causa raiz do bug que travava todo homebrew
/// moderno do ndsemu (memória `ndsemu-calico-boot-fix`): o dispatcher de IRQ do runtime
/// (calico, mesma família do libctru) usa `c13` como scratch e trava esperando por um valor
/// que nunca chega.
///
/// Um valor só, trocado por {@link #setThreadLocalStorage} a cada troca de contexto entre
/// threads (G2 PR2: `dev.vitorsilverio.n3dsemu.kernel.Scheduler#switchTo`).
public final class N3dsCp15 implements CoprocessorBus {
    private static final int CP15 = 15;

    /// `CRn`/`CRm` do bloco "Process, context and thread ID registers" (ARM ARM B4.1.116/117).
    private static final int CRN_THREAD_ID = 13;
    private static final int CRM_THREAD_ID = 0;
    /// `TPIDRURO` — thread ID read-only, acessível em modo usuário só para leitura.
    private static final int OPCODE2_TPIDRURO = 3;

    /// `CRn`/`CRm`/`opcode2` do bloco "Cache, TLB, and branch predictor maintenance operations"
    /// (ARM ARM B4.1.5) usados por barreiras de memória em ARMv6K — o mnemônico dedicado
    /// `dmb`/`dsb`/`isb` só existe a partir do ARMv7; no ARM11 MPCore (ARMv6K, RFC-N3DSEMU B5.2)
    /// o compilador emite a forma `MCR p15, 0, Rt, c7, c10, {4|5}` (achado real desta sessão,
    /// G2.2: confirmado via `objdump` no `.3dsx` real de teste, não por analogia — `srvInit` do
    /// libctru abre com `MCR p15, 0, r0, c7, c10, {5}`, o PRIMEIRO coprocessor op que este HLE
    /// não reconhecia).
    private static final int CRN_CACHE_MAINTENANCE = 7;
    private static final int CRM_BARRIER = 10;
    /// `DSB` — Data Synchronization Barrier (ARM ARM B4.1.5, ARMv6 encoding via CP15).
    private static final int OPCODE2_DSB = 4;
    /// `DMB` — Data Memory Barrier (ARM ARM B4.1.5, ARMv6 encoding via CP15).
    private static final int OPCODE2_DMB = 5;

    private int threadLocalStorage;

    @Override
    public boolean handles(int coprocessor) {
        return coprocessor == CP15;
    }

    @Override
    public boolean handles(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
        return coprocessor == CP15
                && ((crn == CRN_THREAD_ID && crm == CRM_THREAD_ID && opcode2 == OPCODE2_TPIDRURO)
                        || isBarrier(crn, crm, opcode2));
    }

    @Override
    public int read(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
        if (crn == CRN_THREAD_ID && crm == CRM_THREAD_ID && opcode2 == OPCODE2_TPIDRURO) {
            return threadLocalStorage;
        }
        throw unsupported(crn, crm, opcode2);
    }

    @Override
    public void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value) {
        if (isBarrier(crn, crm, opcode2)) {
            // Sem efeito no host (achado real, G2.2): este HLE não reordena acessos à memória
            // do guest nem mantém um cache de instrução separado da RAM que precise de
            // invalidação — DSB/DMB são no-ops seguros aqui. Sem isto, `svcCreateAddressArbiter`
            // (o primeiro SVC de `srvInit`, chamado logo após a barreira) nunca era alcançado de
            // verdade: a MCR não reconhecida virava `ArmException.UNDEFINED`
            // (`AsmRuntimeHelpers#executeCoprocessor`), sem vetor de exceção configurado (RFC
            // D2: sem MMU/LLE) o PC caía no endereço 4 (zero, lido como `andeq r0,r0,r0`) e
            // andava sequencialmente até tropeçar de volta no executável carregado em
            // `0x00100000`, reiniciando `_start` do zero — o "laço" de `svcCreateAddressArbiter`
            // documentado nesta task não era uma SVC repetindo, era o boot inteiro reiniciando.
            return;
        }
        throw unsupported(crn, crm, opcode2); // TPIDRURO é só-leitura em modo usuário.
    }

    private static boolean isBarrier(int crn, int crm, int opcode2) {
        return crn == CRN_CACHE_MAINTENANCE && crm == CRM_BARRIER
                && (opcode2 == OPCODE2_DSB || opcode2 == OPCODE2_DMB);
    }

    /// Define o ponteiro de TLS da thread corrente (chamado pelo host ao criar/trocar de
    /// thread — G2).
    public void setThreadLocalStorage(int address) {
        this.threadLocalStorage = address;
    }

    private static IllegalStateException unsupported(int crn, int crm, int opcode2) {
        return new IllegalStateException(
                "bug: executor não consultou handles fino: crn=c%d crm=c%d opcode2=%d"
                        .formatted(crn, crm, opcode2));
    }
}
