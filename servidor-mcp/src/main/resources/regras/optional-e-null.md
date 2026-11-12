# Optional e null

## OPT-01 · Não chamar Optional.get() sem verificar
`repository.findById(id).get()` lança NoSuchElementException quando o registro não existe, o que vira erro 500 para o cliente. Use `orElseThrow(() -> new RecursoNaoEncontradoException(...))` para devolver 404, ou `orElse`/`map`.

## OPT-02 · Optional não é campo nem parâmetro
Optional foi feito para retorno de método. Como campo de entidade ou DTO ele não é serializável de forma previsível e como parâmetro obriga quem chama a embrulhar o valor. Use null documentado ou sobrecarga de método.

## OPT-03 · Comparar String com equals, nunca com ==
`==` compara referência, não conteúdo: `status == "PAGO"` pode dar false mesmo com o texto igual. Use `"PAGO".equals(status)` (seguro contra null) ou, melhor, um enum.
