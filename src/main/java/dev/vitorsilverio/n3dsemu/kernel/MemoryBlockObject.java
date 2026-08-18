package dev.vitorsilverio.n3dsemu.kernel;

/// `svcCreateMemoryBlock` (RFC-N3DSEMU G2 PR2 — "necessário para o `gsp` na G3", per a task).
/// Sem MMU (RFC D2), `svcMapMemoryBlock`/`svcUnmapMemoryBlock` são só validação de handle na
/// maioria dos casos — **exceto** para blocos "do servidor" (G3, ver {@link #serverOwned}
/// abaixo), onde o próprio `svcMapMemoryBlock` precisa criar backing de RAM de verdade no
/// endereço que o guest escolher (ver Javadoc de {@link #serverOwned}).
///
/// Mutável (ao contrário do registro imutável da G2 PR2) só no campo `address`/`hostMapped` de
/// um bloco do servidor — o resto (`size`/permissões) nunca muda depois de criado.
public final class MemoryBlockObject implements KernelObject {
    private final int size;
    private final int ownerPermission;
    private final int otherPermission;
    private final boolean serverOwned;
    private int address;
    private boolean hostMapped;

    /// Bloco criado pelo GUEST (`svcCreateMemoryBlock`, G2): `address` já é uma região viva do
    /// próprio heap do processo (tipicamente `linearAlloc`) — já tem RAM de verdade por trás
    /// desde que o heap foi commitado, então já nasce "mapeado" (nenhum trabalho extra em
    /// `svcMapMemoryBlock`).
    public MemoryBlockObject(int address, int size, int ownerPermission, int otherPermission) {
        this.address = address;
        this.size = size;
        this.ownerPermission = ownerPermission;
        this.otherPermission = otherPermission;
        this.serverOwned = false;
        this.hostMapped = true;
    }

    private MemoryBlockObject(int size, int ownerPermission, int otherPermission) {
        this.address = 0;
        this.size = size;
        this.ownerPermission = ownerPermission;
        this.otherPermission = otherPermission;
        this.serverOwned = true;
        this.hostMapped = false;
    }

    /// Memória compartilhada que um SERVIÇO cria e devolve por IPC (`hid:USER`/`gsp::Gpu`, G3)
    /// — o endereço só existe quando o GUEST decide onde mapear, via `svcMapMemoryBlock`; até
    /// lá {@link #address()} não é válido (ver {@link #hostMapped()}). `SvcTable#handleMapMemoryBlock`
    /// é quem detecta `serverOwned() && !hostMapped()` e cria a RAM de backing de verdade no
    /// endereço pedido pelo guest (esta HLE não tem MMU, RFC D2 — sem isso, ler/escrever nesse
    /// endereço cairia no barramento aberto, não numa página real).
    public static MemoryBlockObject serverOwned(int size, int ownerPermission, int otherPermission) {
        return new MemoryBlockObject(size, ownerPermission, otherPermission);
    }

    public int address() {
        return address;
    }

    public int size() {
        return size;
    }

    public int ownerPermission() {
        return ownerPermission;
    }

    public int otherPermission() {
        return otherPermission;
    }

    public boolean serverOwned() {
        return serverOwned;
    }

    public boolean hostMapped() {
        return hostMapped;
    }

    /// Chamado por `SvcTable#handleMapMemoryBlock` depois de mapear RAM de verdade no endereço
    /// escolhido pelo guest — só válido para blocos {@link #serverOwned()}.
    public void bindHostBacking(int mappedAddress) {
        if (!serverOwned) {
            throw new IllegalStateException("bindHostBacking só se aplica a MemoryBlockObject serverOwned");
        }
        this.address = mappedAddress;
        this.hostMapped = true;
    }

    @Override
    public String debugName() {
        return "MemoryBlock@0x" + Integer.toHexString(address) + (serverOwned ? "(server)" : "");
    }
}
