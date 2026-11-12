# Recursos e concorrência

## REC-01 · Fechar recursos com try-with-resources
InputStream, Connection, ResultSet, BufferedReader e afins precisam ser fechados mesmo quando dá erro. Sem fechar, a aplicação esgota conexões do banco ou arquivos abertos.
Certo: `try (var in = Files.newInputStream(caminho)) { ... }`.

## REC-02 · Bean do Spring não guarda estado mutável
Serviços (@Service, @Component) são singletons usados por várias requisições ao mesmo tempo. Campo mutável (contador, lista, "ultimoPedido") gera condição de corrida. Use variáveis locais, ou estruturas thread-safe se o estado for mesmo necessário.

## REC-03 · Dinheiro em BigDecimal, nunca double
`double` não representa centavos com exatidão: 0.1 + 0.2 = 0.30000000000000004. Valores monetários usam BigDecimal com escala e RoundingMode definidos.
