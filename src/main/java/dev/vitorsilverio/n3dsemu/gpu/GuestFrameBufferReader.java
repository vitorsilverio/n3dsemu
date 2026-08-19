package dev.vitorsilverio.n3dsemu.gpu;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

/// Lê `screen.columns() * buffer.stride()` bytes crus da memória do guest a partir do endereço
/// de {@link FrameBufferState.Buffer} — a ponte entre a memória do ARM11 e
/// {@link PicaRenderer#presentScreen} (RFC-N3DSEMU G4). {@link AddressSpace} só expõe leitura
/// byte-a-byte (sem bulk read); aceitável nesta task (RFC/task: degrau antes do PICA200, sem
/// meta de performance).
public final class GuestFrameBufferReader {
    private GuestFrameBufferReader() {
    }

    public static byte[] read(AddressSpace memory, Screen screen, FrameBufferState.Buffer buffer) {
        int length = screen.columns() * buffer.stride();
        byte[] raw = new byte[length];
        for (int i = 0; i < length; i++) {
            raw[i] = (byte) memory.read8(buffer.address() + i);
        }
        return raw;
    }
}
