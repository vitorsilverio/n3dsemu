package dev.vitorsilverio.n3dsemu;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.n3dsemu.gpu.FrameBufferState;
import dev.vitorsilverio.n3dsemu.gpu.GuestFrameBufferReader;
import dev.vitorsilverio.n3dsemu.gpu.PicaRenderer;
import dev.vitorsilverio.n3dsemu.gpu.RecordingRenderer;
import dev.vitorsilverio.n3dsemu.gpu.Screen;
import dev.vitorsilverio.n3dsemu.gpu.vulkan.VulkanRenderer;
import dev.vitorsilverio.n3dsemu.input.InputScript;
import dev.vitorsilverio.n3dsemu.input.Keys;
import dev.vitorsilverio.n3dsemu.kernel.KernelHaltException;
import dev.vitorsilverio.n3dsemu.kernel.SvcCall;
import dev.vitorsilverio.n3dsemu.kernel.UnsupportedSvcException;
import dev.vitorsilverio.n3dsemu.loader.Bad3dsxException;
import dev.vitorsilverio.n3dsemu.loader.Image3dsx;
import dev.vitorsilverio.n3dsemu.loader.Loader3dsx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.glfw.GLFW.*;

/// CLI do n3dsemu: `n3dsemu [--headless] [--interp|--check] [--slices=N] [--trace-svc]
/// [--report] [--script=<arquivo>] <arquivo.3dsx>`.
///
/// **Modo janela é o default** (RFC-N3DSEMU G4 — Vulkan/LWJGL/GLFW, RFC D4: sem backend de
/// software). `--headless` preserva o comportamento da G3: nenhuma janela, executa
/// {@code --slices} fatias ({@link N3dsMachine#RUN_SLICE_BLOCKS} blocos cada) e sai — é o modo
/// usado pelos testes automatizados e pelo CI (RFC/task: "o runner do GitHub não tem GPU/driver
/// Vulkan").
///
/// Nenhuma `svc` é implementada de verdade nesta task (RFC D2, G2 em diante trata disso
/// separadamente) — {@link SvcTable#dispatcher} já deixou o PC avançado para DEPOIS da instrução
/// `svc` antes de lançar {@link UnsupportedSvcException} (mesma convenção de
/// `IrSystemExecutor#executeSwi` do arm-jitter), então capturar a exceção e simplesmente
/// continuar a próxima fatia deixa a CPU seguir em frente como se a SVC tivesse "sumido".
public final class Main {
    private static final int DEFAULT_SLICE_COUNT = 100;

    /// Código de saída ao esgotar o orçamento de fatias sem nenhuma `svc` implementada — o
    /// resultado esperado do modo `--headless` sem nenhuma SVC crítica pendente.
    private static final int EXIT_NO_SVC_IMPLEMENTED = 3;
    private static final int EXIT_USAGE_ERROR = 2;
    private static final int EXIT_BAD_3DSX = 2;

    /// `svcExitProcess`: saída limpa.
    private static final int EXIT_PROCESS_EXIT = 0;
    /// `svcBreak`: o guest pediu para parar com um diagnóstico — não é "svc não implementada"
    /// (código 3), é um ponto de parada intencional do próprio guest.
    private static final int EXIT_GUEST_BREAK = 4;

    /// Mapeamento fixo de teclado (RFC-N3DSEMU G4 — "sem tela de configuração"; documentado
    /// também no README). Convenção próxima da usada por outros emuladores de 3DS: `Z`/`X` para
    /// `A`/`B` (não as posições físicas do botão, que ficariam ao contrário do teclado QWERTY).
    private static final Map<Integer, Integer> KEY_MAP = Map.ofEntries(
            Map.entry(GLFW_KEY_Z, Keys.KEY_A),
            Map.entry(GLFW_KEY_X, Keys.KEY_B),
            Map.entry(GLFW_KEY_A, Keys.KEY_Y),
            Map.entry(GLFW_KEY_S, Keys.KEY_X),
            Map.entry(GLFW_KEY_Q, Keys.KEY_L),
            Map.entry(GLFW_KEY_W, Keys.KEY_R),
            Map.entry(GLFW_KEY_ENTER, Keys.KEY_START),
            Map.entry(GLFW_KEY_BACKSPACE, Keys.KEY_SELECT),
            Map.entry(GLFW_KEY_UP, Keys.KEY_DUP),
            Map.entry(GLFW_KEY_DOWN, Keys.KEY_DDOWN),
            Map.entry(GLFW_KEY_LEFT, Keys.KEY_DLEFT),
            Map.entry(GLFW_KEY_RIGHT, Keys.KEY_DRIGHT));

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        N3dsMachine.Backend backend = N3dsMachine.Backend.JIT;
        int sliceCount = DEFAULT_SLICE_COUNT;
        boolean traceSvc = false;
        boolean headless = false;
        boolean report = false;
        InputScript inputScript = null;
        int index = 0;
        while (index < args.length && args[index].startsWith("--")) {
            String arg = args[index];
            if (arg.startsWith("--slices=")) {
                sliceCount = Integer.parseInt(arg.substring("--slices=".length()));
            } else if (arg.startsWith("--script=")) {
                inputScript = InputScript.load(Path.of(arg.substring("--script=".length())));
            } else {
                switch (arg) {
                    case "--interp" -> backend = N3dsMachine.Backend.INTERPRETED;
                    case "--check" -> backend = N3dsMachine.Backend.CHECK;
                    case "--trace-svc" -> traceSvc = true;
                    case "--headless" -> headless = true;
                    case "--report" -> {
                        headless = true;
                        report = true;
                    }
                    default -> {
                        usage();
                        return;
                    }
                }
            }
            index++;
        }
        if (index >= args.length) {
            usage();
            return;
        }

