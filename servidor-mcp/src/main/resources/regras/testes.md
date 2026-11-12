# Testes

## TST-01 · Regra de negócio nova precisa de teste
Mudança em serviço com regra (cálculo, validação, mudança de status) deve vir com teste que cobre o caso feliz e pelo menos um caso de erro.

## TST-02 · Teste precisa verificar algo
Teste sem assert (ou que só chama o método) passa sempre e não protege nada. Verifique o resultado e os efeitos (com AssertJ: `assertThat(...)`).

## TST-03 · Teste não depende de horário nem de ordem
Teste que usa `LocalDate.now()` ou depende de outro teste rodar antes falha de vez em quando. Injete um Clock e deixe cada teste montar os próprios dados.
