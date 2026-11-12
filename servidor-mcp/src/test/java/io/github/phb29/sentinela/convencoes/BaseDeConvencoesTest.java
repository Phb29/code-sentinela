package io.github.phb29.sentinela.convencoes;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Testa a busca com as regras reais (src/main/resources/regras). */
class BaseDeConvencoesTest {

    static final BaseDeConvencoes base;

    static {
        try {
            base = new BaseDeConvencoes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void carregaTodasAsRegras() {
        assertThat(base.todas()).hasSize(25);
        assertThat(base.todas()).extracting(Regra::codigo).contains("SQL-01", "TX-01", "OPT-01", "SEG-02");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
        "String sql = \"select * from pedido where cliente = '\" + nome + \"'\"; em.createQuery(sql) | SQL-01",
        "Pedido p = pedidoRepository.findById(id).get();                                        | OPT-01",
        "pedidoRepository.save(p); estoqueRepository.save(e); sem transação                     | TX-01",
        "for (Pedido p : pedidos) { p.getItens().size(); } lazy                                  | SQL-02",
        "catch (Exception e) { }                                                                 | EXC-01",
        "double total = preco * quantidade; valor em dinheiro                                    | REC-03",
        "if (status == \"PAGO\")                                                                 | OPT-03",
        "log.info(\"senha do usuario: \" + senha)                                                | SEG-01",
        "BufferedReader leitor = new BufferedReader(new FileReader(arquivo)); sem fechar         | REC-01",
    })
    void achaARegraCerta(String consulta, String esperada) {
        assertThat(base.buscar(consulta, 3)).extracting(r -> r.regra().codigo()).contains(esperada.trim());
    }

    @Test
    void consultaSemRelacaoNaoAchaNada() {
        assertThat(base.buscar("receita de bolo de cenoura", 3)).isEmpty();
    }

    @Test
    void dividirSeparaPorCabecalho() {
        var regras = BaseDeConvencoes.dividir("# Título\n\n## AB-01 · Primeira\ntexto 1\n\n## AB-02 · Segunda\ntexto 2\n", "x.md");
        assertThat(regras).extracting(Regra::codigo).containsExactly("AB-01", "AB-02");
        assertThat(regras.get(1).texto()).isEqualTo("texto 2");
    }

    @Test
    void termosSeparaCamelCaseEChamadas() {
        assertThat(BaseDeConvencoes.termos("findById().get() @Transactional"))
                .contains("find", "by", "id", "get", "@transactional", "transactional");
    }
}
