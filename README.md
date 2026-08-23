# n3dsemu

[![CI](https://github.com/vitorsilverio/n3dsemu/actions/workflows/ci.yml/badge.svg)](https://github.com/vitorsilverio/n3dsemu/actions/workflows/ci.yml)

Emulador de **Nintendo 3DS** em Java 25, irmão do [`gbaemu`](../gbaemu) e do
[`ndsemu`](../ndsemu). Reaproveita a biblioteca [`arm-jitter`](../arm-jitter) como runtime ARM
(JIT + interpretador de debug). Decisões de projeto em
`arm-jitter/tasks/trilha-g-3ds/RFC-N3DSEMU.md`.

> Estado: **marco M4** (task G4) — janela GLFW + apresentação Vulkan (LWJGL 3) dos
> framebuffers do guest, kernel Horizon em HLE (`svc`s + IPC + serviços `srv:`/`APT`/`hid`/
> `fs`/`gsp` mínimo, G2/G3), sem interpretar ainda nenhuma lista de comando da PICA200 (isso é
> a G5) — homebrew que desenha direto no framebuffer com a CPU (`consoleInit()` do libctru)
> já aparece na tela.

## Arquitetura

```
   ARM11 (v6K)   PagedAddressSpace (RFC §3)
   ArmCore   --> executável .3dsx @ 0x00100000, heap linear, heap "novo",
   JitRuntime     VRAM, DSP RAM, config memory, shared page, FCRAM
                  fora do mapa conhecido -> LoggingOpenBus
```

Só o núcleo 0 do MPCore é emulado (RFC D1); o `ExclusiveMonitor` compartilhado do arm-jitter
(B5.1) já é instalado desde o início para o segundo núcleo entrar depois sem refactor. Sem
ARM9/`Process9` — os serviços que ele oferece no hardware real (sistema de arquivos,
criptografia, cartucho) são HLE puro em Java, a partir da G3.

## Estado atual

### Núcleo (G1 — marco M1)

- `loader/Loader3dsx`: parser completo do formato `.3DSX` (3dbrew) com aplicação de
  relocação (absoluta e relativa) — algoritmo transcrito do carregador de referência do
  Homebrew Launcher, já que a spec do 3dbrew só documenta o layout dos campos, não a
  semântica de aplicação. Ver Javadoc da classe.
- `memory/N3dsAddressSpace`: mapa de endereçamento da RFC §3 sobre `PagedAddressSpace`
  (executável, heap linear, heap "novo", VRAM, DSP RAM, config memory, shared page, FCRAM);
  regiões fora do mapa caem num `LoggingOpenBus` que loga e devolve `0`.
- `core/N3dsCp15`: CP15 mínimo com `TPIDRURO` (`c13`, TLS por thread) — a ausência desse
  registrador foi a causa raiz de um bug real que travava homebrew moderno no ndsemu, então
  foi implementado desde o início aqui.
- `kernel/SvcTable`: intercepta toda `svc` via `SwiDispatcher` do arm-jitter, loga com nome
  real (tabela extraída empiricamente do `libctru.a` real via `objdump`, não copiada à mão) e
  lança `UnsupportedSvcException` — nenhuma SVC é implementada de verdade nesta task.

**Achado real corrigido** (não é um bug do arm-jitter — decisão de compatibilidade
documentada em `SvcTable`): o decoder ARM compartilhado do arm-jitter interpreta o imediato
de 24 bits de `SVC` na convenção do BIOS GBA/NDS (número nos 8 bits ALTOS). O kernel Horizon
usa a convenção oposta (número direto no campo, confirmado via `objdump` no `libctru.a`
real). `SvcTable` relê a instrução crua da memória do guest para extrair o número correto,
sem tocar no decoder compartilhado (evitaria quebrar gbaemu/ndsemu/armbox).

`n3dsemu testdata/application.3dsx --trace-svc` chega a `0x21 svcCreateAddressArbiter`,
idêntico nos três backends (JIT/`--interp`/`--check`) — o aceite mais importante da task:
prova que o preset `ARM11_MPCORE` executa ARMv6K real de forma consistente.

### Fora de escopo até aqui

Nenhuma lista de comando da PICA200 interpretada (G5 — vertex shader interpretado + TEV→SPIR-V),
segundo núcleo, ARM9, MMU, áudio (DSP, G7+), ROMs comerciais (`.cia`/`.3ds`, G6) — ver a RFC §5
para a lista completa.

## Build

Requer **JBR 25** (JDK do IntelliJ). A `arm-jitter` resolve do Maven Central
(`dev.vitorsilverio:arm-jitter:1.1.0`):

```sh
mvn test
```

## Uso

```sh
n3dsemu [--headless] [--interp|--check] [--slices=N] [--trace-svc] [--script=<arquivo>] <arquivo.3dsx>
```

**Janela é o default** (G4 — sem backend de software, RFC D4): abre uma janela GLFW com as
duas telas do 3DS empilhadas verticalmente (superior 400×240 em cima, inferior 320×240
centralizada embaixo) e roda até a janela fechar ou o guest travar/sair.

`--headless` preserva o comportamento anterior (sem LWJGL/Vulkan, usado pelo CI e pelos
testes automatizados — o runner do GitHub não tem GPU/driver Vulkan): roda em fatias,
capturando cada `svc` não implementada e seguindo em frente (o PC já avançou); ao esgotar
`--slices` (ou parar de progredir por outro motivo), imprime o trace das últimas SVCs
observadas e sai com código 3.

### Controles (mapeamento fixo, sem tela de configuração — G4)

| Tecla | Botão |
|-------|-------|
| `Z` | A |
| `X` | B |
| `A` | Y |
| `S` | X |
| `Q` | L |
| `W` | R |
| `Enter` | START |
| `Backspace` | SELECT |
| Setas | D-Pad |
| Mouse (botão esquerdo, sobre a tela inferior) | Touch screen |

Círculo analógico ainda não mapeado neste marco (nenhum SVC deste corpus de teste depende
dele — ver Aceite da G4).

### macOS

GLFW/Vulkan precisam iniciar na thread principal em macOS — rode a JVM com
`-XstartOnFirstThread`. Não testado nesta máquina (Windows); registrado aqui por precaução
para quando o CI ganhar um runner macOS.

### Vulkan validation layers

`-Dn3dsemu.vulkan.validation=true` liga as validation layers (`VK_LAYER_KHRONOS_validation`)
se o SDK da LunarG estiver instalado — não é requisito de build (`lwjgl-shaderc` já traz o
compilador de shaders embutido).

## Layout

```
src/main/java/dev/vitorsilverio/n3dsemu/
  loader/    Loader3dsx (.3dsx + relocação)
  memory/    mapa de endereçamento (RFC §3)
  core/      N3dsCp15 (TLS c13)
  kernel/    SvcTable, nomes de SVC do Horizon, objetos do kernel (HandleTable, Scheduler...)
  ipc/       codec de comando IPC (IpcRequest/IpcResponse)
  service/   srv:/APT/hid/fs/gsp/cfg/ptm em HLE
  input/     InputState, Keys, InputScript (--script)
  gpu/       PicaRenderer, Screen/PixelFormat, FrameBufferCodec/FrameBufferState (G4)
  gpu/vulkan/  VulkanRenderer (LWJGL 3 + GLFW, G4)
  N3dsMachine.java, Main.java
src/main/resources/shaders/  present.vert/present.frag (compilados via lwjgl-shaderc em runtime)
testdata/    corpus .3dsx/.elf compilado do devkitPro (ver testdata/README.md)
```

## Licença

BSD 3-Clause — ver [LICENSE](LICENSE).
