package io.github.phb29.sentinela.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.phb29.sentinela.config.SentinelaProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Teste de ponta a ponta do sandbox: monta um mini-projeto Maven com um teste que passa e outro que falha
 * e roda tudo no Docker de verdade (dependências com rede, testes sem rede).
 *
 * <p>É lento (baixa dependências na primeira vez) e precisa do Docker ligado, por isso só roda com:
 * <pre>SENTINELA_TESTE_DOCKER=1 mvn test -Dtest=SandboxIntegracaoTest</pre>
 */
@EnabledIfEnvironmentVariable(named = "SENTINELA_TESTE_DOCKER", matches = "1")
class SandboxIntegracaoTest {

    @Test
    void rodaOsTestesSemRedeEAchaAFalha(@TempDir Path pasta) throws Exception {
        Files.writeString(pasta.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>teste</groupId><artifactId>mini</artifactId><version>1</version>
                  <properties>
                    <maven.compiler.release>25</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
                      <version>5.13.4</version><scope>test</scope>
                    </dependency>
                  </dependencies>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.4</version>
                  </plugin></plugins></build>
                </project>""");
        Path testes = Files.createDirectories(pasta.resolve("src/test/java/mini"));
        Files.writeString(testes.resolve("ContaTest.java"), """
                package mini;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import org.junit.jupiter.api.Test;
                class ContaTest {
                    @Test void somaInteiros() { assertEquals(3, 1 + 2); }
                    @Test void somaComDouble() { assertEquals(0.3, 0.1 + 0.2); }
                    @Test void semRedeNoSandbox() throws Exception {
                        // Se o sandbox tivesse rede, isto conectaria. Sem rede, lança exceção e o teste passa.
                        try (var s = new java.net.Socket()) {
                            s.connect(new java.net.InetSocketAddress("1.1.1.1", 443), 3000);
                            throw new AssertionError("O sandbox tem acesso à internet!");
                        } catch (java.io.IOException esperado) { }
                    }
                }""");

        var sandbox = new SandboxDeTestes(new ExecutorDeProcesso(), new SentinelaProperties(null, null, null, 0));
        SandboxDeTestes.Resultado r = sandbox.testarPasta(pasta, System.currentTimeMillis());

        assertThat(r.etapa()).as("log do sandbox:%n%s", r.trechoDoLog()).isEqualTo("concluido");
        assertThat(r.passou()).isFalse();
        assertThat(r.resumo().testes()).as("log do sandbox:%n%s", r.trechoDoLog()).isEqualTo(3);
        assertThat(r.resumo().falhas()).isEqualTo(1);
        assertThat(r.resumo().detalhes()).singleElement()
                .satisfies(f -> assertThat(f.metodo()).isEqualTo("somaComDouble"));
    }
}
