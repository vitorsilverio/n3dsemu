# PICA200 → Vulkan: o que o n3dsemu aprendeu sobre a GPU do 3DS

Documento **só de gráficos**. Nada aqui depende de ARM, de emulação de CPU ou do `arm-jitter` — é o
mapa da GPU do 3DS e das armadilhas que custaram sessões inteiras de investigação. Se um dia o
subsistema gráfico virar um módulo/repo próprio, este arquivo é a fronteira.

Fontes de verdade usadas (nesta ordem de confiança): o **código real** do `libctru`
(`source/gpu/gx.c`, `source/gpu/gxqueue.c`, `source/services/gspgpu.c`, `source/os.c`) e do
`citro3d` (`source/renderqueue.c`, `source/base.c`); o **Citra** (`video_core/pica/regs_*.h`); e só
então a wiki [3dbrew](https://www.3dbrew.org/wiki/GPU/Internal_Registers). Onde a wiki e o código
divergiram, o código venceu — e mais de uma vez a divergência era a causa de um bug.

---

## 1. Como um quadro chega até a GPU

O aplicativo nunca fala com a GPU diretamente. O caminho completo é:

```
app (citro3d)
  └─ C3D_FrameBegin(C3D_FRAME_SYNCDRAW)   espera o vsync
  └─ C3D_RenderTargetClear                 → GX_MemoryFill   (limpa color+depth buffer)
  └─ C3D_FrameDrawOn + desenho             → monta a lista de comandos PICA200
  └─ C3D_FrameEnd                          → GX_ProcessCommandList
                                           → GX_DisplayTransfer (color buffer → framebuffer)
       ↓
  fila GX na memória compartilhada do gsp   (gspSubmitGxCommand)
       ↓
  GSPGPU_TriggerCmdReqQueue (IPC)           ← só quando a fila estava VAZIA
       ↓
  GSP processa, sinaliza interrupções na fila de interrupção da memória compartilhada
       ↓
  thread de relay do libctru → LightEvent por tipo → o app acorda
```

Três estruturas distintas vivem na memória compartilhada do `gsp`, e confundi-las é fácil:

| Offset (por cliente) | O quê |
|---|---|
| `0x00` | fila de **interrupções** (0x40 bytes) |
| `0x200` / `0x240` | `FrameBufferUpdate` da tela de cima / de baixo |
| `0x800 + id*0x200` | fila de **comandos GX** |

### 1.1 Fila de comandos GX — `0x800 + clientId*0x200`

Layout real, de `gspSubmitGxCommand`:

```c
u8 commandIndex  = hdr & 0xFF;          // índice do primeiro comando pendente
u8 totalCommands = (hdr >> 8) & 0xFF;   // QUANTIDADE de pendentes, NÃO um índice final
u32* dst = &sharedGspCmdBuf[8*(1 + (commandIndex + totalCommands) % 15)];
```

**Duas armadilhas, ambas já custaram um bug:**

1. **O cabeçalho tem `0x20` bytes, não `8`.** O `8` do código do libctru é índice de *palavras*:
   a primeira entrada começa no byte `0x20`. (`0x20` de cabeçalho + 15 × `0x20` de entrada =
   `0x200`, o bloco por cliente, exato.)
2. **`totalCommands` é uma contagem, e o GSP tem que decrementá-la.** O cliente só chama
   `GSPGPU_TriggerCmdReqQueue()` quando o valor **pós-incremento é 1** — isto é, quando a fila
   estava vazia. Se o emulador não zerar a contagem ao consumir, o cliente nunca mais dispara a IPC
   e tudo congela depois do primeiro quadro.

Cada entrada tem 32 bytes: 1 byte de tipo + 7 argumentos de 32 bits. Os endereços nos argumentos são
**virtuais** (a função `GX_*` só copia os ponteiros do app).

| Tipo | Comando | Evento de conclusão |
|---|---|---|
| 0 | `RequestDma` | `DMA` (6) |
| 1 | `ProcessCommandList` | `P3D` (5) |
| 2 | `MemoryFill` | `PSC0` (0) |
| 3 | `DisplayTransfer` | `PPF` (4) |
| 4 | `TextureCopy` | `PPF` (4) |
| 5 | `FlushCacheRegions` | — (não enfileirável) |

`gxCmdQueueInterrupt` do cliente **ignora** `PSC1`, `VBlank0` e `VBlank1` e conta **uma unidade por
interrupção** das demais. Entregar interrupções a mais ou a menos dessincroniza a fila do cliente.

### 1.2 Fila de interrupções — offset `0x00`

```
0x00  u8   cursor de LEITURA   (do CLIENTE — o kernel NUNCA escreve aqui)
0x01  u8   quantidade
0x0C  ...  lista de interrupções
```

A posição de escrita é **derivada**: `(cursorDeLeitura + quantidade) % capacidade`. Escrever no
cursor de leitura faz o cliente ler a entrada errada — foi a causa raiz de um travamento inteiro.

Tipos: `PSC0=0, PSC1=1, VBlank0=2, VBlank1=3, PPF=4, P3D=5, DMA=6`.

> **Não basta gerar `VBlank0`.** O `citro3d` mantém `frameCounter[2]`, um por VBlank, e
> `C3D_FrameSync` gira em `while (cur[0]==start[0] || cur[1]==start[1])` — **só sai quando os DOIS
> avançam**. Gerando só `PDC0`, `C3D_FrameBegin(C3D_FRAME_SYNCDRAW)` nunca retorna e o app não
> submete um único comando de GPU.

---

## 2. Endereços: virtual × físico

A CPU do guest usa endereços **virtuais**; os registradores internos da PICA200 guardam endereços
**físicos** (o app converte com `osConvertVirtToPhys`, e quase sempre ainda desloca `>> 3`).

| Região | Virtual | Físico |
|---|---|---|
| VRAM | `0x1F000000` (6 MiB) | `0x18000000` |
| Heap linear (antigo) | `0x14000000` (128 MiB) | `0x20000000` |

No hardware é a **mesma memória** vista por dois endereços. Espelhar as páginas no mapa de memória
é mais fiel — e muito mais simples — do que traduzir endereço a endereço em cada consumidor da GPU.
Sem o espelhamento, todo atributo de vértice lido pela GPU sai zerado e o framebuffer da CPU cai no
barramento aberto.

---

## 3. Lista de comandos PICA200

Sequência de pares `(parâmetro, cabeçalho)` — **o parâmetro vem primeiro**.

```
cabeçalho: bits 0-15  índice do registrador de destino
           bits 16-19 máscara de escrita por byte
           bits 20-27 quantidade de palavras extras
           bit  31    modo consecutivo (incrementa o índice a cada extra)
```

Sem modo consecutivo, as palavras extras vão todas para o **mesmo** registrador — é assim que
funcionam os registradores-FIFO (upload de shader, uniforms).

> **Todo comando ocupa um múltiplo de 8 bytes.** Um comando tem `2 + extras` palavras, então uma
> quantidade **ímpar** de palavras extras carrega uma palavra de enchimento no fim. Não pular esse
> enchimento desalinha a lista inteira a partir dali — o sintoma é um "índice de registrador"
> absurdo (`0xE000` e afins) alguns comandos depois.

---

## 4. Vertex shader

### 4.1 Upload por registrador-FIFO

O `.shbin` não é enviado inteiro: a lista de comandos escreve código, *operand descriptors* e
constantes um a um em registradores que se comportam como FIFO.

| Registrador | O quê |
|---|---|
| `0x2B0` | uniforms booleanos |
| `0x2B1`-`0x2B4` | uniforms inteiros |
| `0x2BA` | ponto de entrada — vale `0x7FFF0000 \| offset`, **só os 16 bits baixos importam** |
| `0x2BB`/`0x2BC` | permutação atributo → registrador de entrada |
| `0x2C0` | configuração de uniform float: índice + **bit 31 = modo float32** |
| `0x2C1`-`0x2C8` | dados de uniform float (FIFO) |
| `0x2CB` / `0x2CC`-`0x2D3` | índice / dados do código do programa |
| `0x2D5` / `0x2D6`-`0x2DD` | índice / dados dos *operand descriptors* |

### 4.2 Uniforms float — a ordem é INVERTIDA

Nos **dois** modos, a primeira palavra escrita no FIFO carrega o componente **`w`**:

- **float32** (4 palavras): `w, z, y, x`.
- **float24 empacotado** (3 palavras, 4 valores de 24 bits):
  ```
  w = word0 >> 8
  z = ((word0 & 0xFF) << 16) | (word1 >> 16)
  y = ((word1 & 0xFFFF) << 8) | (word2 >> 24)
  x = word2 & 0xFFFFFF
  ```

Um único quadro exercita os dois: o `picasso` sobe as constantes embutidas do `.shbin` em float24 e
o `citro3d` sobe os uniforms do app em float32. Com a ordem direta, a matriz de projeção sai
rotacionada e o `w` do vértice projetado dá `0` — **todo vértice vira `NaN`**.

O formato float24 em si é 1 bit de sinal + 7 de expoente (*bias* 63) + 16 de mantissa.

### 4.3 Saída: `SH_OUTMAP`

Não existe convenção fixa de "registrador 0 = posição". `GPUREG_SH_OUTMAP_TOTAL` (`0x04F`) diz
quantos registradores de saída o shader usa, e `GPUREG_SH_OUTMAP_O0`-`O6` (`0x050`-`0x056`) trazem
**um byte de semântica por componente**:

```
0-3   POSITION x,y,z,w        8-11  COLOR r,g,b,a
4-7   QUATERNION              12-13 TEXCOORD0 u,v      14-15 TEXCOORD1 u,v
16    TEXCOORD0 w             18-20 VIEW x,y,z         22-23 TEXCOORD2 u,v
31    inválido (descartar)
```

Presumir `o0`=posição / `o1`=cor (a convenção que o `picasso` usa na prática) funciona para casos
simples, mas **não dá acesso às coordenadas de textura**, que podem sair de qualquer registrador.

---

## 5. Atributos de vértice

Registradores `0x200`-`0x22A`: endereço base (físico, `<< 3` implícito no campo), formato de até 12
atributos (tipo `byte`/`ubyte`/`short`/`float` + contagem de componentes), 12 *loaders*, e o
disparo (`0x22E` = `DrawArrays`, `0x22F` = `DrawElements`).

Dois pontos que quebram tudo silenciosamente:

1. **`0x202` bits 28-31 = `max_attribute_index`.** Só os `max_attribute_index + 1` primeiros
   atributos existem. Aplicar a permutação aos 12 slots faz os não usados — todos com nibble `0` —
   sobrescreverem `v0` com zeros.
2. **Atributos FIXOS.** Nem todo atributo vem de um array: `GPUREG_FIXEDATTRIB_INDEX` (`0x232`) +
   `DATA0`-`DATA2` (`0x233`-`0x235`) programam um `vec4` constante para todos os vértices, com o
   **mesmo empacotamento float24 invertido** dos uniforms. O índice `0xF` é o modo de submissão
   imediata de vértices, não um atributo. O valor default de um atributo nunca programado é
   `(0,0,0,1)` — **não** zeros.

Um exemplo tão simples quanto `simple_tri` já usa os dois caminhos: posição por array, cor por
atributo fixo. Sem suporte a fixos, o triângulo sai preto transparente — indistinguível de "não
desenhou nada".

---

## 6. TEV (*Texture Environment*)

6 estágios, registradores em offsets **irregulares**: `0x0C0, 0x0C8, 0x0D0, 0x0D8, 0x0F0, 0x0F8`
(os estágios 4 e 5 pulam o bloco de `UPDATE_BUFFER`/`BUFFER_COLOR`). Cada estágio ocupa:

| Offset | Campo |
|---|---|
| `+0` | 3 fontes de cor (bits 0/4/8) + 3 de alpha (bits 16/20/24) |
| `+1` | operandos: cor (bits 0/4/8, 4 bits) + alpha (bits 16/20/24, 3 bits) |
| `+2` | combinadores: cor (bits 0-3) + alpha (bits 16-19) |
| `+3` | cor constante do estágio (RGBA8) |
| `+4` | escala: cor (bits 0-1) + alpha (bits 16-17), `0`=1× `1`=2× `2`=4× |

**Fontes:** `0` cor primária, `1`/`2` cor de fragmento, `3`-`6` texturas 0-3, `13` buffer
combinado, `14` constante, `15` resultado anterior.

**Operandos** (o item que a spec da G5 marca como armadilha — "ignorá-los faz tudo *quase*
funcionar"): selecionam um canal e/ou o complemento. Cor: `0` rgb, `1` 1-rgb, `2` aaa, `3` 1-aaa,
`4/5` r, `8/9` g, `12/13` b. Alpha: `0` a, `1` 1-a, `2/3` r, `4/5` g, `6/7` b.

**Buffer de cor combinada:** `GPUREG_TEXENV_UPDATE_BUFFER` (`0x0E0`) — bits 8-11 dizem quais dos
**4 primeiros** estágios gravam a cor no buffer, bits 12-15 o mesmo para o alpha. O valor visível a
um estágio é o do fim do estágio anterior; o inicial vem de `GPUREG_TEXENV_BUFFER_COLOR` (`0x0FD`).

O PICA trabalha em ponto fixo de 8 bits: **satura em `[0,1]`** a cada estágio. Sem isso um `Add`
estoura e o `Interpolate` seguinte usa um peso fora da faixa.

**Teste de alpha:** `GPUREG_FRAGOP_ALPHA_TEST` (`0x104`) — bit 0 habilita, bits 4-6 a função, bits
8-15 a referência. Faz parte da **chave do cache de shader**, porque muda o código gerado (um
`discard`), não um estado dinâmico do pipeline.

> Gerar o *fragment shader* a partir da configuração e cacheá-lo é obrigatório; recompilar o mesmo
> shader a cada quadro é o erro clássico. A chave tem que comparar por **valor** — uma configuração
> guardada em arrays compara por identidade e o cache erra sempre.

---

## 7. Color buffer e texturas

### 7.1 Color buffer

`GPUREG_COLORBUFFER_FORMAT` (`0x117`, bits 16-18) e `GPUREG_COLORBUFFER_LOC` (`0x11D`, físico
`>> 3`). Formatos: `0` RGBA8, `1` RGB8, `2` RGB5A1, `3` RGB565, `4` RGBA4.

⚠️ **Esta ordem NÃO é a mesma de `GSPGPU_FramebufferFormat`** (o formato do framebuffer de
apresentação), onde `2` é RGB565 e `3` é RGB5A1. São duas enumerações diferentes com os mesmos
nomes — reaproveitar uma no lugar da outra decodifica a cor errada.

Os componentes ficam na memória na ordem **inversa** do nome: lendo a palavra em *little-endian*, o
vermelho está nos bits altos (`0xRRGGBBAA`).

### 7.2 Unidades de textura

3 unidades, com blocos de registradores em offsets irregulares:

| | dimensão | endereço | formato |
|---|---|---|---|
| unidade 0 | `0x082` | `0x085` | `0x08E` |
| unidade 1 | `0x092` | `0x095` | `0x096` |
| unidade 2 | `0x09A` | `0x09D` | `0x09E` |

`GPUREG_TEXUNIT_CONFIG` (`0x080`) bits 0/1/2 habilitam cada uma. A dimensão traz **altura nos 16
bits baixos e largura nos altos**. O endereço é físico `<< 3`.

Formatos (código → nome): `0` RGBA8, `1` RGB8, `2` RGBA5551, `3` RGB565, `4` RGBA4, `5` IA8,
`6` RG8, `7` I8, `8` A8, `9` IA4, `10` I4, `11` A4, `12` ETC1, `13` ETC1A4.

> **Armadilha grande:** as texturas são guardadas em blocos **8×8 em ordem de curva de Morton**, não
> linearmente. Decodificar como se fosse linear produz uma imagem "embaralhada em quadradinhos" —
> sintoma característico, vale reconhecer de imediato.

---

## 8. Método de investigação que funcionou

Registrado porque economizou várias sessões:

1. **Avance até a próxima parada real** com instrumentação temporária, em vez de teorizar a causa
   final a partir do sintoma visível. Um pipeline gráfico longo costuma ter vários bloqueios
   **independentes em série**: cada correção é real e destrava exatamente um passo, o que dá a falsa
   impressão de "andar e voltar".
2. **Confira o código-fonte real** do `libctru`/`citro3d`/do próprio exemplo antes de codificar. A
   wiki descreve o hardware; o bug quase sempre está na diferença entre o hardware e o que a
   biblioteca do lado do cliente **assume**.
3. **Ligue as *validation layers* do Vulkan cedo.** Um `VkWriteDescriptorSet` sem `descriptorCount`
   vira um no-op silencioso, sem erro de API, e a tela fica preta — as *validation layers* acusam,
   e só elas.
4. **Um relatório sem GPU vale muito.** Rodar o corpus de exemplos em modo texto contando desenhos,
   vértices, cor de fundo e texturas ligadas separa "não desenha" de "desenha errado" sem depender
   de olho humano.
