package io.github.phb29.sentinela.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Lê os relatórios XML que o Maven Surefire grava em target/surefire-reports/TEST-*.xml
 * e resume: quantos testes rodaram, quantos falharam e por quê.
 *
 * <p>Segurança: esses XML são gerados pelo código do PR, que pode ter sido escrito por qualquer pessoa.
 * Um XML malicioso pode tentar ler arquivos da máquina com "entidades externas" (ataque XXE).
 * Por isso o leitor de XML desliga DOCTYPE e entidades externas antes de ler.
 */
public final class RelatorioSurefire {

    /** Uma falha de teste: classe, método e a mensagem (cortada). */
    public record Falha(String classe, String metodo, String tipo, String mensagem) {}

    public record Resumo(int testes, int falhas, int erros, int ignorados, List<Falha> detalhes) {
        public boolean passou() {
            return testes > 0 && falhas == 0 && erros == 0;
        }
    }

    private RelatorioSurefire() {}

    public static Resumo ler(Path pastaRelatorios) {
        int testes = 0, falhas = 0, erros = 0, ignorados = 0;
        List<Falha> detalhes = new ArrayList<>();
        if (!Files.isDirectory(pastaRelatorios)) return new Resumo(0, 0, 0, 0, detalhes);

        List<Path> arquivos;
        try (Stream<Path> s = Files.list(pastaRelatorios)) {
            arquivos = s.filter(p -> p.getFileName().toString().matches("TEST-.*\\.xml")).sorted().toList();
        } catch (IOException e) {
            return new Resumo(0, 0, 0, 0, detalhes);
        }
        for (Path arquivo : arquivos) {
            try (InputStream in = Files.newInputStream(arquivo)) {
                Document doc = leitorSeguro().parse(in);
                Element suite = doc.getDocumentElement();
                testes += inteiro(suite, "tests");
                falhas += inteiro(suite, "failures");
                erros += inteiro(suite, "errors");
                ignorados += inteiro(suite, "skipped");
                NodeList casos = suite.getElementsByTagName("testcase");
                for (int i = 0; i < casos.getLength() && detalhes.size() < 20; i++) {
                    Element caso = (Element) casos.item(i);
                    for (String tag : List.of("failure", "error")) {
                        NodeList problemas = caso.getElementsByTagName(tag);
                        if (problemas.getLength() > 0) {
                            Element p = (Element) problemas.item(0);
                            detalhes.add(new Falha(
                                    caso.getAttribute("classname"),
                                    caso.getAttribute("name"),
                                    p.getAttribute("type"),
                                    cortar(p.getAttribute("message"), 400)));
                        }
                    }
                }
            } catch (Exception e) {
                detalhes.add(new Falha(arquivo.getFileName().toString(), "-", "relatorio-ilegivel", cortar(e.getMessage(), 200)));
            }
        }
        return new Resumo(testes, falhas, erros, ignorados, detalhes);
    }

    /** Leitor de XML com proteção contra XXE (OWASP: desligar DOCTYPE e entidades externas). */
    static DocumentBuilder leitorSeguro() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }

    private static int inteiro(Element e, String atributo) {
        try {
            return Integer.parseInt(e.getAttribute(atributo));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String cortar(String texto, int max) {
        if (texto == null) return "";
        return texto.length() <= max ? texto : texto.substring(0, max) + "...";
    }
}
