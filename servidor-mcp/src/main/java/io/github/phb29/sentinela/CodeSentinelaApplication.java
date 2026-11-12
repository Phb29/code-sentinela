package io.github.phb29.sentinela;

import io.github.phb29.sentinela.config.SentinelaProperties;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Ponto de entrada do Code Sentinela.
 *
 * <p>Não é um site nem uma API HTTP: é um servidor MCP que conversa com o Claude Code pela
 * entrada e saída padrão (STDIO). O Claude Code inicia este programa, pergunta quais ferramentas
 * existem e chama cada uma quando precisa (ler o PR, rodar os testes, buscar convenções...).
 */
@SpringBootApplication
@EnableConfigurationProperties(SentinelaProperties.class)
public class CodeSentinelaApplication {

    public static void main(String[] args) {
        // O protocolo MCP fala JSON em UTF-8. No Windows, a saída padrão do Java usa outra codificação
        // (cp1252) e os acentos chegariam quebrados ao Claude Code. Por isso fixamos UTF-8 antes de tudo.
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        SpringApplication.run(CodeSentinelaApplication.class, args);
    }
}
