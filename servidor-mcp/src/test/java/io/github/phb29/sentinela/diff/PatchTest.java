package io.github.phb29.sentinela.diff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PatchTest {

    // Trecho real no formato que o GitHub devolve: começa na linha 10 do arquivo novo.
    static final String PATCH = """
            @@ -10,4 +10,5 @@ public class PedidoService {
                 public Pedido buscar(Long id) {
            -        return repo.findById(id).orElseThrow();
            +        Pedido p = repo.findById(id).get();
            +        return p;
                 }
             }
            @@ -30,2 +31,3 @@ public class PedidoService {
                 void outro() {
            +        log.info("x");
                 }""";

    @Test
    void numeraAsLinhasDoArquivoNovo() {
        Patch p = Patch.ler(PATCH);
        assertThat(p.linhasAdicionadas()).containsExactly(11, 12, 32);
        assertThat(p.podeComentar(10)).isTrue();   // contexto
        assertThat(p.podeComentar(11)).isTrue();   // adicionada
        assertThat(p.podeComentar(14)).isTrue();   // contexto "}" do fim do primeiro trecho
        assertThat(p.podeComentar(20)).isFalse();  // fora do diff
    }

    @Test
    void textoNumeradoMostraOnumeroAntesDaLinha() {
        String texto = Patch.ler(PATCH).comNumeros(100);
        assertThat(texto).contains("   11 +         Pedido p = repo.findById(id).get();");
        assertThat(texto).contains("     - |         return repo.findById(id).orElseThrow();");
    }

    @Test
    void cortaPatchGrande() {
        String texto = Patch.ler(PATCH).comNumeros(3);
        assertThat(texto).contains("linhas omitidas");
    }

    @Test
    void patchNuloViraPatchVazio() {
        Patch p = Patch.ler(null);
        assertThat(p.linhas()).isEmpty();
        assertThat(p.podeComentar(1)).isFalse();
    }

    @Test
    void ignoraAvisoDeFimDeArquivo() {
        Patch p = Patch.ler("@@ -1,1 +1,1 @@\n-a\n\\ No newline at end of file\n+b\n\\ No newline at end of file");
        assertThat(p.linhasAdicionadas()).containsExactly(1);
    }
}
