package io.github.phb29.sentinela.ferramentas;

import io.github.phb29.sentinela.github.GitHubCliente;
import io.github.phb29.sentinela.github.PullRequest;
import io.github.phb29.sentinela.sandbox.RelatorioSurefire;
import io.github.phb29.sentinela.sandbox.SandboxDeTestes;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Ferramenta que roda os testes do PR no sandbox e devolve um resumo para o Claude. */
@Component
public class FerramentasDeTestes {

    private final GitHubCliente github;
    private final SandboxDeTestes sandbox;

    public FerramentasDeTestes(GitHubCliente github, SandboxDeTestes sandbox) {
        this.github = github;
        this.sandbox = sandbox;
    }

    @McpTool(name = "rodar_testes", description = """
            Baixa o código do PR e roda "mvn test" num container Docker isolado (sem rede, com limite de
            memória, CPU e tempo). Devolve quantos testes passaram e as falhas. Pode levar alguns minutos
            na primeira vez (download das dependências). Só funciona para projetos Maven.""")
    public String rodarTestes(
            @McpToolParam(description = "Repositório no formato dono/nome") String repo,
            @McpToolParam(description = "Número do pull request") int numero) {
        PullRequest pr;
        try {
            pr = github.lerPullRequest(repo, numero);
        } catch (RuntimeException e) {
            return "ERRO ao ler o PR: " + e.getMessage();
        }
        SandboxDeTestes.Resultado r = sandbox.rodar(pr, github.token());
        StringBuilder sb = new StringBuilder();
        sb.append(r.passou() ? "TESTES PASSARAM" : "TESTES NÃO PASSARAM")
                .append(" (etapa: ").append(r.etapa()).append(", ").append(r.segundos()).append("s)\n");
        RelatorioSurefire.Resumo resumo = r.resumo();
        if (resumo != null) {
            sb.append("Executados: ").append(resumo.testes())
                    .append(" | falhas: ").append(resumo.falhas())
                    .append(" | erros: ").append(resumo.erros())
                    .append(" | ignorados: ").append(resumo.ignorados()).append('\n');
            if (resumo.testes() == 0) {
                sb.append("Nenhum teste foi executado: o projeto não tem testes ou eles não compilaram.\n");
            }
            for (RelatorioSurefire.Falha f : resumo.detalhes()) {
                sb.append("  ✗ ").append(f.classe()).append('.').append(f.metodo())
                        .append(" [").append(f.tipo()).append("] ").append(f.mensagem()).append('\n');
            }
        }
        if (!r.passou() && r.trechoDoLog() != null && !r.trechoDoLog().isBlank()) {
            sb.append("\nFinal do log:\n").append(r.trechoDoLog()).append('\n');
        }
        return sb.toString();
    }
}
