"""Gera o dataset de treino e de teste a partir de projetos Java com licença permissiva.

Uso:
    uv run python gerar_dataset.py --treino fontes/spring-petclinic fontes/outro --teste fontes/terceiro --saida dados

Regra importante: os projetos de TESTE são diferentes dos de TREINO. Se o mesmo arquivo aparecesse
nos dois, o modelo poderia "decorar" o código e a nota da avaliação seria falsa (vazamento de dados).

Saída (formato de conversa, aceito pelo SFTTrainer/Unsloth):
    dados/treino.jsonl  {"messages": [{"role":"system",...},{"role":"user",...},{"role":"assistant",...}]}
    dados/teste.jsonl   o mesmo, mais "bugs" (resposta certa) e "tipo" para a avaliação
"""

from __future__ import annotations

import argparse
import json
import random
from collections import Counter
from pathlib import Path

from formato import INSTRUCAO, mensagem_usuario, resposta_esperada
from injetor import gerar_exemplos


def arquivos_java(raiz: Path) -> list[Path]:
    """Só código de produção (src/main/java), sem testes e sem arquivos gigantes."""
    return sorted(
        p
        for p in raiz.rglob("*.java")
        if "src/main/java" in p.as_posix() and p.stat().st_size < 40_000
    )


def exemplos_de(raizes: list[Path]) -> list[dict]:
    registros = []
    for raiz in raizes:
        for arquivo in arquivos_java(raiz):
            codigo = arquivo.read_text(encoding="utf-8", errors="ignore")
            if "class " not in codigo:
                continue
            caminho = arquivo.relative_to(raiz).as_posix()
            for ex in gerar_exemplos(caminho, codigo):
                patch = ex.patch()
                if not patch:
                    continue
                registros.append({
                    "messages": [
                        {"role": "system", "content": INSTRUCAO},
                        {"role": "user", "content": mensagem_usuario(ex.caminho, patch)},
                        {"role": "assistant", "content": resposta_esperada(ex.bugs)},
                    ],
                    "bugs": [b.__dict__ for b in ex.bugs],
                    "tipo": ex.tipo,
                    "projeto": raiz.name,
                })
    return registros


def salvar(caminho: Path, registros: list[dict], com_gabarito: bool) -> None:
    with caminho.open("w", encoding="utf-8") as f:
        for r in registros:
            linha = r if com_gabarito else {"messages": r["messages"]}
            f.write(json.dumps(linha, ensure_ascii=False) + "\n")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--treino", nargs="+", type=Path, required=True, help="pastas dos projetos de treino")
    p.add_argument("--teste", nargs="+", type=Path, required=True, help="pastas dos projetos de teste (diferentes!)")
    p.add_argument("--saida", type=Path, default=Path("dados"))
    p.add_argument("--semente", type=int, default=42, help="mesma semente = mesmo dataset")
    a = p.parse_args()

    sobrepostos = {x.resolve() for x in a.treino} & {x.resolve() for x in a.teste}
    if sobrepostos:
        raise SystemExit(f"Projeto em treino e teste ao mesmo tempo (vazamento): {sobrepostos}")

    random.seed(a.semente)
    treino, teste = exemplos_de(a.treino), exemplos_de(a.teste)
    random.shuffle(treino)
    a.saida.mkdir(parents=True, exist_ok=True)
    salvar(a.saida / "treino.jsonl", treino, com_gabarito=False)
    salvar(a.saida / "teste.jsonl", teste, com_gabarito=True)

    for nome, regs in (("treino", treino), ("teste", teste)):
        tipos = Counter(r["tipo"] for r in regs)
        print(f"{nome}: {len(regs)} exemplos  " + ", ".join(f"{t}={n}" for t, n in tipos.most_common()))


if __name__ == "__main__":
    main()
