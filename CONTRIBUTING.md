# Como contribuir

Este é um projeto pessoal, mas issues e pull requests são bem-vindos.

## Antes de abrir um PR

- Abra uma [issue](https://github.com/vitorsilverio/n3dsemu/issues) descrevendo o
  problema/ideia primeiro — evita trabalho duplicado ou um PR que não se encaixa na
  direção do projeto.
- Compile e teste com **JBR 25** (a JDK do IntelliJ), não a JDK do sistema:

  ```bash
  mvn test
  ```

- Toda mudança de comportamento vem com teste automatizado cobrindo o caso novo.
- A CPU ARM11 (ARMv6K) vem do [`arm-jitter`](https://github.com/vitorsilverio/arm-jitter)
  — se sua mudança exigir uma feature nova da CPU em si, ela provavelmente pertence lá.
- Decisões de projeto (escopo, prioridades) estão em
  `arm-jitter/tasks/trilha-g-3ds/RFC-N3DSEMU.md` — vale ler antes de propor mudanças
  arquiteturais grandes.
- Mantenha o estilo do código existente; não introduza dependências novas sem discutir
  antes na issue.

## Dúvidas

Abra uma issue ou veja a seção de contato no [README](README.md).
