# 🛡️ Code Sentinela

Revisor de código **Java/Spring** com IA. O cérebro é o **Claude Code** (pela sua assinatura, sem chave de API). O Code Sentinela entrega as **ferramentas**: um servidor MCP em Java (Spring AI) que lê o pull request, roda os testes num sandbox Docker sem rede, busca as convenções do time (RAG) e publica a revisão com comentários nas linhas do PR.

```
Você ─► Claude Code ─► /revisar-pr dono/repo 12
                         │
                         ▼
          Servidor MCP do Code Sentinela (Java 25 + Spring Boot 4.1 + Spring AI 2.0)
            ler_pull_request · ler_arquivo · buscar_convencoes · listar_convencoes
            rodar_testes (Docker, sem rede) · publicar_revisao (prévia → confirmação → GitHub)
```

Tem também um caminho de **fine-tune**: um gerador de dataset por injeção de bugs, um notebook LoRA para o Google Colab e um avaliador que compara o modelo base, o modelo treinado e o Claude com números.

## Como usar (no seu PC)

Requisitos: Java 25, Maven, Docker Desktop ligado, [Claude Code](https://claude.com/claude-code) e o [GitHub CLI](https://cli.github.com) logado (`gh auth login`).

```powershell
git clone https://github.com/Phb29/code-sentinela.git
cd code-sentinela
mvn -B -f servidor-mcp/pom.xml package -DskipTests   # gera servidor-mcp/target/code-sentinela.jar
claude                                                # abre o Claude Code nesta pasta
```

Dentro do Claude Code:

```
/revisar-pr Phb29/sentinela-demo 1
```

O Claude lê o PR, roda os testes, consulta as convenções e mostra uma **prévia** da revisão. Só publica no GitHub depois do seu "sim".

## Como usar (automático em todo PR)

Copie [exemplos/github-action/code-sentinela.yml](exemplos/github-action/code-sentinela.yml) para `.github/workflows/` do repositório a revisar e siga os 3 passos do cabeçalho do arquivo (`claude setup-token`, o secret `CLAUDE_CODE_OAUTH_TOKEN` e o app do Claude).

## Estrutura

| Pasta | O que tem |
|---|---|
| `servidor-mcp/` | O servidor MCP em Java: ferramentas, cliente do GitHub, leitor de diff, RAG (BM25), sandbox, validador |
| `servidor-mcp/src/main/resources/regras/` | As 25 convenções do time em Markdown (a base do RAG) |
| `.claude/commands/revisar-pr.md` | O comando `/revisar-pr`: o "prompt" do revisor |
| `.mcp.json` | Registra o servidor MCP no Claude Code |
| `exemplos/github-action/` | Workflow para revisar PRs automaticamente |
| `finetune/` | Injetor de bugs, gerador de dataset, avaliador e notebook LoRA |

## Testes

```powershell
mvn -B -f servidor-mcp/pom.xml test                    # 35 testes rápidos
$env:SENTINELA_TESTE_DOCKER="1"; mvn -B -f servidor-mcp/pom.xml test   # + teste de ponta a ponta no Docker
cd finetune; uv run pytest -q                          # 8 testes do injetor
```

## Primeira medição

Modelo base `qwen2.5:3b` (Ollama, só no processador), 12 exemplos do dataset de amostra:

| Acerto da linha (±3) | Regra certa | Ruído em código limpo | Tempo médio |
|---|---|---|---|
| 90% (9/10) | 30% (3/10) | 100% (2/2) | 9,5 s |

É uma amostra pequena e serve como ponto de partida. A comparação completa (base × fine-tune × Claude) é a próxima etapa.

## Segurança

- O código do PR só roda dentro do Docker: **sem rede**, 2 GB de memória, 2 CPUs, 512 processos, `--cap-drop ALL`, `no-new-privileges` e 5 min de tempo limite.
- O leitor dos relatórios XML bloqueia XXE.
- O conteúdo do PR chega ao modelo marcado como **dado, não instrução** (defesa contra prompt injection).
- Publicar exige confirmação (prévia primeiro). Na Action, só rodam PRs do próprio repositório, não de forks.
- Risco que sobra: na etapa de download de dependências (com rede), um `pom.xml` malicioso pode rodar um plugin.
