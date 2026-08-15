package dev.vitorsilverio.n3dsemu.kernel;

/// Uma chamada de `svc` observada: número, nome (se conhecido, ver {@link HorizonSvcNames}) e
/// os registradores mais úteis para diagnóstico (RFC-N3DSEMU M1: formato de log legível).
public record SvcCall(int number, String name, int r0, int r1, int pc) {
    /// Formato de uma linha de trace, ex.: `[svc] 0x2D svcConnectToPort r0=0x00000000
    /// r1=0x00108A44 pc=0x0010035C`.
    public String format() {
        String label = name != null ? name : "svc_0x" + Integer.toHexString(number);
        return "[svc] 0x%02X %s r0=0x%08X r1=0x%08X pc=0x%08X".formatted(number, label, r0, r1, pc);
    }
}
