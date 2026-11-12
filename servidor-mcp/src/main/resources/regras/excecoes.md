# Exceções

## EXC-01 · Não engolir exceção
`catch (Exception e) { }` ou um catch que só faz `e.printStackTrace()` esconde o erro: a operação falha em silêncio e ninguém descobre. Trate de verdade, registre com o logger (`log.error("...", e)`) e relance, ou converta em exceção de negócio.

## EXC-02 · Capturar a exceção mais específica
`catch (Exception e)` pega também erros de programação (NullPointerException, IllegalStateException) que deveriam estourar. Capture o tipo que você sabe tratar (IOException, DataIntegrityViolationException).

## EXC-03 · Erros de negócio viram resposta HTTP correta
Use um `@RestControllerAdvice` com `@ExceptionHandler` para transformar exceções de negócio em 400/404/409 com corpo padronizado (ProblemDetail). Não devolva stack trace para o cliente.
