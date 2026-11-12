# REST e validação

## API-01 · Validar a entrada com Bean Validation
Corpo de requisição deve ter `@Valid` no controller e anotações no DTO (`@NotBlank`, `@Positive`, `@Email`, `@Size`). Sem validação, dado inválido chega ao banco ou estoura erro 500.

## API-02 · Usar o status HTTP correto
Criação devolve 201 Created (com Location), remoção 204 No Content, recurso inexistente 404, conflito 409, entrada inválida 400. Não devolver 200 com `{"erro": ...}` no corpo.

## API-03 · Injeção por construtor, não por campo
Prefira injeção pelo construtor (campos final) em vez de `@Autowired` em campo: deixa as dependências explícitas, facilita teste sem Spring e evita objeto pela metade.
