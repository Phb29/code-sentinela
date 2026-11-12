package io.github.phb29.sentinela.convencoes;

/**
 * Um trecho da base de convenções: uma regra só.
 *
 * @param codigo  identificador citado nos comentários (ex.: SQL-01)
 * @param titulo  nome curto da regra
 * @param texto   explicação, exemplos de errado e certo
 * @param arquivo de qual arquivo .md ela veio
 */
public record Regra(String codigo, String titulo, String texto, String arquivo) {}
