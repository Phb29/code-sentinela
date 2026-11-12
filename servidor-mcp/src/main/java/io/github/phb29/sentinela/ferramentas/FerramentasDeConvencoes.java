package io.github.phb29.sentinela.ferramentas;

import io.github.phb29.sentinela.convencoes.BaseDeConvencoes;
import io.github.phb29.sentinela.convencoes.Regra;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Ferramentas do RAG: dão ao Claude as convenções do time na hora da revisão. */
@Component
public class FerramentasDeConvencoes {

    private final BaseDeConvencoes base;

    public FerramentasDeConvencoes(BaseDeConvencoes base) {
        this.base = base;
    }

    @McpTool(name = "buscar_convencoes", description = """
            Busca nas convenções de código do time (Java/Spring) as regras relacionadas a um assunto
            ou a um trecho de código. Use antes de comentar, para embasar o comentário e citar o código
            da regra (ex.: SQL-01). Exemplos de consulta: "concatenação de SQL com parâmetro",
            "findById().get()", "save em dois repositórios sem transação".""")
    public String buscarConvencoes(
            @McpToolParam(description = "Assunto ou trecho de código") String consulta,
            @McpToolParam(description = "Quantas regras devolver (padrão 3, máximo 6)", required = false) Integer quantas) {
        int n = Math.min(6, Math.max(1, quantas == null ? 3 : quantas));
        List<BaseDeConvencoes.Resultado> achadas = base.buscar(consulta, n);
        if (achadas.isEmpty()) {
            return "Nenhuma convenção encontrada para: " + consulta
                    + "\nSe ainda assim for um problema, comente sem citar regra. Use listar_convencoes para ver todas.";
        }
        StringBuilder sb = new StringBuilder();
        for (BaseDeConvencoes.Resultado r : achadas) {
            Regra regra = r.regra();
            sb.append("[").append(regra.codigo()).append("] ").append(regra.titulo())
                    .append("  (relevância ").append(r.nota()).append(")\n")
                    .append(regra.texto()).append("\n\n");
        }
        return sb.toString().trim();
    }

    @McpTool(name = "listar_convencoes", description = "Lista o código e o título de todas as convenções do time.")
    public String listarConvencoes() {
        StringBuilder sb = new StringBuilder();
        for (Regra r : base.todas()) {
            sb.append(r.codigo()).append(" · ").append(r.titulo()).append('\n');
        }
        return sb.toString();
    }
}
