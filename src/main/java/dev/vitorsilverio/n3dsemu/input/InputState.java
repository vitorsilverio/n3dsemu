package dev.vitorsilverio.n3dsemu.input;

/// Estado de entrada do host (RFC-N3DSEMU G3 — "injeção de input sem GUI"): botões mantidos
/// pressionados, posição do círculo analógico e posição/estado da tela de toque. Mutável,
/// atualizado pelo host (`N3dsMachine#pressButtons`/`releaseButtons`/`setCirclePad`/`setTouch`,
/// ou pelo leitor de `--script`) e LIDO por {@link dev.vitorsilverio.n3dsemu.service.HidService}
/// a cada pulso de 60&nbsp;Hz para preencher a memória compartilhada do HID — mesma ideia dos
/// `*Drive.java`/`*Probe.java` que o `ndsemu` usa para dirigir jogos sem GUI.
///
/// A máscara de bits é exatamente a convenção `KEY_*` de `libctru/include/3ds/services/hid.h`
/// (`KEY_A=BIT(0)`, `KEY_START=BIT(3)`...) — este estado não reinterpreta os bits, só os
/// mantém.
public final class InputState {
    private int heldButtons;
    private short circlePadX;
    private short circlePadY;
    private short touchX;
    private short touchY;
    private boolean touching;

    public void pressButtons(int mask) {
        heldButtons |= mask;
    }

    public void releaseButtons(int mask) {
        heldButtons &= ~mask;
    }

    public int heldButtons() {
        return heldButtons;
    }

    public void setCirclePad(int dx, int dy) {
        this.circlePadX = (short) dx;
        this.circlePadY = (short) dy;
    }

    public short circlePadX() {
        return circlePadX;
    }

    public short circlePadY() {
        return circlePadY;
    }

    public void setTouch(int x, int y) {
        this.touchX = (short) x;
        this.touchY = (short) y;
        this.touching = true;
    }

    public void releaseTouch() {
        this.touching = false;
    }

    public short touchX() {
        return touchX;
    }

    public short touchY() {
        return touchY;
    }

    public boolean touching() {
        return touching;
    }
}
