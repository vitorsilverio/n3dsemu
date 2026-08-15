package dev.vitorsilverio.n3dsemu.core;

import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;

/// CP15 mínimo do 3DS: só o registrador de TLS por thread (`TPIDRURO`, ARMv6K+ —
/// `MRC p15, 0, Rt, c13, c0, 3`), que o libctru lê para achar o `ThreadLocalStorage` de cada
/// thread (`getThreadLocalStorage`/`__aeabi_read_tp`).
///
/// A ausência deste registrador foi exatamente a causa raiz do bug que travava todo homebrew
/// moderno do ndsemu (memória `ndsemu-calico-boot-fix`): o dispatcher de IRQ do runtime
/// (calico, mesma família do libctru) usa `c13` como scratch e trava esperando por um valor
/// que nunca chega. Implementado desde o início aqui por precaução — mesmo que o G1 ainda não
/// crie threads de verdade (só a principal, até a primeira `svc`).
///
/// **Sem suporte a múltiplas threads ainda**: um valor só, trocado por
/// {@link #setThreadLocalStorage} quando a G2 implementar troca de contexto entre threads.
public final class N3dsCp15 implements CoprocessorBus {
    private static final int CP15 = 15;

    /// `CRn`/`CRm` do bloco "Process, context and thread ID registers" (ARM ARM B4.1.116/117).
    private static final int CRN_THREAD_ID = 13;
    private static final int CRM_THREAD_ID = 0;
    /// `TPIDRURO` — thread ID read-only, acessível em modo usuário só para leitura.
    private static final int OPCODE2_TPIDRURO = 3;

    private int threadLocalStorage;

    @Override
    public boolean handles(int coprocessor) {
        return coprocessor == CP15;
    }

    @Override
    public boolean handles(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
        return coprocessor == CP15 && crn == CRN_THREAD_ID && crm == CRM_THREAD_ID
                && opcode2 == OPCODE2_TPIDRURO;
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
        throw unsupported(crn, crm, opcode2); // TPIDRURO é só-leitura em modo usuário.
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
