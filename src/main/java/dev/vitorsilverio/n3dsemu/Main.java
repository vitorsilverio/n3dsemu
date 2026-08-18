package dev.vitorsilverio.n3dsemu;

import dev.vitorsilverio.n3dsemu.input.InputScript;
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

/// CLI headless (marco M1 da RFC-N3DSEMU): `n3dsemu [--interp|--check] [--slices=N]
/// [--trace-svc] <arquivo.3dsx>`.
///
/// Carrega o `.3dsx`, executa em fatias ({@link N3dsMachine#RUN_SLICE_BLOCKS} blocos por
/// fatia, até `--slices` fatias). Nenhuma `svc` é implementada nesta task (RFC D2, G2 em
/// diante) — {@link SvcTable#dispatcher} já deixou o PC avançado para DEPOIS da instrução
/// `svc` antes de lançar {@link UnsupportedSvcException} (mesma convenção de
/// `IrSystemExecutor#executeSwi` do arm-jitter), então capturar a exceção e simplesmente
/// continuar a próxima fatia deixa a CPU seguir em frente como se a SVC tivesse "sumido" —
/// exatamente o que este marco precisa: acumular várias `svc`s reais do arranque do libctru
/// (não travar na primeira) até o orçamento de fatias acabar, quando finalmente imprime o
/// trace acumulado e sai com código 3.
public final class Main {
    private static final int DEFAULT_SLICE_COUNT = 100;

    /// Código de saída ao esgotar o orçamento de fatias sem nenhuma `svc` implementada — o
    /// resultado esperado desta task inteira (nenhuma é implementada), não um erro do
    /// emulador.
    private static final int EXIT_NO_SVC_IMPLEMENTED = 3;
    private static final int EXIT_USAGE_ERROR = 2;
    private static final int EXIT_BAD_3DSX = 2;

    /// `svcExitProcess` (G2 PR1): saída limpa — o critério de aceite do marco M2 desta sessão.
    private static final int EXIT_PROCESS_EXIT = 0;
    /// `svcBreak` (G2 PR1): o guest pediu para parar com um diagnóstico — não é "svc não
    /// implementada" (código 3), é um ponto de parada intencional do próprio guest.
    private static final int EXIT_GUEST_BREAK = 4;

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        N3dsMachine.Backend backend = N3dsMachine.Backend.JIT;
        int sliceCount = DEFAULT_SLICE_COUNT;
        boolean traceSvc = false;
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

        N3dsMachine machine = N3dsMachine.create(image, backend, System.out, traceSvc, inputScript);
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
                System.err.println("n3dsemu: parou de progredir após a última svc acima ("
                        + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
                System.exit(EXIT_NO_SVC_IMPLEMENTED);
                return;
            }
        }
        printTrace(machine.svcTable().recentCalls());
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
        System.err.println("uso: n3dsemu [--interp|--check] [--slices=N] [--trace-svc] [--script=<arquivo>] <arquivo.3dsx>");
        System.exit(EXIT_USAGE_ERROR);
    }
}
