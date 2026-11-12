"""Injetor de bugs: pega código Java correto e coloca UM bug conhecido.

Como fomos nós que colocamos o bug, sabemos a resposta certa (arquivo, linha, regra, severidade).
Isso resolve dois problemas de uma vez:
- dados de treino sem depender de comentários de terceiros (licença);
- avaliação objetiva: o modelo achou o bug na linha certa? (sem precisar de outro LLM como juiz)

Há dois tipos de injeção:
- MUTAÇÃO: troca um trecho que já existe (ex.: .orElseThrow(...) vira .get());
- INSERÇÃO: acrescenta um método novo com o bug no fim da classe (ex.: SQL concatenado).
E há o exemplo LIMPO: uma mudança inofensiva, onde o certo é NÃO comentar nada (mede o ruído).
"""

from __future__ import annotations

import difflib
import random
import re
from dataclasses import dataclass, field


@dataclass
class Bug:
    """A resposta certa de um exemplo."""

    linha: int  # linha no arquivo NOVO
    regra: str  # código da convenção (ex.: SQL-01)
    severidade: str  # bloqueante | importante | sugestao
    titulo: str


@dataclass
class Exemplo:
    caminho: str
    antes: str
    depois: str
    bugs: list[Bug] = field(default_factory=list)  # vazio = exemplo limpo
    tipo: str = ""

    def patch(self) -> str:
        """Diff unificado no mesmo formato que o GitHub devolve (só os trechos @@)."""
        linhas = difflib.unified_diff(
            self.antes.splitlines(), self.depois.splitlines(), lineterm="", n=3
        )
        return "\n".join(l for l in linhas if not l.startswith(("---", "+++")))


# ----------------------------------------------------------------- utilidades


def _fim_da_classe(linhas: list[str]) -> int | None:
    """Índice da última '}' do arquivo (fecha a classe principal)."""
    for i in range(len(linhas) - 1, -1, -1):
        if linhas[i].strip() == "}":
            return i
    return None


def _indentacao(linhas: list[str]) -> str:
    for l in linhas:
        m = re.match(r"^(\s+)(public|private|protected)\s", l)
        if m:
            return m.group(1)
    return "    "


def _inserir_metodo(codigo: str, metodo: list[str], linha_do_bug: int) -> tuple[str, int] | None:
    """Insere o método antes do '}' final. Devolve o código novo e a linha (1-based) do bug."""
    linhas = codigo.splitlines()
    fim = _fim_da_classe(linhas)
    if fim is None:
        return None
    ind = _indentacao(linhas)
    bloco = [""] + [ind + l if l else "" for l in metodo]
    novas = linhas[:fim] + bloco + linhas[fim:]
    return "\n".join(novas) + "\n", fim + 1 + 1 + linha_do_bug  # +1 linha vazia, +1 base 1


def _classe_e_entidade(codigo: str) -> tuple[str, str]:
    classe = re.search(r"\bclass\s+(\w+)", codigo)
    nome = classe.group(1) if classe else "Servico"
    entidade = re.sub(r"(Service|Controller|Repository|Impl|Servico)$", "", nome) or "Registro"
    return nome, entidade


# ----------------------------------------------------------------- mutações


def mutacao_optional_get(codigo: str) -> tuple[str, Bug] | None:
    """OPT-01: findById(x).orElseThrow(...) -> findById(x).get()"""
    linhas = codigo.splitlines()
    for i, l in enumerate(linhas):
        m = re.search(r"\.orElseThrow\((?:[^;]*)\)\s*;", l)
        if m and "findBy" in l:
            linhas[i] = l[: m.start()] + ".get();" + l[m.end():]
            return "\n".join(linhas) + "\n", Bug(i + 1, "OPT-01", "bloqueante", "Optional.get() sem verificar se existe")
    return None


def mutacao_string_igual(codigo: str) -> tuple[str, Bug] | None:
    """OPT-03: "X".equals(var) ou var.equals("X") -> var == "X" """
    linhas = codigo.splitlines()
    for i, l in enumerate(linhas):
        m = re.search(r'"([^"\\]{1,30})"\.equals\((\w+(?:\.\w+\(\))?)\)', l) or re.search(
            r'(\w+(?:\.\w+\(\))?)\.equals\("([^"\\]{1,30})"\)', l
        )
        if m:
            if l[m.start()] == '"':
                texto, var = m.group(1), m.group(2)
            else:
                var, texto = m.group(1), m.group(2)
            linhas[i] = l[: m.start()] + f'{var} == "{texto}"' + l[m.end():]
            return "\n".join(linhas) + "\n", Bug(i + 1, "OPT-03", "bloqueante", "String comparada com == em vez de equals")
    return None


def mutacao_tirar_transacao(codigo: str) -> tuple[str, Bug] | None:
    """TX-01: remove @Transactional de método que chama save/delete mais de uma vez."""
    linhas = codigo.splitlines()
    for i, l in enumerate(linhas):
        if l.strip() != "@Transactional":
            continue
        # corpo do método logo abaixo: conta escritas até a próxima anotação/método
        corpo = "\n".join(linhas[i + 1 : i + 30])
        fim_metodo = re.search(r"\n\s*}\s*\n", corpo)
        trecho = corpo[: fim_metodo.start()] if fim_metodo else corpo
        if len(re.findall(r"\.(save|saveAll|delete|saveAndFlush)\(", trecho)) >= 2:
            novas = linhas[:i] + linhas[i + 1 :]
            # o comentário vai na assinatura do método (que subiu uma linha)
            return "\n".join(novas) + "\n", Bug(i + 1, "TX-01", "bloqueante", "Método grava em mais de um lugar sem @Transactional")
    return None


