# Transações

## TX-01 · Método que grava em mais de uma tabela precisa de @Transactional
Quando um método de serviço faz dois ou mais save, update ou delete (por exemplo grava o pedido e baixa o estoque), ele precisa estar em uma transação. Sem @Transactional, se o segundo passo falhar o primeiro já foi gravado e o banco fica inconsistente.
Errado: `public void finalizar(Pedido p) { pedidoRepository.save(p); estoqueRepository.baixar(p.itens()); }`
Certo: anotar o método de serviço com `@Transactional` (org.springframework.transaction.annotation).

## TX-02 · @Transactional não funciona em chamada interna nem em método privado
O Spring aplica a transação por proxy. Chamar `this.metodoTransacional()` de dentro da mesma classe, ou anotar método private, faz a anotação ser ignorada sem aviso. Mova o método para outro bean ou anote o método público chamado de fora.

## TX-03 · Consulta sem escrita usa @Transactional(readOnly = true)
Métodos que só leem devem usar `@Transactional(readOnly = true)`: o Hibernate pula a verificação de alterações (dirty checking) e o banco pode otimizar. Não use readOnly em método que grava.
