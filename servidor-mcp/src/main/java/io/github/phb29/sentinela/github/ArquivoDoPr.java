package io.github.phb29.sentinela.github;

import io.github.phb29.sentinela.diff.Patch;

/**
 * Um arquivo alterado no PR.
 *
 * @param status "added", "modified", "removed" ou "renamed" (valores do GitHub)
 * @param patch  trecho do diff; vem nulo para arquivo binário ou grande demais
 */
public record ArquivoDoPr(String caminho, String status, int adicoes, int remocoes, String patch) {

    public Patch patchLido() {
        return Patch.ler(patch);
    }
}
