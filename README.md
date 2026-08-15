# n3dsemu

[![CI](https://github.com/vitorsilverio/n3dsemu/actions/workflows/ci.yml/badge.svg)](https://github.com/vitorsilverio/n3dsemu/actions/workflows/ci.yml)

Emulador de **Nintendo 3DS** em Java 25, irmão do [`gbaemu`](../gbaemu) e do
[`ndsemu`](../ndsemu). Reaproveita a biblioteca [`arm-jitter`](../arm-jitter) como runtime ARM
(JIT + interpretador de debug). Decisões de projeto em
`arm-jitter/tasks/trilha-g-3ds/RFC-N3DSEMU.md`.

> Estado: **marco M1** (task G1) — carrega um `.3dsx` homebrew, executa código ARMv6K real do
> ARM11 (preset `ArmArchitecture.ARM11_MPCORE`) e chega à primeira `svc` do kernel Horizon,
> com trace legível e nomes reais de SVC. Kernel em HLE (sem MMU, sem LLE do Horizon — RFC D2);
> **nenhuma SVC tem implementação de verdade ainda**, isso é a G2.

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

Nenhum serviço IPC (`srv:`/`APT`/`hid`/`fs`), nenhum gráfico (Vulkan é a G4), segundo núcleo,
ARM9, MMU, áudio, ROMs comerciais (`.cia`/`.3ds`) — ver a RFC §5 para a lista completa.

## Build

Requer **JBR 25** (JDK do IntelliJ). A `arm-jitter` resolve do Maven Central
(`dev.vitorsilverio:arm-jitter:1.0.0`):

```sh
mvn test
```

## Uso

```sh
n3dsemu [--interp|--check] [--slices=N] [--trace-svc] <arquivo.3dsx>
```

Headless (sem gráfico — G1 não depende de LWJGL/Vulkan). Roda em fatias, capturando cada
`svc` não implementada e seguindo em frente (o PC já avançou); ao esgotar `--slices` (ou
parar de progredir por outro motivo), imprime o trace das últimas SVCs observadas e sai com
código 3.

## Layout

```
src/main/java/dev/vitorsilverio/n3dsemu/
  loader/    Loader3dsx (.3dsx + relocação)
  memory/    mapa de endereçamento (RFC §3)
  core/      N3dsCp15 (TLS c13)
  kernel/    SvcTable, nomes de SVC do Horizon
  N3dsMachine.java, Main.java
testdata/    corpus .3dsx/.elf compilado do devkitPro (ver testdata/README.md)
```

## Licença

BSD 3-Clause — ver [LICENSE](LICENSE).
