package io.github.phb29.sentinela.revisao;

import java.text.Normalizer;
import java.util.Locale;

/** Gravidade de um comentário. Serve para ordenar e para o leitor saber o que corrigir primeiro. */
public enum Severidade {
    BLOQUEANTE("🔴", "bug, falha de segurança ou perda de dados: não deveria ir para produção"),
    IMPORTANTE("🟠", "problema real, mas que não quebra na hora (desempenho, manutenção)"),
    SUGESTAO("🔵", "melhoria de legibilidade ou estilo");

    private final String emoji;
    private final String significado;

    Severidade(String emoji, String significado) {
        this.emoji = emoji;
        this.significado = significado;
    }

    public String emoji() {
        return emoji;
    }

    public String significado() {
        return significado;
    }

    /** Aceita "bloqueante", "Importante", "sugestão" (com ou sem acento). Valor desconhecido vira SUGESTAO. */
    public static Severidade de(String texto) {
        if (texto == null) return SUGESTAO;
        String limpo = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .trim()
                .toUpperCase(Locale.ROOT);
        try {
            return valueOf(limpo);
        } catch (IllegalArgumentException e) {
            return SUGESTAO;
        }
    }

    /** Diz se o texto é exatamente um dos três valores aceitos. */
    public static boolean valida(String texto) {
        if (texto == null) return false;
        String limpo = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").trim().toUpperCase(Locale.ROOT);
        for (Severidade s : values()) {
            if (s.name().equals(limpo)) return true;
        }
        return false;
    }
}
