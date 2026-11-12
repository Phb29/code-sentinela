package io.github.phb29.sentinela.ferramentas;

import io.github.phb29.sentinela.diff.Patch;
import io.github.phb29.sentinela.github.ArquivoDoPr;
import io.github.phb29.sentinela.github.GitHubCliente;
import io.github.phb29.sentinela.github.PullRequest;
import io.github.phb29.sentinela.revisao.ComentarioDeRevisao;
import io.github.phb29.sentinela.revisao.Severidade;
import io.github.phb29.sentinela.revisao.ValidadorDeComentarios;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * A única ferramenta que ESCREVE no GitHub. Funciona em dois passos:
 * <ol>
 *   <li>publicar=false (prévia): valida e mostra como a revisão ficaria, sem publicar nada;</li>
 *   <li>publicar=true: valida de novo e publica no PR.</li>
 * </ol>
 * No uso pelo terminal, o comando /revisar-pr mostra a prévia e só publica depois que a pessoa confirma.
 */
@Component
public class FerramentasDeComentario {

    private final GitHubCliente github;

    public FerramentasDeComentario(GitHubCliente github) {
        this.github = github;
    }

    @McpTool(name = "publicar_revisao", description = """
            Valida e publica a revisão no PR: um resumo geral e comentários nas linhas.
            Comentário em linha fora do diff, sem explicação ou repetido é recusado (e o motivo é informado).
            Chame primeiro com publicar=false para ver a prévia. Só chame com publicar=true depois da confirmação.""")
    public String publicarRevisao(
            @McpToolParam(description = "Repositório no formato dono/nome") String repo,
            @McpToolParam(description = "Número do pull request") int numero,
            @McpToolParam(description = "Resumo geral da revisão, em Markdown") String resumo,
            @McpToolParam(description = "Comentários nas linhas") List<ComentarioDeRevisao> comentarios,
            @McpToolParam(description = "false = só prévia; true = publica no GitHub") boolean publicar) {
        try {
            PullRequest pr = github.lerPullRequest(repo, numero);
            Map<String, Patch> patches = new HashMap<>();
            for (ArquivoDoPr a : github.listarArquivos(repo, numero)) {
                patches.put(a.caminho(), a.patchLido());
            }
            ValidadorDeComentarios.Resultado v = ValidadorDeComentarios.validar(comentarios, patches);

            String corpoResumo = montarResumo(resumo, v);
            if (!publicar) {
                return previa(corpoResumo, v);
            }
            String url = github.publicarRevisao(pr, corpoResumo, v.aceitos());
            return "Revisão publicada com " + v.aceitos().size() + " comentário(s): " + url
                    + (v.recusados().isEmpty() ? "" : "\n" + v.recusados().size() + " comentário(s) recusado(s) foram listados no resumo.");
        } catch (RuntimeException e) {
            return "ERRO ao publicar: " + e.getMessage();
        }
    }

    /** Resumo final: o texto do modelo + contagem por severidade + o que foi recusado (para não sumir). */
    static String montarResumo(String resumo, ValidadorDeComentarios.Resultado v) {
        Map<Severidade, Integer> contagem = new HashMap<>();
        v.aceitos().forEach(c -> contagem.merge(Severidade.de(c.severidade()), 1, Integer::sum));
        StringBuilder sb = new StringBuilder("## 🛡️ Code Sentinela\n\n");
        sb.append(resumo == null ? "" : resumo.trim()).append("\n\n");
        sb.append("| Severidade | Comentários |\n|---|---|\n");
        for (Severidade s : Severidade.values()) {
            sb.append("| ").append(s.emoji()).append(' ').append(s.name().toLowerCase()).append(" | ")
                    .append(contagem.getOrDefault(s, 0)).append(" |\n");
        }
        if (!v.recusados().isEmpty()) {
            sb.append("\n<details><summary>Observações fora do diff (").append(v.recusados().size()).append(")</summary>\n\n");
            for (ValidadorDeComentarios.Recusado r : v.recusados()) {
                sb.append("- `").append(r.comentario().caminho()).append(':').append(r.comentario().linha()).append("` ")
                        .append(r.comentario().titulo()).append(" — _").append(r.motivo()).append("_\n");
            }
            sb.append("\n</details>\n");
        }
        sb.append("\n<sub>Revisão automática por IA. Quem aprova o PR é uma pessoa.</sub>");
        return sb.toString();
    }

    private static String previa(String corpoResumo, ValidadorDeComentarios.Resultado v) {
        StringBuilder sb = new StringBuilder("PRÉVIA (nada foi publicado)\n\n");
        sb.append(corpoResumo).append("\n\n--- Comentários que serão publicados (").append(v.aceitos().size()).append(") ---\n");
        for (ComentarioDeRevisao c : v.aceitos()) {
            sb.append("\n").append(c.caminho()).append(':').append(c.linha()).append('\n').append(c.comoMarkdown()).append('\n');
        }
        if (!v.recusados().isEmpty()) {
            sb.append("\n--- Recusados (").append(v.recusados().size()).append(") ---\n");
            v.recusados().forEach(r -> sb.append("- ").append(r.comentario().caminho()).append(':')
                    .append(r.comentario().linha()).append(" → ").append(r.motivo()).append('\n'));
        }
        return sb.toString();
    }
}
