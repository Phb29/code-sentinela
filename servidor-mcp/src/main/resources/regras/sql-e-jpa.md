# SQL e JPA

## SQL-01 · Nunca concatenar entrada do usuário em SQL ou JPQL
Montar consulta com `+` ou `String.format` usando valor que vem de fora (parâmetro do controller, campo do DTO) permite SQL injection: o atacante lê ou apaga dados. Use parâmetros nomeados ou posicionais.
Errado: `em.createQuery("select p from Pedido p where p.cliente = '" + nome + "'")` ou `jdbcTemplate.query("... where id = " + id, ...)`
Certo: `em.createQuery("select p from Pedido p where p.cliente = :nome").setParameter("nome", nome)` ou `jdbcTemplate.query("... where id = ?", mapper, id)`.

## SQL-02 · Evitar N+1 ao percorrer relacionamentos lazy
Buscar uma lista de entidades e, dentro de um laço, acessar um relacionamento lazy (`pedido.getItens()`, `pedido.getCliente().getNome()`) dispara uma consulta por item: 1 consulta + N consultas. Com 1.000 pedidos são 1.001 idas ao banco.
Certo: usar `join fetch` na JPQL, `@EntityGraph` no repositório, ou uma projeção/DTO que já traga os dados.

## SQL-03 · Listagem precisa de paginação
`findAll()` em tabela que cresce (pedidos, clientes, logs) carrega tudo na memória e derruba a aplicação com o tempo. Endpoints de listagem devem receber `Pageable` e devolver `Page` ou `Slice`.

## SQL-04 · Entidade JPA não deve sair pelo controller
Devolver a entidade no JSON expõe campos internos (senha, dados de auditoria), pode disparar carregamento lazy fora da transação (LazyInitializationException) e acopla a API ao banco. Use um record de resposta (DTO).
