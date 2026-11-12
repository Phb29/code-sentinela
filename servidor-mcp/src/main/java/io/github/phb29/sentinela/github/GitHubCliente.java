package io.github.phb29.sentinela.github;

import io.github.phb29.sentinela.config.SentinelaProperties;
import io.github.phb29.sentinela.revisao.ComentarioDeRevisao;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Conversa com a API REST do GitHub usando o HttpClient do próprio Java (sem biblioteca extra).
 *
 * <p>O token vem de GITHUB_TOKEN. Se não houver, tenta "gh auth token", que funciona quando
 * o GitHub CLI está logado na máquina. Na GitHub Action, o GITHUB_TOKEN é entregue pelo próprio GitHub.
 */
@Component
public class GitHubCliente {

    private static final Logger log = LoggerFactory.getLogger(GitHubCliente.class);
    /** Aceita só "dono/repositorio". Impede que um valor estranho monte uma URL diferente. */
    private static final Pattern REPO_VALIDO = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = JsonMapper.builder().build();
    private final String api;
    private final String tokenConfigurado;
    private volatile String tokenEmCache;

    public GitHubCliente(SentinelaProperties props) {
        this.api = props.githubApi().replaceAll("/+$", "");
        this.tokenConfigurado = props.githubToken();
    }

    /** Lê título, descrição, autor e o commit mais novo do PR. */
    public PullRequest lerPullRequest(String repo, int numero) {
        JsonNode pr = get("/repos/" + validar(repo) + "/pulls/" + numero);
        return new PullRequest(
                repo,
                numero,
                texto(pr, "title"),
                texto(pr, "body"),
                texto(pr.path("user"), "login"),
                texto(pr.path("base"), "ref"),
                texto(pr.path("head"), "sha"),
                texto(pr.path("base").path("repo"), "clone_url"));
    }

    /** Lista os arquivos alterados, com o patch de cada um (até 300 arquivos, em páginas de 100). */
    public List<ArquivoDoPr> listarArquivos(String repo, int numero) {
        List<ArquivoDoPr> arquivos = new ArrayList<>();
        for (int pagina = 1; pagina <= 3; pagina++) {
            JsonNode lista = get("/repos/" + validar(repo) + "/pulls/" + numero + "/files?per_page=100&page=" + pagina);
            for (JsonNode a : lista) {
                arquivos.add(new ArquivoDoPr(
                        texto(a, "filename"),
                        texto(a, "status"),
                        a.path("additions").asInt(),
                        a.path("deletions").asInt(),
                        a.hasNonNull("patch") ? texto(a, "patch") : null));
            }
            if (lista.size() < 100) break;
        }
        return arquivos;
    }

    /** Conteúdo bruto de um arquivo numa versão (ref = commit, branch ou tag). */
    public String lerArquivo(String repo, String caminho, String ref) {
        String caminhoCodificado = URLEncoder.encode(caminho, StandardCharsets.UTF_8).replace("+", "%20").replace("%2F", "/");
        String url = "/repos/" + validar(repo) + "/contents/" + caminhoCodificado + "?ref=" + URLEncoder.encode(ref, StandardCharsets.UTF_8);
        HttpResponse<String> r = enviar(requisicao(url).header("Accept", "application/vnd.github.raw+json").GET().build());
        return r.body();
    }

    /**
     * Publica UMA revisão com resumo + comentários nas linhas.
     * Usa event=COMMENT: comenta sem aprovar nem bloquear o PR (quem decide é uma pessoa).
     *
     * @return o endereço da revisão no GitHub
     */
    public String publicarRevisao(PullRequest pr, String resumo, List<ComentarioDeRevisao> comentarios) {
        List<Map<String, Object>> lista = new ArrayList<>();
        for (ComentarioDeRevisao c : comentarios) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", c.caminho());
            item.put("line", c.linha());
            item.put("side", "RIGHT");
            item.put("body", c.comoMarkdown());
            lista.add(item);
        }
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("commit_id", pr.headSha());
        corpo.put("body", resumo);
        corpo.put("event", "COMMENT");
        corpo.put("comments", lista);

        HttpRequest req = requisicao("/repos/" + validar(pr.repo()) + "/pulls/" + pr.numero() + "/reviews")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo)))
                .build();
        JsonNode resposta = json.readTree(enviar(req).body());
        return texto(resposta, "html_url");
    }

    /** Token para o git clonar repositório privado. Pode ser vazio (repositório público). */
    public String token() {
        if (tokenEmCache != null) return tokenEmCache;
        String t = tokenConfigurado;
        if (t == null || t.isBlank()) t = tokenDoGhCli();
        tokenEmCache = t == null ? "" : t.trim();
        return tokenEmCache;
    }

    // ---------------------------------------------------------------- internos

    static String validar(String repo) {
        if (repo == null || !REPO_VALIDO.matcher(repo).matches()) {
            throw new IllegalArgumentException("Repositório inválido: use o formato dono/repositorio, por exemplo Phb29/sentinela-demo");
        }
        return repo;
    }

    private JsonNode get(String caminho) {
        HttpResponse<String> r = enviar(requisicao(caminho).header("Accept", "application/vnd.github+json").GET().build());
        return json.readTree(r.body());
    }

    private HttpRequest.Builder requisicao(String caminho) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(api + caminho))
                .timeout(Duration.ofSeconds(30))
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "code-sentinela");
        String t = token();
        if (!t.isEmpty()) b.header("Authorization", "Bearer " + t);
        return b;
    }

    private HttpResponse<String> enviar(HttpRequest req) {
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() >= 400) {
                String corpo = r.body() == null ? "" : r.body();
                throw new GitHubException(r.statusCode(), corpo.length() > 300 ? corpo.substring(0, 300) : corpo);
            }
            return r;
        } catch (IOException e) {
            throw new GitHubException(0, "Falha de rede ao falar com o GitHub: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubException(0, "Interrompido");
        }
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode v = no.path(campo);
        return v.isMissingNode() || v.isNull() ? "" : v.asString();
    }

    private static String tokenDoGhCli() {
        try {
            Process p = new ProcessBuilder("gh", "auth", "token").redirectErrorStream(false).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.exitValue() == 0 ? saida : null;
        } catch (IOException e) {
            log.info("GitHub CLI (gh) não encontrado; seguindo sem token");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Erro vindo do GitHub, com o código HTTP (401 = token, 404 = não existe ou sem acesso, 422 = linha inválida). */
    public static class GitHubException extends RuntimeException {
        private final int status;

        public GitHubException(int status, String mensagem) {
            super(status == 0 ? mensagem : "GitHub respondeu " + status + ": " + mensagem);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
