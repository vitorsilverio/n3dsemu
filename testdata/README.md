# testdata/ — corpus de homebrew `.3dsx`

Compilados localmente com o toolchain do devkitPro instalado nesta máquina em
2026-08-15:

- **devkitARM** r68 (`arm-none-eabi-gcc` 16.1.0)
- **libctru** 2.7.0
- **3dstools** 1.3.1 (`3dsxtool`, `smdhtool`, `picasso`)

Cada exemplo foi compilado sem modificação a partir de `C:\devkitPro\examples\3ds\`, pelo
shell MSYS2 do próprio devkitPro (`C:\devkitPro\msys2\usr\bin\bash.exe`, necessário para que
`make`/`gcc` resolvam os caminhos consistentemente — ver Armadilha abaixo):

```sh
export DEVKITPRO=/opt/devkitpro
export DEVKITARM=/opt/devkitpro/devkitARM
export PATH="$DEVKITARM/bin:$DEVKITPRO/tools/bin:$PATH"
cd /opt/devkitpro/examples/3ds/<exemplo>
make
```

| Arquivo | Fonte | Alvo (RFC-N3DSEMU §4) |
|---|---|---|
| `application.3dsx`/`.elf` | `templates/application` | M1/M2 — "hello world" mínimo |
| `hello-world.3dsx`/`.elf` | `graphics/printing/hello-world` | M4 — primeira imagem na tela |
| `simple_tri.3dsx`/`.elf` | `graphics/gpu/simple_tri` | M5 — PICA200 (aceite da G5) |
| `read-controls.3dsx`/`.elf` | `input/read-controls` | M3 — laço `aptMainLoop` + input |

O `.elf` é mantido junto do `.3dsx` para depuração simbólica (mesmo padrão do
`nds-examples` do ndsemu — ver memória `ndsemu-homebrew-testing`).

## Armadilha de ambiente (Windows)

Este ambiente tem **duas instalações MSYS2 independentes** (a do Git for Windows, usada pelo
shell padrão desta sessão, e a do próprio devkitPro). Rodar `make`/`arm-none-eabi-gcc.exe`
invocando o `make.exe` do devkitPro a partir do Git Bash falha com
`Cannot create temporary file in C:\WINDOWS\: Permission denied` — as duas MSYS2 têm tabelas
de mount diferentes (`/c/devkitPro` vs `/opt/devkitpro` apontam para o mesmo diretório físico,
mas os executáveis nativos do devkitARM recebem caminhos da tabela errada quando o `make` é o
do devkitPro mas o `cd`/exportação de env vem do Git Bash). A build só funcionou invocando o
`bash.exe` do **próprio** devkitPro (`C:\devkitPro\msys2\usr\bin\bash.exe -lc '...'`) do início
ao fim, com os caminhos `/opt/devkitpro/...` (a raiz que a MSYS2 do devkitPro usa para
`C:\devkitPro`).
