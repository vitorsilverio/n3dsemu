# Regras do projeto n3dsemu

Emulador de **Nintendo 3DS** escrito em Java 25, irmao do `gbaemu`/`ndsemu`. Consome a
biblioteca `arm-jitter` (runtime ARM JIT/debug) como dependencia Maven. Decisoes de projeto
estao em `tasks/trilha-g-3ds/RFC-N3DSEMU.md` do repo `arm-jitter` — leia-a antes de mexer em
qualquer coisa aqui, as decisoes D1-D8 la nao devem ser reabertas sem o usuario.

## Hardware alvo (resumo)

- **ARM11 MPCore** (aplicacao) — **ARMv6K + VFPv2, sem Thumb-2**
  (`ArmArchitecture.ARM11_MPCORE`). So o nucleo 0 e emulado por enquanto (RFC D1); o
  `ExclusiveMonitor` compartilhado ja e usado desde o inicio para o segundo nucleo entrar
  depois sem refactor.
- **ARM9/`Process9` NAO e emulado** — kernel Horizon em **HLE** (RFC D2): sem MMU, sem LLE.
  As `svc` do guest sao interceptadas pelo `SwiDispatcher` do arm-jitter (mesmo mecanismo do
  gbaemu/ndsemu para a BIOS) e implementadas em Java.
- **CP15 `c13`** guarda o TLS por thread — foi a causa raiz do bug que travava todo homebrew
  moderno no ndsemu (memoria `ndsemu-calico-boot-fix`); implementado desde o inicio aqui.
- Mapa de memoria: ver RFC §3 (FCRAM 128MiB em `0x20000000`, VRAM 6MiB em `0x18000000`,
  config memory em `0x1FF80000`, shared page em `0x1FF81000`, heap linear em `0x08000000`).

## Regras de arquitetura

- **Nao confunda o ARM11 do 3DS com o ARM9 do NDS.** Nada do `ndsemu` se aplica direto,
  apesar do nome parecido — sao consoles diferentes.
- **`.3dsx` nao e ELF.** Formato proprio com relocacao (ver `loader/`), nao reuse
  `Elf32Loader` do armbox.
- **`arm-jitter` e compartilhado** com gbaemu/ndsemu/armbox/virtual-arm-box. Mudancas nele
  devem preservar o comportamento dos outros consumidores.
- Comeca por **`.3dsx` homebrew** (RFC D3); `.cia`/`.3ds` comerciais ficam para a task G6
  quando houver dump de `boot9.bin`.
- **Sem grafico nesta fase** (M1-M3): headless. A partir da G4, Vulkan via LWJGL 3 + janela
  GLFW propria (RFC D4) — nao herda a GUI Swing do gbaemu/ndsemu.

## Convencoes de codigo

- Pacote raiz `dev.vitorsilverio.n3dsemu`: `loader/` (`.3dsx`), `memory/` (mapa de
  endereçamento), `core/` (ArmCore/JitRuntime), `kernel/` (SvcTable, HLE do Horizon).
- Classes `final` por padrao; APIs publicas com Javadoc `///` (markdown, Java 25).
- Constantes arquiteturais (bases de memoria, offsets, numeros de SVC) sempre nomeadas —
  sem numeros magicos.
- Inclua/preserve testes JUnit 5 para qualquer comportamento observavel.

## Build e testes

- Compilar e testar com **JBR 25** (a JDK do IntelliJ), nao o JDK 21 do sistema.
- A `arm-jitter` resolve do **Maven Central** (`dev.vitorsilverio:arm-jitter:1.1.0`), sem
  `mvn install` local.
- O agente esta autorizado a rodar build/testes, sempre com o JBR 25 no `JAVA_HOME`.

## Corpus de teste (`testdata/`)

`.3dsx`+`.elf` de exemplos do devkitPro (`C:\devkitPro\examples\3ds`), compilados localmente
com devkitARM r68 + libctru 2.7.0. Ver `testdata/README.md` para a lista e o comando de
build.
