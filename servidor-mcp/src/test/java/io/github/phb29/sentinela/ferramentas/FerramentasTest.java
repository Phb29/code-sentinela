package io.github.phb29.sentinela.ferramentas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.phb29.sentinela.convencoes.BaseDeConvencoes;
import io.github.phb29.sentinela.github.GitHubClienteAcesso;
import io.github.phb29.sentinela.revisao.ComentarioDeRevisao;
import io.github.phb29.sentinela.revisao.ValidadorDeComentarios;
import java.util.List;
import org.junit.jupiter.api.Test;

class FerramentasTest {

    @Test
    void resumoContaPorSeveridadeEListaRecusados() {
        var aceito = new ComentarioDeRevisao("A.java", 3, "bloqueante", "SQL concatenado",
                "Permite SQL injection com o parâmetro nome.", "Use setParameter", "SQL-01");
        var recusado = new ValidadorDeComentarios.Recusado(
                new ComentarioDeRevisao("A.java", 99, "sugestao", "Fora", "x", null, null), "a linha 99 não aparece no diff");
        String resumo = FerramentasDeComentario.montarResumo("Dois problemas sérios.",
                new ValidadorDeComentarios.Resultado(List.of(aceito), List.of(recusado)));
        assertThat(resumo).contains("Code Sentinela", "Dois problemas sérios.", "| 🔴 bloqueante | 1 |",
                "| 🔵 sugestao | 0 |", "Observações fora do diff (1)", "A.java:99");
    }

    @Test
    void comentarioViraMarkdownComRegraESugestao() {
        String md = new ComentarioDeRevisao("A.java", 3, "importante", "N+1 no laço",
                "Uma consulta por pedido.", "Use join fetch", "SQL-02").comoMarkdown();
        assertThat(md).startsWith("🟠 **N+1 no laço** · `SQL-02`").contains("**Sugestão:**\nUse join fetch");
    }

    @Test
    void buscarConvencoesCitaCodigoDaRegra() throws Exception {
        var f = new FerramentasDeConvencoes(new BaseDeConvencoes());
        assertThat(f.buscarConvencoes("findById(id).get()", 2)).contains("[OPT-01]");
        assertThat(f.buscarConvencoes("receita de bolo", 2)).startsWith("Nenhuma convenção encontrada");
        assertThat(f.listarConvencoes()).contains("TX-01 · ");
    }

    @Test
    void repositorioInvalidoEhRecusado() {
        assertThatThrownBy(() -> GitHubClienteAcesso.validar("../../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GitHubClienteAcesso.validar("dono/repo/extra")).isInstanceOf(IllegalArgumentException.class);
        assertThat(GitHubClienteAcesso.validar("Phb29/sentinela-demo")).isEqualTo("Phb29/sentinela-demo");
    }
}