def mutacao_log_concatenado(codigo: str) -> tuple[str, Bug] | None:
    """SEG-03: log.info("Pedido {} criado", id) -> log.info("Pedido " + id + " criado")"""
    linhas = codigo.splitlines()
    for i, l in enumerate(linhas):
        m = re.search(r'(log\.(?:info|debug|warn))\("([^"{}]*)\{\}([^"{}]*)",\s*([\w.()]+)\)', l)
        if m:
            nivel, antes, depois, arg = m.groups()
            novo = f'{nivel}("{antes}" + {arg}' + (f' + "{depois}")' if depois else ")")
            linhas[i] = l[: m.start()] + novo + l[m.end():]
            return "\n".join(linhas) + "\n", Bug(i + 1, "SEG-03", "sugestao", "Log com concatenação em vez de placeholder")
    return None


# ----------------------------------------------------------------- inserções


def insercao_sql_concatenado(codigo: str) -> tuple[str, Bug] | None:
    """SQL-01: método novo que monta JPQL com o parâmetro concatenado."""
    _, ent = _classe_e_entidade(codigo)
    campo = random.choice(["nome", "email", "cidade", "descricao"])
    metodo = [
        f"public List<{ent}> buscarPor{campo.capitalize()}(String {campo}) {{",
        f'    String jpql = "select x from {ent} x where x.{campo} = \'" + {campo} + "\'";',
        f"    return entityManager.createQuery(jpql, {ent}.class).getResultList();",
        "}",
    ]
    r = _inserir_metodo(codigo, metodo, 1)
    return (r[0], Bug(r[1], "SQL-01", "bloqueante", "JPQL montada com concatenação permite SQL injection")) if r else None


def insercao_excecao_engolida(codigo: str) -> tuple[str, Bug] | None:
    """EXC-01: catch vazio."""
    _, ent = _classe_e_entidade(codigo)
    metodo = [
        f"public void notificar{ent}(Long id) {{",
        "    try {",
        "        notificador.enviar(id);",
        "    } catch (Exception e) {",
        "    }",
        "}",
    ]
    r = _inserir_metodo(codigo, metodo, 3)
    return (r[0], Bug(r[1], "EXC-01", "importante", "Exceção engolida em catch vazio")) if r else None


def insercao_dinheiro_double(codigo: str) -> tuple[str, Bug] | None:
    """REC-03: valor monetário em double."""
    nome = random.choice(["calcularTaxa", "calcularDesconto", "calcularJuros", "calcularComissao"])
    metodo = [
        f"public double {nome}(double valor) {{",
        f"    return valor * {random.choice(['0.03', '0.15', '0.075', '0.1'])};",
        "}",
    ]
    r = _inserir_metodo(codigo, metodo, 0)
    return (r[0], Bug(r[1], "REC-03", "importante", "Valor em dinheiro calculado com double")) if r else None


def insercao_log_senha(codigo: str) -> tuple[str, Bug] | None:
    """SEG-01: dado sensível no log."""
    campo = random.choice(["senha", "token", "numeroCartao"])
    metodo = [
        f"public void autenticar(String usuario, String {campo}) {{",
        f'    log.info("Login de " + usuario + " com {campo} " + {campo});',
        f"    autenticador.autenticar(usuario, {campo});",
        "}",
    ]
    r = _inserir_metodo(codigo, metodo, 1)
    return (r[0], Bug(r[1], "SEG-01", "bloqueante", f"Dado sensível ({campo}) registrado no log")) if r else None


def insercao_recurso_aberto(codigo: str) -> tuple[str, Bug] | None:
    """REC-01: leitor de arquivo que nunca é fechado."""
    metodo = [
        "public String lerPrimeiraLinha(Path arquivo) throws IOException {",
        "    BufferedReader leitor = Files.newBufferedReader(arquivo);",
        "    return leitor.readLine();",
        "}",
    ]
    r = _inserir_metodo(codigo, metodo, 1)
    return (r[0], Bug(r[1], "REC-01", "importante", "BufferedReader nunca é fechado")) if r else None


# ----------------------------------------------------------------- exemplo limpo


def mudanca_limpa(codigo: str) -> str | None:
    """Mudança inofensiva: método novo, correto, que usa as boas práticas. O certo é não comentar."""
    _, ent = _classe_e_entidade(codigo)
    metodo = [
        "/** Quantidade de registros cadastrados. */",
        f"public long contar{ent}s() {{",
        "    return repository.count();",
        "}",
    ]
    r = _inserir_metodo(codigo, metodo, 0)
    return r[0] if r else None


INJECOES = [
    mutacao_optional_get,
    mutacao_string_igual,
    mutacao_tirar_transacao,
    mutacao_log_concatenado,
    insercao_sql_concatenado,
    insercao_excecao_engolida,
    insercao_dinheiro_double,
    insercao_log_senha,
    insercao_recurso_aberto,
]


def gerar_exemplos(caminho: str, codigo: str, proporcao_limpos: float = 0.2) -> list[Exemplo]:
    """Gera todos os exemplos possíveis para um arquivo: um por injeção aplicável, mais alguns limpos."""
    exemplos: list[Exemplo] = []
    for injetar in INJECOES:
        r = injetar(codigo)
        if r:
            depois, bug = r
            exemplos.append(Exemplo(caminho, codigo, depois, [bug], injetar.__name__))
    if exemplos and random.random() < proporcao_limpos * len(exemplos):
        limpo = mudanca_limpa(codigo)
        if limpo:
            exemplos.append(Exemplo(caminho, codigo, limpo, [], "limpo"))
    return exemplos
