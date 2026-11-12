package io.github.phb29.sentinela.revisao;

import io.github.phb29.sentinela.diff.Patch;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Filtro final antes de publicar: decide quais comentários podem ir para o GitHub.
 *
 * <p>Regras (cada uma evita um problema real):
 * <ol>
 *   <li>o arquivo precisa estar no PR: senão o GitHub recusa a revisão inteira (erro 422);</li>
 *   <li>a linha precisa aparecer no diff: mesmo motivo;</li>
 *   <li>severidade precisa ser bloqueante, importante ou sugestao;</li>
 *   <li>título e explicação não podem ser vazios nem curtos demais: comentário vago é ruído;</li>
 *   <li>dois comentários na mesma linha com o mesmo título: fica só o primeiro.</li>
 * </ol>
 * O que for barrado não some: volta com o motivo, e o resumo da revisão pode mencioná-lo.
 */
public final class ValidadorDeComentarios {

    private static final int MAX_COMENTARIOS = 25;

    /** Resultado da validação: o que passou e o que foi barrado (com o motivo). */
    public record Resultado(List<ComentarioDeRevisao> aceitos, List<Recusado> recusados) {}

    public record Recusado(ComentarioDeRevisao comentario, String motivo) {}

    private ValidadorDeComentarios() {}

    /**
     * @param comentarios o que o modelo quer publicar
     * @param patches     patch de cada arquivo do PR (chave = caminho)
     */
    public static Resultado validar(List<ComentarioDeRevisao> comentarios, Map<String, Patch> patches) {
        List<ComentarioDeRevisao> aceitos = new ArrayList<>();
        List<Recusado> recusados = new ArrayList<>();
        Set<String> vistos = new HashSet<>();

        for (ComentarioDeRevisao c : comentarios == null ? List.<ComentarioDeRevisao>of() : comentarios) {
            Patch patch = patches.get(c.caminho());
            String motivo = null;
            if (patch == null) {
                motivo = "o arquivo " + c.caminho() + " não faz parte do PR";
            } else if (!patch.podeComentar(c.linha())) {
                motivo = "a linha " + c.linha() + " não aparece no diff de " + c.caminho();
            } else if (!Severidade.valida(c.severidade())) {
                motivo = "severidade inválida: use bloqueante, importante ou sugestao";
            } else if (vazio(c.titulo(), 5) || vazio(c.explicacao(), 20)) {
                motivo = "comentário vago: título ou explicação curtos demais";
            } else if (!vistos.add(c.caminho() + ":" + c.linha() + ":" + c.titulo().trim().toLowerCase())) {
                motivo = "comentário repetido na mesma linha";
            }
            if (motivo == null) aceitos.add(c);
            else recusados.add(new Recusado(c, motivo));
        }

        // Mais graves primeiro; muitos comentários viram ruído, então há um teto.
        aceitos.sort(Comparator.comparing((ComentarioDeRevisao c) -> Severidade.de(c.severidade()))
                .thenComparing(ComentarioDeRevisao::caminho)
                .thenComparingInt(ComentarioDeRevisao::linha));
        if (aceitos.size() > MAX_COMENTARIOS) {
            for (ComentarioDeRevisao extra : aceitos.subList(MAX_COMENTARIOS, aceitos.size())) {
                recusados.add(new Recusado(extra, "limite de " + MAX_COMENTARIOS + " comentários por revisão"));
            }
            aceitos = new ArrayList<>(aceitos.subList(0, MAX_COMENTARIOS));
        }
        return new Resultado(aceitos, recusados);
    }

    private static boolean vazio(String texto, int minimo) {
        return texto == null || texto.isBlank() || texto.trim().length() < minimo;
    }
}
