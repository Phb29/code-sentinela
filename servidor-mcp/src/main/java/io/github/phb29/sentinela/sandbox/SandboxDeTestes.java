package io.github.phb29.sentinela.sandbox;

import io.github.phb29.sentinela.config.SentinelaProperties;
import io.github.phb29.sentinela.github.PullRequest;
import io.github.phb29.sentinela.sandbox.ExecutorDeProcesso.Execucao;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Roda os testes do PR num container Docker isolado. O código de um PR é código de terceiros:
 * pode conter qualquer coisa, inclusive algo malicioso. Por isso ele nunca roda direto na máquina.
 *
 * <p>Etapas:
 * <ol>
 *   <li><b>Baixar:</b> git busca o commit do PR (refs/pull/N/head) numa pasta temporária.</li>
 *   <li><b>Dependências (com rede):</b> baixa as bibliotecas para um volume de cache (ver PREPARAR_DEPENDENCIAS).
 *       Esta etapa não roda os testes do PR. Risco que sobra: um pom.xml malicioso pode declarar um plugin
 *       que roda código aqui, com rede. Por isso a Action só revisa PRs de quem tem acesso de escrita.</li>
 *   <li><b>Testes (SEM rede):</b> "mvn -o test" roda com --network none, limite de memória, de CPU,
 *       de processos e de tempo, sem privilégios extras e sem montar nada da máquina além da pasta do PR.
 *       Assim o código do PR não consegue mandar dados para fora nem derrubar a máquina.</li>
 *   <li><b>Ler resultado:</b> os relatórios do Surefire viram um resumo (RelatorioSurefire).</li>
 *   <li><b>Limpar:</b> a pasta temporária é apagada sempre, mesmo se der erro.</li>
 * </ol>
 */
@Component
public class SandboxDeTestes {

    private static final Logger log = LoggerFactory.getLogger(SandboxDeTestes.class);

    /**
     * Etapa com rede: baixa tudo o que a etapa sem rede vai precisar.
     *
     * <p>Só o "dependency:go-offline" não basta: o Surefire baixa na hora de testar o "provider" do JUnit 5
     * (surefire-junit-platform) e o junit-platform-launcher. Sem eles, "mvn -o test" quebra.
     * Por isso lemos as versões no POM efetivo e baixamos as duas com "dependency:get".
     *
     * <p>O POM vem do PR, então a versão lida passa por "tr -cd" (só letras, números, ponto, hífen e
     * sublinhado): um POM malicioso não consegue injetar comando no shell por esse caminho.
     */
    static final String PREPARAR_DEPENDENCIAS = String.join("; ",
            "set -e",
            "mvn -B -q dependency:go-offline",
            "mvn -B -q help:effective-pom -Doutput=/tmp/pom-efetivo.xml",
            "versao() { grep -A1 \"<artifactId>$1</artifactId>\" /tmp/pom-efetivo.xml | grep -oE '<version>[^<]+' | head -1 | cut -c10- | tr -cd 'A-Za-z0-9._-'; }",
            "S=$(versao maven-surefire-plugin)",
            "L=$(versao junit-platform-launcher)",
            // Sem BOM no projeto, a versão do launcher não aparece no POM efetivo: usa a do junit-platform-engine,
            // que sempre anda junto com ele.
            "if [ -z \"$L\" ]; then mvn -B -q dependency:list -DincludeScope=test -DoutputFile=/tmp/deps.txt; "
                    + "L=$(grep -oE 'org.junit.platform:junit-platform-engine:jar:[^:]+' /tmp/deps.txt | head -1 | cut -d: -f4 | tr -cd 'A-Za-z0-9._-'); fi",
            "if [ -n \"$S\" ]; then mvn -B -q dependency:get -Dartifact=org.apache.maven.surefire:surefire-junit-platform:$S; fi",
            "if [ -n \"$L\" ]; then mvn -B -q dependency:get -Dartifact=org.junit.platform:junit-platform-launcher:$L; fi");

    private final ExecutorDeProcesso executor;
    private final SentinelaProperties.Sandbox cfg;

    public SandboxDeTestes(ExecutorDeProcesso executor, SentinelaProperties props) {
        this.executor = executor;
        this.cfg = props.sandbox();
    }

    /**
     * Resultado final, já pronto para o modelo ler.
     *
     * @param etapa onde parou: "docker", "download", "build", "dependencias", "testes" ou "concluido"
     */
    public record Resultado(String etapa, boolean passou, RelatorioSurefire.Resumo resumo, String trechoDoLog, long segundos) {}

    public Resultado rodar(PullRequest pr, String token) {
        long inicio = System.currentTimeMillis();
        Execucao docker = executor.rodar(List.of("docker", "version", "--format", "{{.Server.Version}}"), null, Duration.ofSeconds(20), null);
        if (!docker.ok()) {
            return fim("docker", false, null, "Docker não está rodando nesta máquina. Abra o Docker Desktop e tente de novo.\n" + docker.saida(), inicio);
        }

        Path pasta = null;
        try {
            pasta = Files.createTempDirectory("sentinela-pr-");
            Execucao baixar = baixar(pr, token, pasta);
            if (!baixar.ok()) return fim("download", false, null, baixar.saida(), inicio);
            return testarPasta(pasta, inicio);
        } catch (IOException e) {
            return fim("download", false, null, "Erro de arquivo: " + e.getMessage(), inicio);
        } finally {
            apagar(pasta);
        }
    }

