"""Avalia um modelo revisor no conjunto de teste e mede com números.

Uso:
    uv run python avaliar.py --modelo ollama:qwen2.5-coder:1.5b --teste dados/teste.jsonl --limite 50
    uv run python avaliar.py --modelo ollama:sentinela-lora   (o modelo treinado, depois do fine-tune)
    uv run python avaliar.py --modelo claude                    (usa o Claude Code da sua assinatura)

Métricas (todas calculadas a partir da resposta certa que o injetor guardou):
- acerto      : % dos exemplos com bug em que o modelo comentou a até ±3 linhas do bug
- regra certa : % dos exemplos com bug em que, além da linha, citou a regra certa
- ruído       : % dos exemplos LIMPOS em que o modelo inventou pelo menos um comentário
- json válido : % de respostas que vieram no formato pedido
- tempo médio : segundos por exemplo
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import time
from pathlib import Path

import httpx

from formato import ler_resposta

TOLERANCIA = 3


def perguntar_ollama(modelo: str, mensagens: list[dict], url: str) -> str:
    corpo = {"model": modelo, "messages": mensagens, "stream": False,
             "options": {"temperature": 0, "num_gpu": 0}}
    r = httpx.post(f"{url}/api/chat", json=corpo, timeout=600)
    r.raise_for_status()
    return r.json()["message"]["content"]


def perguntar_claude(mensagens: list[dict]) -> str:
    """Usa o Claude Code em modo não interativo (claude -p). Gasta a cota da assinatura."""
    claude = shutil.which("claude")
    if not claude:
        raise SystemExit("Claude Code não encontrado no PATH")
    sistema = next(m["content"] for m in mensagens if m["role"] == "system")
    usuario = next(m["content"] for m in mensagens if m["role"] == "user")
    r = subprocess.run(
        [claude, "-p", "--output-format", "text", "--append-system-prompt", sistema, "--tools", ""],
        input=usuario, capture_output=True, text=True, encoding="utf-8", timeout=300,
    )
    return r.stdout


def acertou(previstos: list[dict], bug: dict) -> tuple[bool, bool]:
    perto = [p for p in previstos if abs(p["linha"] - bug["linha"]) <= TOLERANCIA]
    return bool(perto), any(str(p.get("regra", "")).upper() == bug["regra"] for p in perto)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--modelo", required=True, help="ollama:<nome> ou claude")
    p.add_argument("--teste", type=Path, default=Path("dados/teste.jsonl"))
    p.add_argument("--limite", type=int, default=0, help="avaliar só os N primeiros (0 = todos)")
    p.add_argument("--ollama-url", default="http://localhost:11434")
    p.add_argument("--saida", type=Path, default=Path("resultados"))
    a = p.parse_args()

    registros = [json.loads(l) for l in a.teste.read_text(encoding="utf-8").splitlines() if l.strip()]
    if a.limite:
        registros = registros[: a.limite]

    com_bug = limpos = achou = regra_certa = ruido = json_ok = 0
    tempos, detalhes = [], []
    for i, r in enumerate(registros, 1):
        perguntas = [m for m in r["messages"] if m["role"] != "assistant"]
        inicio = time.time()
        try:
            if a.modelo == "claude":
                texto = perguntar_claude(perguntas)
            else:
                texto = perguntar_ollama(a.modelo.removeprefix("ollama:"), perguntas, a.ollama_url)
        except Exception as erro:  # um exemplo com erro não derruba a avaliação inteira
            texto = f"ERRO: {erro}"
        tempos.append(time.time() - inicio)
        previstos = ler_resposta(texto)
        json_ok += texto.strip().startswith("[") or bool(previstos) or "[]" in texto

        if r["bugs"]:
            com_bug += 1
            linha_ok, regra_ok = acertou(previstos, r["bugs"][0])
            achou += linha_ok
            regra_certa += regra_ok
            veredito = "acertou" if linha_ok else "errou"
        else:
            limpos += 1
            ruido += bool(previstos)
            veredito = "ruido" if previstos else "limpo-ok"
        detalhes.append({"tipo": r["tipo"], "veredito": veredito, "esperado": r["bugs"], "resposta": texto[:600]})
        print(f"[{i}/{len(registros)}] {r['tipo']:<28} {veredito:<9} {tempos[-1]:.1f}s")

    def pct(x: int, total: int) -> str:
        return f"{100 * x / total:.1f}%" if total else "n/a"

    resumo = {
        "modelo": a.modelo,
        "exemplos": len(registros),
        "com_bug": com_bug,
        "limpos": limpos,
        "acerto": pct(achou, com_bug),
        "regra_certa": pct(regra_certa, com_bug),
        "ruido": pct(ruido, limpos),
        "json_valido": pct(json_ok, len(registros)),
        "tempo_medio_s": round(sum(tempos) / len(tempos), 1) if tempos else 0,
    }
    a.saida.mkdir(parents=True, exist_ok=True)
    nome = a.modelo.replace(":", "_").replace("/", "_")
    (a.saida / f"{nome}.json").write_text(
        json.dumps({"resumo": resumo, "detalhes": detalhes}, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print("\n" + json.dumps(resumo, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
