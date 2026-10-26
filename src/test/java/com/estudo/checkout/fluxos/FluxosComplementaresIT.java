package com.estudo.checkout.fluxos;

import com.estudo.checkout.suporte.IntegracaoBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Casos de negócio que só a resposta v1 do prompt de cenários trouxe (o ID da v1 vai entre parênteses no nome),
 * reescritos no estilo da {@link IntegracaoBase} durante a curadoria. A v2 deixou as variantes de R1 de fora de
 * propósito, por serem "a mesma regra com outro campo".
 */
class FluxosComplementaresIT extends IntegracaoBase {

    @Test
    @DisplayName("(CF-04 da v1) [R2] Preço e total enviados pelo cliente são ignorados: vale o preço do Estoque")
    void precoEnviadoPeloClienteEIgnorado() {
        ESTOQUE.stubFor(get("/produtos/LIV-001").willReturn(okJson("""
                {"sku":"LIV-001","nome":"Livro Testes de Software","precoCentavos":8990,"disponivel":10}""")));
        ESTOQUE.stubFor(post("/reservas").willReturn(jsonResponse("""
                {"id":"res-1","pedidoId":"p-1","status":"RESERVADA","itens":[{"sku":"LIV-001","quantidade":2}]}""", 201)));
        ESTOQUE.stubFor(post("/reservas/res-1/confirmacao").willReturn(okJson("""
                {"id":"res-1","pedidoId":"p-1","status":"CONFIRMADA","itens":[{"sku":"LIV-001","quantidade":2}]}""")));
        PAGAMENTOS.stubFor(post("/pagamentos").willReturn(jsonResponse("""
                {"id":"pag-1","pedidoId":"p-1","valorCentavos":17980,"metodo":"PIX","status":"APROVADO","motivo":null}""", 201)));

        ResponseEntity<JsonNode> resposta = postarPedido("""
                {"clienteId":"cli-1","itens":[{"sku":"LIV-001","quantidade":2,"precoUnitarioCentavos":1}],
                 "metodoPagamento":"PIX","totalCentavos":2}""");

        assertThat(resposta.getStatusCode().value()).isEqualTo(201);
        assertThat(resposta.getBody().get("totalCentavos").asLong()).isEqualTo(17980);
        PAGAMENTOS.verify(1, postRequestedFor(urlEqualTo("/pagamentos"))
                .withRequestBody(matchingJsonPath("$.valorCentavos", equalTo("17980"))));
    }

    @ParameterizedTest(name = "corpo: {0}")
    @ValueSource(strings = {
            "{\"itens\":[{\"sku\":\"LIV-001\",\"quantidade\":1}],\"metodoPagamento\":\"PIX\"}",
            "{\"clienteId\":\" \",\"itens\":[{\"sku\":\"LIV-001\",\"quantidade\":1}],\"metodoPagamento\":\"PIX\"}",
            "{\"clienteId\":\"cli-1\",\"itens\":[],\"metodoPagamento\":\"PIX\"}",
            "{\"clienteId\":\"cli-1\",\"itens\":[{\"sku\":\"\",\"quantidade\":1}],\"metodoPagamento\":\"PIX\"}",
            "{\"clienteId\":\"cli-1\",\"itens\":[{\"sku\":\"LIV-001\",\"quantidade\":0}],\"metodoPagamento\":\"PIX\"}",
            "{\"clienteId\":\"cli-1\",\"itens\":[{\"sku\":\"LIV-001\",\"quantidade\":1.5}],\"metodoPagamento\":\"PIX\"}",
            "{\"clienteId\":\"cli-1\",\"itens\":[{\"sku\":\"LIV-001\",\"quantidade\":1}],\"metodoPagamento\":\"BOLETO\"}",
            "{\"clienteId\":\"cli-1\",\"itens\":[{\"sku\":\"LIV-001\""})
    @DisplayName("(VAL-01 e VAL-03 da v1) [R1] Entrada inválida: 400 DADOS_INVALIDOS e nenhuma chamada externa")
    void entradaInvalida(String corpo) {
        ResponseEntity<JsonNode> resposta = postarPedido(corpo);

        assertThat(resposta.getStatusCode().value()).isEqualTo(400);
        assertThat(resposta.getBody().get("codigo").asText()).isEqualTo("DADOS_INVALIDOS");
        ESTOQUE.verify(0, anyRequestedFor(anyUrl()));
        PAGAMENTOS.verify(0, anyRequestedFor(anyUrl()));
    }
}
