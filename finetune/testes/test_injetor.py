import json
import random

from formato import ler_resposta, patch_numerado, resposta_esperada
from injetor import (
    Exemplo,
    gerar_exemplos,
    insercao_sql_concatenado,
    mutacao_optional_get,
    mutacao_string_igual,
    mutacao_tirar_transacao,
)

SERVICO = """package demo;

public class PedidoService {

    private final PedidoRepository repository;

    @Transactional
    public void finalizar(Pedido p, Estoque e) {
        repository.save(p);
        estoques.save(e);
    }

    public Pedido buscar(Long id) {
        return repository.findById(id).orElseThrow(() -> new NaoEncontrado(id));
    }

    public boolean pago(Pedido p) {
        return "PAGO".equals(p.getStatus());
    }
}
"""


def linha(codigo: str, n: int) -> str:
    return codigo.splitlines()[n - 1]


def test_optional_get_aponta_a_linha_certa():
    novo, bug = mutacao_optional_get(SERVICO)
    assert ".findById(id).get();" in linha(novo, bug.linha)
    assert bug.regra == "OPT-01"


def test_string_igual_troca_equals_por_igual_igual():
    novo, bug = mutacao_string_igual(SERVICO)
    assert 'p.getStatus() == "PAGO"' in linha(novo, bug.linha)


def test_tirar_transacao_aponta_para_a_assinatura():
    novo, bug = mutacao_tirar_transacao(SERVICO)
    assert "@Transactional" not in novo
    assert "public void finalizar" in linha(novo, bug.linha)


def test_insercao_aponta_para_a_linha_do_bug():
    random.seed(1)
    novo, bug = insercao_sql_concatenado(SERVICO)
    assert '" + ' in linha(novo, bug.linha) and "select" in linha(novo, bug.linha)
    assert novo.rstrip().endswith("}")


def test_linha_do_bug_aparece_como_adicionada_no_patch_numerado():
    random.seed(1)
    novo, bug = insercao_sql_concatenado(SERVICO)
    numerado = patch_numerado(Exemplo("A.java", SERVICO, novo, [bug]).patch())
    assert any(l.startswith(f"{bug.linha:5d} + ") for l in numerado.splitlines())


def test_gera_varios_tipos_para_um_arquivo():
    random.seed(3)
    tipos = {e.tipo for e in gerar_exemplos("A.java", SERVICO)}
    assert {"mutacao_optional_get", "mutacao_string_igual", "mutacao_tirar_transacao", "insercao_sql_concatenado"} <= tipos


def test_resposta_esperada_e_leitura_batem():
    _, bug = mutacao_optional_get(SERVICO)
    texto = "Claro! ```json\n" + resposta_esperada([bug]) + "\n```"
    lido = ler_resposta(texto)
    assert lido[0]["linha"] == bug.linha and lido[0]["regra"] == "OPT-01"


def test_resposta_quebrada_vira_lista_vazia():
    assert ler_resposta("não sei") == []
    assert ler_resposta("[{linha: 3}]") == []
    assert json.loads(resposta_esperada([])) == []
