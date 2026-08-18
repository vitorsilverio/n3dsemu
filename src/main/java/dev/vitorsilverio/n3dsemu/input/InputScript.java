package dev.vitorsilverio.n3dsemu.input;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// `--script=<arquivo>`: uma sequência simples `<quadro> <ação>` por linha (RFC-N3DSEMU G3 —
/// "input sem GUI", mesmo padrão dos `*Drive.java`/`*Probe.java` do `ndsemu`). Linhas em branco
/// e começando com `#` são ignoradas. Ações reconhecidas:
///
/// ```
/// 60 press START
/// 10 release START
/// 5  touch 100 120
/// 5  touchrelease
/// 5  circlepad 10 -20
/// ```
///
/// `press`/`release` aceitam o nome do botão (ver {@link Keys#byName}) ou um literal hex/decimal.
public final class InputScript {
    /// Uma ação a aplicar no `frame` indicado (0-based, contado por VBlank simulado — ver
    /// {@link dev.vitorsilverio.n3dsemu.service.GspGpuService}).
    private record Entry(int frame, java.util.function.Consumer<InputState> action) {
    }

    private final List<Entry> entries;
    private int nextIndex;

    private InputScript(List<Entry> entries) {
        this.entries = entries;
    }

    public static InputScript load(Path path) {
        try {
            return parse(Files.readAllLines(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static InputScript parse(List<String> lines) {
        List<Entry> entries = new ArrayList<>();
        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] tokens = line.split("\\s+");
            int frame = Integer.parseInt(tokens[0]);
            String action = tokens[1].toLowerCase(Locale.ROOT);
            entries.add(switch (action) {
                case "press" -> {
                    int mask = Keys.byName(tokens[2]);
                    yield new Entry(frame, state -> state.pressButtons(mask));
                }
                case "release" -> {
                    int mask = Keys.byName(tokens[2]);
                    yield new Entry(frame, state -> state.releaseButtons(mask));
                }
                case "touch" -> {
                    int x = Integer.parseInt(tokens[2]);
                    int y = Integer.parseInt(tokens[3]);
                    yield new Entry(frame, state -> state.setTouch(x, y));
                }
                case "touchrelease" -> new Entry(frame, InputState::releaseTouch);
                case "circlepad" -> {
                    int dx = Integer.parseInt(tokens[2]);
                    int dy = Integer.parseInt(tokens[3]);
                    yield new Entry(frame, state -> state.setCirclePad(dx, dy));
                }
                default -> throw new IllegalArgumentException("ação de script desconhecida: " + action);
            });
        }
        entries.sort((a, b) -> Integer.compare(a.frame(), b.frame()));
        return new InputScript(entries);
    }

    /// Aplica, em ordem, toda ação cujo `frame` seja `<= currentFrame` e ainda não aplicada —
    /// chamado uma vez por VBlank simulado (`GspGpuService`), então cada ação dispara o mais
    /// tardar no mesmo quadro em que foi agendada.
    public void applyDueActions(int currentFrame, InputState state) {
        while (nextIndex < entries.size() && entries.get(nextIndex).frame() <= currentFrame) {
            entries.get(nextIndex).action().accept(state);
            nextIndex++;
        }
    }
}
