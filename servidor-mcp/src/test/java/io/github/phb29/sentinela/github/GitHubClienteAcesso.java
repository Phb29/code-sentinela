package io.github.phb29.sentinela.github;

/** Dá aos testes de outros pacotes acesso ao método validar (que é package-private). */
public final class GitHubClienteAcesso {

    private GitHubClienteAcesso() {}

    public static String validar(String repo) {
        return GitHubCliente.validar(repo);
    }
}
