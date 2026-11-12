package io.github.phb29.sentinela.convencoes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * O "R" do RAG (Retrieval-Augmented Generation): guarda as convenções do time e acha as que
 * combinam com um trecho de código, para o Claude revisar com base nelas e citar a regra.
 *
 * <p>Como funciona:
 * <ol>
 *   <li><b>Carregar:</b> lê todos os arquivos regras/*.md de dentro do jar.</li>
 *   <li><b>Dividir (chunking):</b> cada "## CODIGO · Título" vira um trecho separado.
 *       Trecho pequeno e focado = resultado mais preciso.</li>
 *   <li><b>Indexar:</b> quebra cada trecho em palavras (termos) e conta quantas vezes aparecem.</li>
 *   <li><b>Buscar (BM25):</b> dá nota para cada regra conforme os termos da consulta que ela contém.
 *       Termo raro vale mais que termo comum; texto longo não ganha só por ser longo.</li>
 * </ol>
 *
 * <p>Por que BM25 e não embeddings? Código tem palavras exatas ("@Transactional", "Optional.get",
 * "createQuery") que a busca por palavra acerta muito bem, sem precisar de modelo, internet ou GPU.
 * Trocar por embeddings depois (Spring AI VectorStore) não muda quem usa esta classe.
 */
@Component
public class BaseDeConvencoes {

    private static final Pattern CABECALHO = Pattern.compile("^## ([A-Z]+-\\d{2}) · (.+)$", Pattern.MULTILINE);
    private static final Pattern PALAVRA = Pattern.compile("[a-z0-9_@.]+");
    private static final Set<String> IGNORAR = Set.of(
            "a", "o", "e", "de", "do", "da", "em", "no", "na", "um", "uma", "que", "para", "com", "por", "se",
            "os", "as", "dos", "das", "ao", "nao", "ou", "mais", "the", "is", "of", "to", "and", "in",
            "public", "private", "return", "new", "void", "final", "class", "string", "int", "var", "this");
    private static final double K1 = 1.4;
    private static final double B = 0.75;

    private final List<Regra> regras = new ArrayList<>();
    private final List<Map<String, Integer>> frequencias = new ArrayList<>();
    private final Map<String, Double> idf = new HashMap<>();
    private double tamanhoMedio;

    /** Construtor usado pelo Spring: carrega as regras de dentro do jar (src/main/resources/regras). */
    public BaseDeConvencoes() throws IOException {
        this(carregarDoClasspath());
    }

    /** Construtor usado nos testes: recebe as regras prontas. */
    public BaseDeConvencoes(List<Regra> regras) {
        this.regras.addAll(regras);
        indexar();
    }

    /** Separa um arquivo Markdown em regras, uma por cabeçalho "## CODIGO · Título". */
    public static List<Regra> dividir(String markdown, String arquivo) {
        List<Regra> saida = new ArrayList<>();
        Matcher m = CABECALHO.matcher(markdown);
        List<int[]> posicoes = new ArrayList<>();
        List<String[]> cabecalhos = new ArrayList<>();
        while (m.find()) {
            posicoes.add(new int[] {m.start(), m.end()});
            cabecalhos.add(new String[] {m.group(1), m.group(2).trim()});
        }
        for (int i = 0; i < posicoes.size(); i++) {
            int fim = i + 1 < posicoes.size() ? posicoes.get(i + 1)[0] : markdown.length();
            String corpo = markdown.substring(posicoes.get(i)[1], fim).trim();
            saida.add(new Regra(cabecalhos.get(i)[0], cabecalhos.get(i)[1], corpo, arquivo));
        }
        return saida;
    }

    /**
     * Busca as regras mais parecidas com a consulta.
     *
     * @param consulta  texto livre ou um trecho de código
     * @param quantas   máximo de regras devolvidas
     * @return regras com nota maior que zero, da mais relevante para a menos relevante
     */
    public List<Resultado> buscar(String consulta, int quantas) {
        List<String> termos = termos(consulta);
        List<Resultado> resultados = new ArrayList<>();
        for (int i = 0; i < regras.size(); i++) {
            Map<String, Integer> freq = frequencias.get(i);
            int tamanho = freq.values().stream().mapToInt(Integer::intValue).sum();
            double nota = 0;
            for (String t : termos) {
                int f = freq.getOrDefault(t, 0);
                if (f == 0) continue;
                double ajuste = 1 - B + B * tamanho / tamanhoMedio;
                nota += idf.get(t) * f * (K1 + 1) / (f + K1 * ajuste);
            }
            if (nota > 0) resultados.add(new Resultado(regras.get(i), Math.round(nota * 100) / 100.0));
        }
        resultados.sort(Comparator.comparingDouble(Resultado::nota).reversed());
        return resultados.subList(0, Math.min(quantas, resultados.size()));
    }

    public List<Regra> todas() {
        return List.copyOf(regras);
    }

    /** Uma regra encontrada e a nota dela na busca (quanto maior, mais relacionada). */
    public record Resultado(Regra regra, double nota) {}

    // ---------------------------------------------------------------- internos

    private void indexar() {
        Map<String, Integer> emQuantasRegras = new HashMap<>();
        long soma = 0;
        for (Regra r : regras) {
            Map<String, Integer> freq = new HashMap<>();
            // O título e o código entram duas vezes: pesam mais que o corpo.
            String texto = r.codigo() + " " + r.titulo() + " " + r.titulo() + " " + r.texto();
            for (String t : termos(texto)) freq.merge(t, 1, Integer::sum);
            frequencias.add(freq);
            soma += freq.values().stream().mapToInt(Integer::intValue).sum();
            for (String t : freq.keySet()) emQuantasRegras.merge(t, 1, Integer::sum);
        }
        tamanhoMedio = regras.isEmpty() ? 1 : (double) soma / regras.size();
        int n = regras.size();
        emQuantasRegras.forEach((t, df) -> idf.put(t, Math.log(1 + (n - df + 0.5) / (df + 0.5))));
    }

    /**
     * Quebra em termos: minúsculas, sem acento, separa camelCase ("findById" vira find, by, id)
     * e mantém anotações e chamadas inteiras ("@transactional", "optional.get") como termo extra.
     */
    static List<String> termos(String texto) {
        if (texto == null) return List.of();
        String camel = texto.replaceAll("([a-z])([A-Z])", "$1 $2");
        String base = Normalizer.normalize(texto + " " + camel, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        List<String> saida = new ArrayList<>();
        Matcher m = PALAVRA.matcher(base);
        while (m.find()) {
            String p = m.group().replaceAll("^[.]+|[.]+$", "");
            if (p.length() < 2 || IGNORAR.contains(p)) continue;
            saida.add(p);
            if (p.contains(".")) { // "optional.get" também conta como "optional" e "get"
                for (String parte : p.split("\\.")) {
                    if (parte.length() >= 2 && !IGNORAR.contains(parte)) saida.add(parte);
                }
            }
            if (p.startsWith("@")) saida.add(p.substring(1));
        }
        return saida;
    }

    private static List<Regra> carregarDoClasspath() throws IOException {
        List<Regra> todas = new ArrayList<>();
        Resource[] arquivos = new PathMatchingResourcePatternResolver().getResources("classpath*:regras/*.md");
        for (Resource r : arquivos) {
            try (InputStream in = r.getInputStream()) {
                todas.addAll(dividir(new String(in.readAllBytes(), StandardCharsets.UTF_8), r.getFilename()));
            }
        }
        todas.sort(Comparator.comparing(Regra::codigo));
        return todas;
    }
}
