"""Formato comum ao dataset, ao treino e à avaliação: o mesmo prompt e a mesma resposta em JSON.

Treinar e avaliar com exatamente o mesmo formato é obrigatório: se o prompt da avaliação for
diferente do prompt do treino, a nota do modelo treinado cai sem que ele tenha piorado.
"""

from __future__ import annotations

import json
import re

INSTRUCAO = (
    "Você é um revisor de código Java/Spring. Analise SOMENTE as linhas adicionadas (+) do diff. "
    "Responda apenas com um array JSON. Cada item: "
    '{"linha": número da linha no arquivo novo, "regra": código da convenção, '
    '"severidade": "bloqueante"|"importante"|"sugestao", "titulo": resumo curto}. '
    "Se não houver problema, responda []. "
    "Convenções: SQL-01 SQL/JPQL concatenado; OPT-01 Optional.get() sem verificar; OPT-03 String com ==; "
    "TX-01 grava em mais de um lugar sem @Transactional; EXC-01 exceção engolida; REC-01 recurso não fechado; "
    "REC-03 dinheiro em double; SEG-01 dado sensível em log; SEG-03 log concatenado."
)

_CABECALHO = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@")


def patch_numerado(patch: str) -> str:
    """Mesmo formato do Patch.comNumeros do servidor Java: número da linha nova antes de cada linha."""
    saida, atual = [], 0
    for bruta in patch.split("\n"):
        m = _CABECALHO.match(bruta)
        if m:
            atual = int(m.group(1))
            saida.append(bruta)
            continue
        if not bruta or bruta.startswith("\\"):
            continue
        tipo, conteudo = bruta[0], bruta[1:]
        if tipo == "-":
            saida.append(f"     - | {conteudo}")
        else:
            saida.append(f"{atual:5d} {tipo if tipo == '+' else ' '} {conteudo}")
            atual += 1
    return "\n".join(saida)


def mensagem_usuario(caminho: str, patch: str) -> str:
    return f"Arquivo: {caminho}\n\n{patch_numerado(patch)}"


def resposta_esperada(bugs) -> str:
    return json.dumps(
        [{"linha": b.linha, "regra": b.regra, "severidade": b.severidade, "titulo": b.titulo} for b in bugs],
        ensure_ascii=False,
    )


def ler_resposta(texto: str) -> list[dict]:
    """Extrai o array JSON da resposta do modelo, mesmo com texto ou ```json em volta. Falha vira lista vazia."""
    if not texto:
        return []
    bloco = re.search(r"\[.*\]", texto, re.DOTALL)
    if not bloco:
        return []
    try:
        itens = json.loads(bloco.group(0))
    except json.JSONDecodeError:
        return []
    validos = []
    for item in itens if isinstance(itens, list) else []:
        if isinstance(item, dict) and isinstance(item.get("linha"), int):
            validos.append(item)
    return validos
