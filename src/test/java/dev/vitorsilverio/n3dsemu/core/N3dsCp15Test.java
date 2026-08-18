package dev.vitorsilverio.n3dsemu.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Regressão da causa raiz achada na task G2.2 (`tasks/trilha-g-3ds/g2.2-address-arbiter-loop.md`
/// do `arm-jitter`): `srvInit` do libctru abre com `MCR p15, 0, r0, c7, c10, {5}` (`DMB`, ARMv6K —
/// o mnemônico dedicado `dmb` só existe a partir do ARMv7) antes de {@link N3dsCp15} tratar
/// qualquer registrador além de `TPIDRURO` (`c13,c0,3`). Sem isto, o core ARM11 recebia
/// `ArmException.UNDEFINED` (coprocessador não reconhecido, `AsmRuntimeHelpers#executeCoprocessor`
/// do arm-jitter) e — como este HLE não configura vetor de exceção (RFC-N3DSEMU D2: sem MMU/LLE)
/// — o PC caía no endereço 4 (memória zerada, decodificada como `andeq r0,r0,r0`) e andava
/// sequencialmente até tropeçar de volta no executável carregado em `0x00100000`, reiniciando
/// `_start` inteiro — o "laço indefinido de `svcCreateAddressArbiter`" documentado na task não era
/// uma SVC repetindo, era o boot inteiro reiniciando a cada volta. Ver Javadoc de
/// `Application3dsxTest` para o relato completo da investigação.
class N3dsCp15Test {
    /// `CRn`/`CRm`/`opcode2` reais dos dois barramentos vistos no `.3dsx` de teste (confirmado via
    /// `arm-none-eabi-objdump`, não por analogia — ver Javadoc da classe).
    private static final int CRN_CACHE_MAINTENANCE = 7;
    private static final int CRM_BARRIER = 10;
    private static final int OPCODE2_DSB = 4;
    private static final int OPCODE2_DMB = 5;
    private static final int OPCODE1_ANY = 0;

    @Test
    void dmbEhAtendidoENaoLancaAoEscrever() {
        N3dsCp15 cp15 = new N3dsCp15();
        assertTrue(cp15.handles(15, OPCODE1_ANY, CRN_CACHE_MAINTENANCE, CRM_BARRIER, OPCODE2_DMB));
        cp15.write(15, OPCODE1_ANY, CRN_CACHE_MAINTENANCE, CRM_BARRIER, OPCODE2_DMB, 0);
    }

    @Test
    void dsbEhAtendidoENaoLancaAoEscrever() {
        N3dsCp15 cp15 = new N3dsCp15();
        assertTrue(cp15.handles(15, OPCODE1_ANY, CRN_CACHE_MAINTENANCE, CRM_BARRIER, OPCODE2_DSB));
        cp15.write(15, OPCODE1_ANY, CRN_CACHE_MAINTENANCE, CRM_BARRIER, OPCODE2_DSB, 0);
    }

    @Test
    void tpidruroContinuaFuncionandoAposAAdicaoDasBarreiras() {
        N3dsCp15 cp15 = new N3dsCp15();
        cp15.setThreadLocalStorage(0x1FF90000);
        assertEquals(0x1FF90000, cp15.read(15, OPCODE1_ANY, 13, 0, 3));
    }

    @Test
    void outroRegistradorDeCr7ContinuaIndefinido() {
        // Regressão de escopo: só DSB/DMB (crm=10) são no-op — outras operações de manutenção de
        // cache/TLB em CRn=7 (ex.: invalidação de i-cache) continuam indefinidas até terem
        // evidência real de uso (mesma disciplina desta task: nada "por analogia").
        N3dsCp15 cp15 = new N3dsCp15();
        assertFalse(cp15.handles(15, OPCODE1_ANY, CRN_CACHE_MAINTENANCE, 5, 4));
    }
}
