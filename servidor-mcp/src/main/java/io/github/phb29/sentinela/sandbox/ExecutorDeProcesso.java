package io.github.phb29.sentinela.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Roda um programa externo (git, docker) com tempo limite e guarda a saída.
 *
 * <p>Detalhe importante: a saída é lida numa thread separada enquanto o processo roda.
 * Se ninguém lê, o buffer do sistema operacional enche e o processo trava para sempre.
 */
@Component
public class ExecutorDeProcesso {

    /** Máximo de saída guardada: mais que isso só ocupa memória e contexto do modelo. */
    static final int MAX_SAIDA = 200_000;

    /**
     * @param codigoSaida  0 = sucesso; -1 = estourou o tempo
     * @param saida        stdout e stderr juntos (cortados em MAX_SAIDA)
     * @param estourouTempo true se foi morto por passar do limite
     */
    public record Execucao(int codigoSaida, String saida, boolean estourouTempo, long milissegundos) {
        public boolean ok() {
            return codigoSaida == 0 && !estourouTempo;
        }
    }

    /**
     * @param comando      programa e argumentos, separados (nunca uma string única: evita injeção de comando)
     * @param pasta        pasta onde o comando roda (pode ser null)
     * @param limite       tempo máximo
     * @param aoEstourar   comando extra para rodar se estourar o tempo (ex.: docker kill), pode ser null
     */
    public Execucao rodar(List<String> comando, Path pasta, Duration limite, List<String> aoEstourar) {
        long inicio = System.nanoTime();
        ProcessBuilder pb = new ProcessBuilder(comando).redirectErrorStream(true);
        if (pasta != null) pb.directory(pasta.toFile());
        Process processo;
        try {
            processo = pb.start();
        } catch (IOException e) {
            return new Execucao(127, "Não consegui iniciar " + comando.getFirst() + ": " + e.getMessage(), false, 0);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread leitor = Thread.ofVirtual().start(() -> copiarLimitado(processo.getInputStream(), buffer));
        boolean terminou;
        try {
            terminou = processo.waitFor(limite.toMillis(), TimeUnit.MILLISECONDS);
            if (!terminou) {
                if (aoEstourar != null) rodar(aoEstourar, null, Duration.ofSeconds(20), null);
                processo.destroyForcibly();
                processo.waitFor(10, TimeUnit.SECONDS);
            }
            leitor.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            processo.destroyForcibly();
            terminou = false;
        }
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        String saida;
        synchronized (buffer) {
            saida = buffer.toString(StandardCharsets.UTF_8);
        }
        return new Execucao(terminou ? processo.exitValue() : -1, saida, !terminou, ms);
    }

    private static void copiarLimitado(InputStream in, ByteArrayOutputStream destino) {
        byte[] bloco = new byte[8192];
        try (in) {
            int lidos;
            while ((lidos = in.read(bloco)) != -1) {
                synchronized (destino) {
                    int espaco = MAX_SAIDA - destino.size();
                    if (espaco > 0) destino.write(bloco, 0, Math.min(lidos, espaco));
                }
            }
        } catch (IOException ignorada) {
            // processo morto no meio da leitura: o que já foi lido basta
        }
    }
}
