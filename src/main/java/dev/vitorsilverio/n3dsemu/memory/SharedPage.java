package dev.vitorsilverio.n3dsemu.memory;

/// Conteúdo da shared page (`0x1FF81000`, RFC §3): relógio, estado do slider 3D, wifi.
///
/// **Zerada nesta task.** O 3dbrew documenta esta página numa página wiki separada da
/// "Configuration Memory" (que cobriu `KERNEL_VERSION`/`APPMEMTYPE`/`APPMEMALLOC`, ver
/// {@link ConfigMemory}) e essa página não pôde ser consultada nesta sessão — preencher com
/// offsets/valores não confirmados violaria a regra de não inventar dado de hardware.
/// `RUNNING_HW` (identifica Old3DS vs New3DS) fica para quando essa spec for revisitada;
/// **o "modelo do console" citado pela task G1 é servido pelo serviço `cfg:u`
/// (`CFG_GetSystemModel`), IPC implementado só na G3 — não é um campo bruto lido daqui.**
/// Nenhum exemplo do corpus de `testdata/` (M1-M3) lê esta página antes da primeira `svc`;
/// zerá-la não bloqueia o aceite desta task.
public final class SharedPage {
    private SharedPage() {
    }

    public static ReadOnlyPage create() {
        return new ReadOnlyPage(MemoryMap.SHARED_PAGE_BASE, new byte[MemoryMap.SHARED_PAGE_SIZE]);
    }
}
