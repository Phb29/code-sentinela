package io.github.phb29.sentinela.github;

/**
 * Dados de um pull request que importam para a revisão.
 *
 * @param repo      "dono/repositorio", por exemplo "Phb29/sentinela-demo"
 * @param headSha   commit mais novo do PR: a revisão é publicada "em cima" dele
 * @param urlClone  endereço para clonar o repositório base (de onde baixamos o PR para testar)
 */
public record PullRequest(
        String repo,
        int numero,
        String titulo,
        String descricao,
        String autor,
        String baseRef,
        String headSha,
        String urlClone) {}
