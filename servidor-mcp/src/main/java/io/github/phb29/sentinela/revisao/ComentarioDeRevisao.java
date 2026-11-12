package io.github.phb29.sentinela.revisao;

import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * Um comentário que o revisor quer deixar numa linha do PR.
 * O Claude Code preenche estes campos ao chamar a ferramenta publicar_revisao.
 *
 * @param caminho     arquivo, como aparece no PR (ex.: src/main/java/.../PedidoService.java)
 * @param linha       número da linha no arquivo NOVO (o número que aparece no patch numerado)
 * @param severidade  bloqueante, importante ou sugestao
 * @param titulo      resumo de uma linha do problema
 * @param explicacao  por que é um problema e qual o risco
 * @param sugestao    como corrigir (pode trazer código); opcional
 * @param regra       código da convenção que embasa o comentário (ex.: TX-01); opcional
 */
public record ComentarioDeRevisao(
        @McpToolParam(description = "Arquivo, exatamente como aparece no PR") String caminho,
        @McpToolParam(description = "Linha no arquivo novo (número que aparece no patch numerado)") int linha,
        @McpToolParam(description = "bloqueante | importante | sugestao") String severidade,
        @McpToolParam(description = "Resumo do problema em uma linha") String titulo,
        @McpToolParam(description = "Por que é um problema e qual o risco") String explicacao,
        @McpToolParam(description = "Como corrigir, pode conter código", required = false) String sugestao,
        @McpToolParam(description = "Código da convenção usada, ex.: SQL-01", required = false) String regra) {

    /** Texto que vai para o GitHub (o GitHub mostra Markdown). */
    public String comoMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append(Severidade.de(severidade).emoji()).append(" **").append(titulo).append("**");
        if (regra != null && !regra.isBlank()) sb.append(" · `").append(regra).append('`');
        sb.append("\n\n").append(explicacao);
        if (sugestao != null && !sugestao.isBlank()) sb.append("\n\n**Sugestão:**\n").append(sugestao);
        sb.append("\n\n<sub>Code Sentinela · revisão automática, confira antes de aplicar</sub>");
        return sb.toString();
    }
}
