package io.github.phb29.sentinela.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê o "patch" de um arquivo (o trecho do diff unificado que o GitHub devolve) e descobre:
 * <ul>
 *   <li>quais linhas do arquivo NOVO aparecem no diff (só nelas o GitHub aceita comentário);</li>
 *   <li>quais dessas linhas foram adicionadas (as que mais interessam na revisão).</li>
 * </ul>
 *
 * <p>Formato de um patch:
 * <pre>
 * @@ -10,4 +10,6 @@ class Pedido {
 *      linha de contexto        (existe antes e depois)
 * -    linha removida           (só existia antes)
 * +    linha adicionada         (só existe depois)
 * </pre>
 * O "+10,6" do cabeçalho diz que o trecho começa na linha 10 do arquivo novo.
 */
public final class Patch {

    private static final Pattern CABECALHO = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");

    /** Uma linha do patch já com o número que ela tem no arquivo novo (0 para linhas removidas). */
    public record Linha(char tipo, int numeroNovo, String texto) {}

    private final List<Linha> linhas;
    private final Set<Integer> linhasComentaveis;
    private final Set<Integer> linhasAdicionadas;

    private Patch(List<Linha> linhas, Set<Integer> comentaveis, Set<Integer> adicionadas) {
        this.linhas = linhas;
        this.linhasComentaveis = comentaveis;
        this.linhasAdicionadas = adicionadas;
    }

    /** Monta o Patch a partir do texto. Patch nulo (arquivo binário ou grande demais) vira patch vazio. */
    public static Patch ler(String texto) {
        List<Linha> linhas = new ArrayList<>();
        Set<Integer> comentaveis = new TreeSet<>();
        Set<Integer> adicionadas = new TreeSet<>();
        if (texto == null || texto.isEmpty()) {
            return new Patch(linhas, comentaveis, adicionadas);
        }
        int atual = 0;
        for (String bruta : texto.split("\n", -1)) {
            Matcher m = CABECALHO.matcher(bruta);
            if (m.matches()) {
                atual = Integer.parseInt(m.group(1));
                linhas.add(new Linha('@', 0, bruta));
                continue;
            }
            if (bruta.isEmpty() || bruta.startsWith("\\")) {
                continue; // fim do texto ou "\ No newline at end of file"
            }
            char tipo = bruta.charAt(0);
            String conteudo = bruta.substring(1);
            switch (tipo) {
                case '+' -> {
                    linhas.add(new Linha('+', atual, conteudo));
                    comentaveis.add(atual);
                    adicionadas.add(atual);
                    atual++;
                }
                case '-' -> linhas.add(new Linha('-', 0, conteudo));
                default -> {
                    linhas.add(new Linha(' ', atual, conteudo));
                    comentaveis.add(atual);
                    atual++;
                }
            }
        }
        return new Patch(linhas, comentaveis, adicionadas);
    }

    /** O GitHub só aceita comentário em linha que aparece no diff (adicionada ou de contexto). */
    public boolean podeComentar(int linha) {
        return linhasComentaveis.contains(linha);
    }

    public Set<Integer> linhasAdicionadas() {
        return Collections.unmodifiableSet(linhasAdicionadas);
    }

    public List<Linha> linhas() {
        return Collections.unmodifiableList(linhas);
    }

    /**
     * Devolve o patch com o número da linha do arquivo novo na frente de cada linha.
     * Assim o modelo sabe exatamente em qual linha comentar, sem precisar fazer conta.
     *
     * <pre>
     *   42 + String sql = "select * from pedido where id = " + id;
     *    - | String sql = "select * from pedido where id = ?";
     * </pre>
     *
     * @param maxLinhas corta patches enormes; avisa no fim quantas linhas foram omitidas
     */
    public String comNumeros(int maxLinhas) {
        StringBuilder sb = new StringBuilder();
        int escritas = 0;
        for (Linha l : linhas) {
            if (escritas >= maxLinhas) {
                sb.append("... (").append(linhas.size() - escritas).append(" linhas omitidas: patch grande demais)\n");
                break;
            }
            switch (l.tipo()) {
                case '@' -> sb.append(l.texto()).append('\n');
                case '-' -> sb.append("     - | ").append(l.texto()).append('\n');
                default -> sb.append(String.format("%5d %c %s\n", l.numeroNovo(), l.tipo(), l.texto()));
            }
            escritas++;
        }
        return sb.toString();
    }
}
