# Logs e segurança

## SEG-01 · Não registrar dado sensível em log
Senha, token, número de cartão, CPF completo e dados de saúde não podem ir para o log. Logs são lidos por muita gente e guardados por muito tempo (LGPD).

## SEG-02 · Segredo não fica no código
Senha de banco, chave de API e token vêm de variável de ambiente ou cofre de segredos, nunca escritos no código ou no application.yml versionado.

## SEG-03 · Log com placeholder, não com concatenação
Use `log.info("Pedido {} criado", id)` em vez de `log.info("Pedido " + id + " criado")`: a concatenação roda mesmo com o nível desligado e dificulta a busca nos logs.
