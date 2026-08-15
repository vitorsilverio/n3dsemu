package dev.vitorsilverio.n3dsemu.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Conteúdo da página de config memory (`0x1FF80000`, RFC §3), transcrito de
/// <a href="https://www.3dbrew.org/wiki/Configuration_Memory">3dbrew: Configuration Memory</a>.
///
/// Só os campos que a spec do 3dbrew documenta com offset/valor claro estão preenchidos;
/// campos fora do "Inclui" desta task (data/hora, calibração, região) ficam zerados — a
/// página real do 3DS os tem, mas nada nesta task lê essas SVCs ainda (isso é G2/G3).
public final class ConfigMemory {
    private ConfigMemory() {
    }

    // KERNEL_VERSIONREVISION/MINOR/MAJOR: 3 bytes consecutivos a partir de +0x1 (o byte em
    // +0x0 não é usado por esse campo). Exemplo documentado de Old3DS: 2.0.0.
    private static final int OFF_KERNEL_VERSION_REVISION = 0x1;
    private static final int OFF_KERNEL_VERSION_MINOR = 0x2;
    private static final int OFF_KERNEL_VERSION_MAJOR = 0x3;
    private static final byte KERNEL_VERSION_MAJOR = 2;
    private static final byte KERNEL_VERSION_MINOR = 0;
    private static final byte KERNEL_VERSION_REVISION = 0;

    /// `UNITINFO`: 0 = retail (não é kit de desenvolvimento).
    private static final int OFF_UNITINFO = 0x15;
    private static final byte UNITINFO_RETAIL = 0;

    /// `APPMEMTYPE`: 0 = configuração padrão retail (região APPLICATION de 64 MiB no Old3DS).
    private static final int OFF_APPMEMTYPE = 0x30;
    private static final int APPMEMTYPE_RETAIL_DEFAULT = 0;

    /// Tamanho total, em bytes, de cada região de memória (retail default, Old3DS).
    private static final int OFF_APPMEMALLOC = 0x40;
    private static final int OFF_SYSMEMALLOC = 0x44;
    private static final int OFF_BASEMEMALLOC = 0x48;
    private static final int APPMEMALLOC_RETAIL_DEFAULT = 64 * 1024 * 1024;
    private static final int SYSMEMALLOC_RETAIL_DEFAULT = 24 * 1024 * 1024;
    private static final int BASEMEMALLOC_RETAIL_DEFAULT = 40 * 1024 * 1024;

    /// Monta os {@link MemoryMap#CONFIG_MEMORY_SIZE} bytes da página, preenchidos com
    /// valores plausíveis de um Old3DS retail rodando o kernel 2.0.0.
    public static ReadOnlyPage create() {
        byte[] page = new byte[MemoryMap.CONFIG_MEMORY_SIZE];
        ByteBuffer buffer = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
        page[OFF_KERNEL_VERSION_REVISION] = KERNEL_VERSION_REVISION;
        page[OFF_KERNEL_VERSION_MINOR] = KERNEL_VERSION_MINOR;
        page[OFF_KERNEL_VERSION_MAJOR] = KERNEL_VERSION_MAJOR;
        page[OFF_UNITINFO] = UNITINFO_RETAIL;
        buffer.putInt(OFF_APPMEMTYPE, APPMEMTYPE_RETAIL_DEFAULT);
        buffer.putInt(OFF_APPMEMALLOC, APPMEMALLOC_RETAIL_DEFAULT);
        buffer.putInt(OFF_SYSMEMALLOC, SYSMEMALLOC_RETAIL_DEFAULT);
        buffer.putInt(OFF_BASEMEMALLOC, BASEMEMALLOC_RETAIL_DEFAULT);
        return new ReadOnlyPage(MemoryMap.CONFIG_MEMORY_BASE, page);
    }
}