        byte[] file = Files.readAllBytes(Path.of(args[index]));
        Image3dsx image;
        try {
            image = new Loader3dsx().load(file);
        } catch (Bad3dsxException e) {
            System.err.println("n3dsemu: " + e.getMessage());
            System.exit(EXIT_BAD_3DSX);
            return;
        }

        if (headless) {
            // RFC-N3DSEMU G5/PR3: sem renderer real (`null`) — o headless/CI não tem GPU/driver
            // Vulkan (RFC D4), a GPU continua sendo interpretada/contada mas nada é desenhado,
            // mesmo comportamento de antes desta PR.
            RecordingRenderer recorder = report ? new RecordingRenderer() : null;
            N3dsMachine machine = N3dsMachine.create(image, backend, System.out, traceSvc, inputScript, recorder);
            runHeadless(machine, sliceCount, recorder);
        } else {
            runWindowed(image, backend, traceSvc, inputScript);
        }
    }

    /// Modo padrão (RFC-N3DSEMU G4): janela GLFW + apresentação Vulkan. Laço de emulação e de
    /// render na MESMA thread (RFC/task: "simples e determinístico") — roda até o guest
    /// encerrar/travar ou a janela fechar. O `VulkanRenderer` é criado ANTES de `N3dsMachine`
    /// (RFC-N3DSEMU G5/PR3) porque `gsp::Gpu` agora recebe o `PicaRenderer` real no construtor —
    /// é isto que faz uma lista de comandos GX real (`GX_ProcessCommandList`) desenhar de verdade
    /// na janela, e não só aplicar/contar registradores (ver Javadoc de `GspGpuService`).
    private static void runWindowed(Image3dsx image, N3dsMachine.Backend backend, boolean traceSvc,
                                     InputScript inputScript) {
        VulkanRenderer renderer = new VulkanRenderer();
        N3dsMachine machine = N3dsMachine.create(image, backend, System.out, traceSvc, inputScript, renderer);
        AtomicBoolean vblank = new AtomicBoolean(false);
        machine.setVBlankListener(() -> vblank.set(true));
        glfwSetKeyCallback(renderer.windowHandle(), (window, key, scancode, action, mods) -> {
            Integer mask = KEY_MAP.get(key);
            if (mask == null) {
                return;
            }
            if (action == GLFW_PRESS) {
                machine.pressButtons(mask);
            } else if (action == GLFW_RELEASE) {
                machine.releaseButtons(mask);
            }
        });
        installTouchCallbacks(renderer, machine);

        try {
            while (!renderer.shouldClose()) {
                renderer.pollEvents();
                try {
                    machine.runSlice();
                } catch (UnsupportedSvcException e) {
                    // Ver Javadoc da classe: PC já avançou, seguir em frente é o comportamento
                    // esperado para toda svc ainda não implementada.
                } catch (KernelHaltException e) {
                    printTrace(machine.svcTable().recentCalls());
                    System.out.println("n3dsemu: " + e.getMessage());
                    break;
                } catch (RuntimeException e) {
                    printTrace(machine.svcTable().recentCalls());
                    System.err.println("n3dsemu: parou de progredir após a última svc acima ("
                            + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
                    break;
                }
                if (vblank.compareAndSet(true, false)) {
                    presentFrame(machine, renderer);
                }
            }
        } finally {
            renderer.close();
        }
    }

    /// Lê o buffer ativo de cada tela (se já configurado — `RFC/task`: pode não estar logo após
    /// o boot, antes do primeiro `gfxSwapBuffers`) e repassa para o {@link PicaRenderer}.
    private static void presentFrame(N3dsMachine machine, PicaRenderer renderer) {
        AddressSpace memory = machine.memory();
        FrameBufferState state = machine.frameBufferState();
        for (Screen screen : Screen.values()) {
            FrameBufferState.Buffer buffer = state.active(screen);
            if (buffer != null) {
                byte[] raw = GuestFrameBufferReader.read(memory, screen, buffer);
                renderer.presentScreen(screen, raw, buffer.format(), buffer.stride());
            }
        }
        renderer.endFrame();
    }

    /// Toque pelo mouse na área da tela inferior (RFC/task) — coordenadas da janela convertidas
    /// proporcionalmente para o espaço lógico `320×240` da tela de baixo, usando o tamanho ATUAL
    /// da janela (mesma proporção que {@code VulkanRenderer} usa para posicionar o quad — metade
    /// inferior da janela, centralizada horizontalmente).
    private static void installTouchCallbacks(VulkanRenderer renderer, N3dsMachine machine) {
        long window = renderer.windowHandle();
        double[] lastCursor = new double[2];
        glfwSetCursorPosCallback(window, (w, x, y) -> {
            lastCursor[0] = x;
            lastCursor[1] = y;
            if (glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS) {
                applyTouch(window, x, y, machine);
            }
        });
        glfwSetMouseButtonCallback(window, (w, button, action, mods) -> {
            if (button != GLFW_MOUSE_BUTTON_LEFT) {
                return;
            }
            if (action == GLFW_PRESS) {
                applyTouch(window, lastCursor[0], lastCursor[1], machine);
            } else if (action == GLFW_RELEASE) {
                machine.setTouch(0, 0);
            }
        });
    }

    private static void applyTouch(long window, double x, double y, N3dsMachine machine) {
        int[] widthBox = new int[1];
        int[] heightBox = new int[1];
        glfwGetWindowSize(window, widthBox, heightBox);
        double windowWidth = widthBox[0];
        double windowHeight = heightBox[0];
        double bottomTop = windowHeight * (double) Screen.TOP.rows() / (Screen.TOP.rows() + Screen.BOTTOM.rows());
        double bottomWidthPx = windowWidth * Screen.BOTTOM.columns() / Screen.TOP.columns();
        double bottomLeft = (windowWidth - bottomWidthPx) / 2;
        int touchX = (int) Math.round((x - bottomLeft) / bottomWidthPx * Screen.BOTTOM.columns());
        int touchY = (int) Math.round((y - bottomTop) / (windowHeight - bottomTop) * Screen.BOTTOM.rows());
        touchX = Math.max(0, Math.min(Screen.BOTTOM.columns() - 1, touchX));
        touchY = Math.max(0, Math.min(Screen.BOTTOM.rows() - 1, touchY));
        machine.setTouch(touchX, touchY);
    }

    /// Modo `--headless` (comportamento da G3, preservado — usado por CI/testes automatizados).
    /// Modo `--report`: como `--headless`, mas com um {@link RecordingRenderer} no lugar da GPU —
    /// ao final imprime um resumo do que a GPU emulada produziu (quantos desenhos, quantos
    /// vértices, cor de fundo, texturas ligadas). É como o levantamento dos exemplos de
    /// `graphics/gpu` é feito sem GPU nem olho humano (RFC/task G5).
    private static void printReport(RecordingRenderer recorder) {
        if (recorder != null) {
            System.out.println("n3dsemu-report: " + recorder.report());
        }
    }

    private static void runHeadless(N3dsMachine machine, int sliceCount, RecordingRenderer recorder) {
        for (int i = 0; i < sliceCount; i++) {
            try {
                machine.runSlice();
            } catch (UnsupportedSvcException e) {
                // Ver Javadoc da classe: PC já avançou, seguir para a próxima fatia deixa a
                // execução continuar em vez de travar na primeira svc.
            } catch (KernelHaltException e) {
                printTrace(machine.svcTable().recentCalls());
                int exitCode = switch (e.reason()) {
                    case PROCESS_EXIT -> {
                        System.out.println("n3dsemu: " + e.getMessage() + " — saindo com sucesso.");
                        yield EXIT_PROCESS_EXIT;
                    }
                    case GUEST_BREAK -> {
                        System.err.println("n3dsemu: " + e.getMessage());
                        yield EXIT_GUEST_BREAK;
                    }
                };
                System.exit(exitCode);
                return;
            } catch (RuntimeException e) {
                // Nenhuma svc é implementada de verdade (RFC D2): "devolver sucesso e não
                // fazer nada" faz o guest seguir por um caminho que o resto do host não
                // precisa suportar de verdade — ex.: um crt0 configurando um modo de VFP que
                // o arm-jitter ainda não implementa (achado real, fora do escopo desta
                // task). Trata isso como o mesmo fim de linha que esgotar as fatias: imprime
                // o que foi observado até aqui e sai com o mesmo código — não é um crash do
                // `n3dsemu`, é o guest indo além do que o esqueleto HLE consegue sustentar.
                printTrace(machine.svcTable().recentCalls());
                printReport(recorder);
                System.err.println("n3dsemu: parou de progredir após a última svc acima ("
                        + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
                System.exit(EXIT_NO_SVC_IMPLEMENTED);
                return;
            }
        }
        printTrace(machine.svcTable().recentCalls());
        printReport(recorder);
        System.exit(EXIT_NO_SVC_IMPLEMENTED);
    }

    private static void printTrace(List<SvcCall> recentCalls) {
        System.out.println("n3dsemu: orçamento de fatias esgotado — trace das últimas "
                + recentCalls.size() + " svc(s) observada(s):");
        for (SvcCall call : recentCalls) {
            System.out.println(call.format());
        }
    }

    private static void usage() {
        System.err.println("uso: n3dsemu [--headless] [--interp|--check] [--slices=N] [--trace-svc] "
                + "[--report] [--script=<arquivo>] <arquivo.3dsx>");
        System.exit(EXIT_USAGE_ERROR);
    }
}
