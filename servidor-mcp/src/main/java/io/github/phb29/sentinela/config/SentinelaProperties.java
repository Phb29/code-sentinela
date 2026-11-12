package io.github.phb29.sentinela.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurações do Code Sentinela, lidas do application.yml ou de variáveis de ambiente
 * (por exemplo SENTINELA_SANDBOX_TEMPO_LIMITE=10m).
 *
 * @param githubApi       endereço da API do GitHub (muda só no GitHub Enterprise)
 * @param githubToken     token do GitHub; se vazio, o cliente tenta o comando "gh auth token"
 * @param sandbox         limites do container que roda os testes
 * @param maxLinhasPatch  corta patches gigantes para não estourar o contexto do modelo
 */
@ConfigurationProperties(prefix = "sentinela")
public record SentinelaProperties(
        String githubApi,
        String githubToken,
        Sandbox sandbox,
        int maxLinhasPatch) {

    public SentinelaProperties {
        if (githubApi == null || githubApi.isBlank()) githubApi = "https://api.github.com";
        if (sandbox == null) sandbox = new Sandbox(null, null, null, null, null);
        if (maxLinhasPatch <= 0) maxLinhasPatch = 800;
    }

    /**
     * @param imagemMaven  imagem Docker usada para rodar "mvn test"
     * @param memoria      limite de memória do container (formato do Docker: 2g, 512m)
     * @param cpus         limite de CPUs do container
     * @param tempoLimite  tempo máximo de cada etapa; passou disso, o container é morto
     * @param volumeCache  volume Docker que guarda as dependências baixadas entre execuções
     */
    public record Sandbox(String imagemMaven, String memoria, String cpus, Duration tempoLimite, String volumeCache) {
        public Sandbox {
            if (imagemMaven == null || imagemMaven.isBlank()) imagemMaven = "maven:3.9.11-eclipse-temurin-25";
            if (memoria == null || memoria.isBlank()) memoria = "2g";
            if (cpus == null || cpus.isBlank()) cpus = "2";
            if (tempoLimite == null) tempoLimite = Duration.ofMinutes(5);
            if (volumeCache == null || volumeCache.isBlank()) volumeCache = "sentinela-m2";
        }
    }
}
