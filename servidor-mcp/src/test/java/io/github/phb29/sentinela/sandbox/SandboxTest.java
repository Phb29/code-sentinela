package io.github.phb29.sentinela.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.phb29.sentinela.config.SentinelaProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxTest {

    final SandboxDeTestes sandbox = new SandboxDeTestes(new ExecutorDeProcesso(), new SentinelaProperties(null, null, null, 0));

    @Test
    void etapaDeTestesRodaSemRedeEComLimites() {
        List<String> cmd = sandbox.docker("x", Path.of("/tmp/pr"), false, "mvn", "-o", "test");
        assertThat(String.join(" ", cmd))
                .contains("--network none", "--memory 2g", "--cpus 2", "--pids-limit 512", "--cap-drop ALL --cap-add DAC_OVERRIDE",
                        "--security-opt no-new-privileges", "--rm");
        assertThat(cmd).endsWith("mvn", "-o", "test");
    }

    @Test
    void preparoBaixaProviderDoSurefireEFiltraVersao() {
        assertThat(SandboxDeTestes.PREPARAR_DEPENDENCIAS)
                .contains("dependency:go-offline", "surefire-junit-platform", "junit-platform-launcher", "tr -cd 'A-Za-z0-9._-'");
    }

    @Test
    void etapaDeDependenciasTemRede() {
        assertThat(String.join(" ", sandbox.docker("x", Path.of("/tmp/pr"), true, "mvn")))
                .contains("--network bridge");
    }

    @Test
    void leRelatorioDoSurefire(@TempDir Path pasta) throws Exception {
        Files.writeString(pasta.resolve("TEST-demo.PedidoServiceTest.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="demo.PedidoServiceTest" tests="3" failures="1" errors="0" skipped="1">
                  <testcase classname="demo.PedidoServiceTest" name="criaPedido"/>
                  <testcase classname="demo.PedidoServiceTest" name="calculaTotal">
                    <failure type="org.opentest4j.AssertionFailedError" message="expected: 30 but was: 29.99"/>
                  </testcase>
                  <testcase classname="demo.PedidoServiceTest" name="ignorado"><skipped/></testcase>
                </testsuite>""");
        RelatorioSurefire.Resumo r = RelatorioSurefire.ler(pasta);
        assertThat(r.testes()).isEqualTo(3);
        assertThat(r.falhas()).isEqualTo(1);
        assertThat(r.passou()).isFalse();
        assertThat(r.detalhes()).singleElement().satisfies(f -> {
            assertThat(f.metodo()).isEqualTo("calculaTotal");
            assertThat(f.mensagem()).contains("29.99");
        });
    }

    @Test
    void xmlComEntidadeExternaNaoLeArquivoDaMaquina(@TempDir Path pasta) throws Exception {
        Path segredo = pasta.resolve("segredo.txt");
        Files.writeString(segredo, "SENHA-SECRETA");
        // Ataque XXE: tenta embutir o conteúdo de um arquivo local na mensagem do teste.
        Files.writeString(pasta.resolve("TEST-ataque.xml"), """
                <?xml version="1.0"?>
                <!DOCTYPE x [<!ENTITY roubo SYSTEM "%s">]>
                <testsuite tests="1" failures="1" errors="0" skipped="0">
                  <testcase classname="a" name="b"><failure message="&roubo;"/></testcase>
                </testsuite>""".formatted(segredo.toUri()));
        RelatorioSurefire.Resumo r = RelatorioSurefire.ler(pasta);
        assertThat(r.detalhes()).noneMatch(f -> f.mensagem().contains("SENHA-SECRETA"));
        assertThat(r.detalhes()).anyMatch(f -> f.tipo().equals("relatorio-ilegivel"));
    }

    @Test
    void pastaSemRelatorioDaZeroTestes(@TempDir Path pasta) {
        assertThat(RelatorioSurefire.ler(pasta.resolve("nao-existe")).testes()).isZero();
    }

    @Test
    void executorRespeitaTempoLimite() {
        var exec = new ExecutorDeProcesso();
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // Subir a JVM leva bem mais que 1 ms: com esse limite, a execução tem de estourar.
        var r = exec.rodar(List.of(javaBin, "-version"), null, java.time.Duration.ofMillis(1), null);
        assertThat(r.estourouTempo()).isTrue();
        assertThat(r.ok()).isFalse();
    }

    @Test
    void finalDoLogPegaAsUltimasLinhas() {
        assertThat(SandboxDeTestes.finalDoLog("a\nb\nc\nd", 2)).isEqualTo("c\nd");
    }
}
