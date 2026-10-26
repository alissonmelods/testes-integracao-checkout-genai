package com.estudo.checkout.contrato;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regras de contratos/estoque-api.yaml (1.4.0) e contratos/pagamentos-api.yaml (2.1.0) transcritas para asserções
 * sobre o que o serviço de Pedidos ENVIA. Se o YAML mudar, esta classe precisa mudar junto.
 */
final class ContratoRegras {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ContratoRegras() {
    }

    static JsonNode lerJson(String texto) {
        try {
            return JSON.readTree(texto);
        } catch (IOException e) {
            throw new UncheckedIOException("corpo não é um JSON válido: " + texto, e);
        }
    }

    static Set<String> campos(JsonNode objeto) {
        Set<String> nomes = new TreeSet<>();
        objeto.fieldNames().forEachRemaining(nomes::add);
        return nomes;
    }

    static void validarTrafego(WireMockServer estoque, WireMockServer pagamentos) {
        for (ServeEvent evento : estoque.getAllServeEvents()) {
            validarRequisicaoAoEstoque(evento.getRequest());
        }
        for (ServeEvent evento : pagamentos.getAllServeEvents()) {
            validarRequisicaoAoPagamentos(evento.getRequest());
        }
    }

    private static void validarRequisicaoAoEstoque(LoggedRequest requisicao) {
        String operacao = operacao(requisicao);
        assertThat(operacao)
                .as("o Pedidos só pode chamar operações que existem no contrato de Estoque")
                .matches("GET /produtos/[^/]+|POST /reservas|POST /reservas/[^/]+/confirmacao|DELETE /reservas/[^/]+");
        if (operacao.equals("POST /reservas")) {
            validarNovaReserva(requisicao);
        }
    }

    /** NovaReserva e ItemReserva: additionalProperties false, pedidoId/sku minLength 1, itens minItems 1, quantidade minimum 1. */
    private static void validarNovaReserva(LoggedRequest requisicao) {
        assertThat(requisicao.getHeader("Content-Type")).as("Content-Type de POST /reservas").contains("application/json");
        JsonNode corpo = lerJson(requisicao.getBodyAsString());
        assertThat(campos(corpo)).as("campos de NovaReserva (additionalProperties: false)")
                .containsExactlyInAnyOrder("pedidoId", "itens");
        assertThat(textoNaoVazio(corpo.get("pedidoId"))).as("pedidoId: string com minLength 1").isTrue();
        JsonNode itens = corpo.get("itens");
        assertThat(itens.isArray() && itens.size() >= 1).as("itens: array com minItems 1").isTrue();
        for (JsonNode item : itens) {
            assertThat(campos(item)).as("campos de ItemReserva (additionalProperties: false)")
                    .containsExactlyInAnyOrder("sku", "quantidade");
            assertThat(textoNaoVazio(item.get("sku"))).as("sku: string com minLength 1").isTrue();
            assertThat(inteiroMinimo(item.get("quantidade"), 1)).as("quantidade: inteiro com minimum 1").isTrue();
        }
    }

    private static void validarRequisicaoAoPagamentos(LoggedRequest requisicao) {
        assertThat(operacao(requisicao))
                .as("o Pedidos só usa POST /pagamentos (GET /pagamentos/{id} não é usado por ele)")
                .isEqualTo("POST /pagamentos");
        assertThat(requisicao.getHeader("Content-Type")).as("Content-Type de POST /pagamentos").contains("application/json");

        String chave = requisicao.getHeader("Idempotency-Key");
        assertThat(chave).as("header Idempotency-Key é obrigatório").isNotNull();
        assertThat(chave.length()).as("Idempotency-Key: de 1 a 64 caracteres").isBetween(1, 64);

        JsonNode corpo = lerJson(requisicao.getBodyAsString());
        assertThat(campos(corpo)).as("campos de NovoPagamento (additionalProperties: false)")
                .contains("pedidoId", "valorCentavos", "metodo")
                .isSubsetOf("pedidoId", "valorCentavos", "metodo", "tokenCartao");
        assertThat(textoNaoVazio(corpo.get("pedidoId"))).as("pedidoId: string com minLength 1").isTrue();
        assertThat(inteiroMinimo(corpo.get("valorCentavos"), 1))
                .as("valorCentavos: inteiro int64 com minimum 1 (veio %s)", corpo.get("valorCentavos")).isTrue();

        String metodo = corpo.get("metodo").asText();
        assertThat(metodo).as("metodo: PIX ou CARTAO").isIn("PIX", "CARTAO");
        boolean temToken = corpo.has("tokenCartao");
        if (temToken) {
            assertThat(textoNaoVazio(corpo.get("tokenCartao")))
                    .as("tokenCartao: string com minLength 1 (null não é aceito)").isTrue();
        }
        if (metodo.equals("CARTAO")) {
            assertThat(temToken).as("tokenCartao é obrigatório quando metodo = CARTAO").isTrue();
        } else {
            assertThat(temToken).as("tokenCartao não pode ser enviado quando metodo = PIX").isFalse();
        }
    }

    private static String operacao(LoggedRequest requisicao) {
        String url = requisicao.getUrl();
        int query = url.indexOf('?');
        return requisicao.getMethod() + " " + (query >= 0 ? url.substring(0, query) : url);
    }

    private static boolean textoNaoVazio(JsonNode no) {
        return no != null && no.isTextual() && !no.asText().isEmpty();
    }

    private static boolean inteiroMinimo(JsonNode no, long minimo) {
        return no != null && no.isIntegralNumber() && no.asLong() >= minimo;
    }
}