    /**
     * Roda as etapas de dependências e testes numa pasta que já tem o código (usado por rodar e pelo teste
     * de integração). Não apaga a pasta: quem criou é quem apaga.
     */
    public Resultado testarPasta(Path pasta, long inicio) {
        if (!Files.exists(pasta.resolve("pom.xml"))) {
            return fim("build", false, null, "Não achei pom.xml na raiz. Por enquanto só projetos Maven são suportados (Gradle está nos próximos passos).", inicio);
        }
        Path script = null;
        try {
            // O script vai num arquivo montado como somente leitura, e não na linha de comando:
            // no Windows, o Java perde as aspas internas ao montar a linha de comando do docker.exe.
            script = Files.createTempFile("sentinela-preparar-", ".sh");
            Files.writeString(script, PREPARAR_DEPENDENCIAS.replace("; ", "\n") + "\n");
            String nome = "sentinela-" + UUID.randomUUID().toString().substring(0, 8);
            List<String> preparar = docker(nome, pasta, true, "sh", "/sentinela/preparar.sh");
            preparar.add(preparar.indexOf(cfg.imagemMaven()), "-v");
            preparar.add(preparar.indexOf(cfg.imagemMaven()), script.toAbsolutePath() + ":/sentinela/preparar.sh:ro");
            Execucao deps = executor.rodar(preparar, null, cfg.tempoLimite(), List.of("docker", "kill", nome));
            if (!deps.ok()) return fim("dependencias", false, null, deps.saida(), inicio);

            Execucao testes = executor.rodar(docker(nome + "-t", pasta, false, "mvn", "-B", "-o", "test"), null, cfg.tempoLimite(), List.of("docker", "kill", nome + "-t"));
            RelatorioSurefire.Resumo resumo = RelatorioSurefire.ler(pasta.resolve("target/surefire-reports"));
            String log = testes.estourouTempo()
                    ? "Passou do tempo limite de " + cfg.tempoLimite().toMinutes() + " min e o container foi encerrado."
                    : finalDoLog(testes.saida(), 60);
            return fim("concluido", testes.ok() && resumo.passou(), resumo, log, inicio);
        } catch (IOException e) {
            return fim("dependencias", false, null, "Erro ao preparar o script: " + e.getMessage(), inicio);
        } finally {
            if (script != null) script.toFile().delete();
        }
    }

    /**
     * Monta o "docker run" com os limites de segurança.
     *
     * @param comRede só a etapa de dependências tem rede; a de testes roda com --network none
     */
    List<String> docker(String nome, Path pasta, boolean comRede, String... comando) {
        List<String> args = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "--name", nome,
                "--network", comRede ? "bridge" : "none",
                "--memory", cfg.memoria(),
                "--cpus", cfg.cpus(),
                "--pids-limit", "512",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "-v", pasta.toAbsolutePath() + ":/projeto",
                "-v", cfg.volumeCache() + ":/root/.m2",
                "-w", "/projeto",
                cfg.imagemMaven()));
        args.addAll(List.of(comando));
        return args;
    }

    /** Baixa só o commit do PR (sem histórico), direto do repositório base. Funciona também para PR de fork. */
    private Execucao baixar(PullRequest pr, String token, Path pasta) {
        Duration limite = Duration.ofMinutes(2);
        Execucao init = executor.rodar(List.of("git", "init", "-q"), pasta, limite, null);
        if (!init.ok()) return init;
        List<String> fetch = new ArrayList<>(List.of("git"));
        if (token != null && !token.isBlank()) {
            // O git por HTTPS só aceita autenticação Basic (usuário x-access-token + token), não Bearer.
            // O cabeçalho vale só nesta chamada: o token não fica gravado na pasta clonada.
            String basic = Base64.getEncoder().encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
            fetch.addAll(List.of("-c", "http.extraHeader=Authorization: Basic " + basic));
        }
        fetch.addAll(List.of("fetch", "-q", "--depth", "1", pr.urlClone(), "refs/pull/" + pr.numero() + "/head"));
        Execucao buscar = executor.rodar(fetch, pasta, limite, null);
        if (!buscar.ok()) return buscar;
        return executor.rodar(List.of("git", "checkout", "-q", "FETCH_HEAD"), pasta, limite, null);
    }

    private Resultado fim(String etapa, boolean passou, RelatorioSurefire.Resumo resumo, String trecho, long inicio) {
        long segundos = (System.currentTimeMillis() - inicio) / 1000;
        log.info("sandbox etapa={} passou={} tempo={}s", etapa, passou, segundos);
        return new Resultado(etapa, passou, resumo, trecho, segundos);
    }

    static String finalDoLog(String saida, int linhas) {
        String[] todas = saida.split("\\R");
        int inicio = Math.max(0, todas.length - linhas);
        return String.join("\n", List.of(todas).subList(inicio, todas.length));
    }

    private static void apagar(Path pasta) {
        if (pasta == null) return;
        try {
            Files.walkFileTree(pasta, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path arquivo, BasicFileAttributes a) throws IOException {
                    arquivo.toFile().setWritable(true); // arquivos do .git vêm somente leitura no Windows
                    Files.delete(arquivo);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("não consegui apagar {}: {}", pasta, e.getMessage());
        }
    }
}
