package io.github.phb29.sentinela.revisao;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.phb29.sentinela.diff.Patch;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidadorDeComentariosTest {

    static final String ARQ = "src/main/java/demo/PedidoService.java";
    static final Map<String, Patch> PATCHES = Map.of(ARQ, Patch.ler("@@ -1,2 +1,3 @@\n a\n+b\n c"));

    static ComentarioDeRevisao comentario(int linha, String severidade, String titulo) {
        return new ComentarioDeRevisao(ARQ, linha, severidade, titulo,
                "Explicação longa o bastante para não ser vaga.", null, null);
    }

    @Test
    void aceitaComentarioValido() {
        var r = ValidadorDeComentarios.validar(List.of(comentario(2, "bloqueante", "Optional.get sem checar")), PATCHES);
        assertThat(r.aceitos()).hasSize(1);
        assertThat(r.recusados()).isEmpty();
    }

    @Test
    void recusaLinhaForaDoDiffEArquivoForaDoPr() {
        var foraDoDiff = comentario(40, "importante", "Linha que não está no diff");
        var outroArquivo = new ComentarioDeRevisao("Outro.java", 1, "importante", "Arquivo fora",
                "Explicação longa o bastante para não ser vaga.", null, null);
        var r = ValidadorDeComentarios.validar(List.of(foraDoDiff, outroArquivo), PATCHES);
        assertThat(r.aceitos()).isEmpty();
        assertThat(r.recusados()).extracting(ValidadorDeComentarios.Recusado::motivo)
                .anyMatch(m -> m.contains("não aparece no diff"))
                .anyMatch(m -> m.contains("não faz parte do PR"));
    }

    @Test
    void recusaVagoRepetidoESeveridadeInvalida() {
        var vago = new ComentarioDeRevisao(ARQ, 2, "sugestao", "Ruim", "Melhorar.", null, null);
        var a = comentario(2, "importante", "Mesmo título");
        var repetido = comentario(2, "importante", "Mesmo título");
        var severidadeErrada = comentario(1, "gravissimo", "Severidade que não existe");
        var r = ValidadorDeComentarios.validar(List.of(vago, a, repetido, severidadeErrada), PATCHES);
        assertThat(r.aceitos()).containsExactly(a);
        assertThat(r.recusados()).hasSize(3);
    }

    @Test
    void ordenaPorSeveridadeELimitaA25() {
        List<ComentarioDeRevisao> muitos = new ArrayList<>();
        for (int i = 0; i < 30; i++) muitos.add(comentario(1 + i % 3, "sugestao", "Sugestão número " + i));
        muitos.add(comentario(2, "bloqueante", "O mais grave de todos"));
        var r = ValidadorDeComentarios.validar(muitos, PATCHES);
        assertThat(r.aceitos()).hasSize(25);
        assertThat(r.aceitos().getFirst().severidade()).isEqualTo("bloqueante");
        assertThat(r.recusados()).hasSize(6);
    }

    @Test
    void severidadeAceitaAcentoEMaiuscula() {
        assertThat(Severidade.valida("Sugestão")).isTrue();
        assertThat(Severidade.de("BLOQUEANTE")).isEqualTo(Severidade.BLOQUEANTE);
        assertThat(Severidade.de("qualquer")).isEqualTo(Severidade.SUGESTAO);
    }
}
