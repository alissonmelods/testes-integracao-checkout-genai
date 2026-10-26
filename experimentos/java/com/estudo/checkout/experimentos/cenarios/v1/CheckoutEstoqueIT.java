package com.estudo.checkout.experimentos.cenarios.v1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Checkout: cenários do Estoque")
class CheckoutEstoqueIT extends IntegracaoBase {

    private static final String PEDIDO_COM_DOIS_ITENS = """
            {"clienteId":"cli-1","metodoPagamento":"PIX","itens":[
              {"sku":"SKU-1","quantidade":1},{"sku":"SKU-2","quantidade":1}]}""";

    @Test
    @DisplayName("ES-01: SKU inexistente na consulta de preço -> 422, sem reservar nem cobrar")
    void skuInexistenteNaConsultaDePreco() {
        estoqueProdutoComResposta("SKU-1", aResponse().withStatus(404));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(422);
        assertThat(resposta.texto("$.status")).isEqualTo("PRODUTO_INEXISTENTE");
        assertThat(resposta.texto("$.motivo")).contains("SKU-1");
        assertThat(resposta.texto("$.reservaId")).isNull();
        assertThat(resposta.texto("$.pagamentoId")).isNull();
        verificarReservasCriadas(0);
        verificarCobrancas(0);
    }

    @Test
    @DisplayName("ES-02: segundo item inexistente -> 422, preços parciais, total nulo e nada reservado")
    void segundoItemInexistente() {
        estoqueProduto("SKU-1", 1000);
        estoqueProdutoComResposta("SKU-2", aResponse().withStatus(404));

        Resposta resposta = postarPedido(PEDIDO_COM_DOIS_ITENS);

        assertThat(resposta.status()).isEqualTo(422);
        assertThat(resposta.texto("$.status")).isEqualTo("PRODUTO_INEXISTENTE");
        assertThat(resposta.texto("$.motivo")).contains("SKU-2");
        assertThat(resposta.numero("$.itens[0].precoUnitarioCentavos")).isEqualTo(1000L);
        assertThat(resposta.numero("$.itens[1].precoUnitarioCentavos")).isNull();
        assertThat(resposta.numero("$.totalCentavos")).isNull();
        verificarReservasCriadas(0);
        verificarCobrancas(0);
    }

    @Test
    @DisplayName("ES-03: sem estoque -> 409, sem cobrar e sem liberar (nada foi reservado)")
    void semEstoque() {
        estoqueProduto("SKU-1", 1000);
        estoqueReservaComResposta(jsonComStatus(409, """
                {"codigo":"ESTOQUE_INSUFICIENTE","mensagem":"Estoque insuficiente","sku":"SKU-1","disponivel":1}"""));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(409);
        assertThat(resposta.texto("$.status")).isEqualTo("SEM_ESTOQUE");
        assertThat(resposta.texto("$.motivo")).contains("SKU-1").contains("disponível: 1");
        assertThat(resposta.numero("$.totalCentavos")).isEqualTo(2000L);
        assertThat(resposta.texto("$.reservaId")).isNull();
        verificarReservasCriadas(1);
        verificarCobrancas(0);
        verificarNenhumaLiberacao();
    }

    static Stream<Arguments> corposDeErroIlegiveis() {
        return Stream.of(
                arguments("corpo vazio", "application/json", ""),
                arguments("JSON truncado", "application/json", "{\"codigo\":\"ESTOQUE_INSU"),
                arguments("HTML de um gateway", "text/html", "<html><body>Conflict</body></html>"));
    }

    @ParameterizedTest(name = "409 com {0}")
    @MethodSource("corposDeErroIlegiveis")
    @DisplayName("ES-04: 409 com corpo de erro ilegível continua sendo SEM_ESTOQUE")
    void semEstoqueComCorpoDeErroIlegivel(String caso, String tipoDoConteudo, String corpo) {
        estoqueProduto("SKU-1", 1000);
        estoqueReservaComResposta(aResponse().withStatus(409)
                .withHeader("Content-Type", tipoDoConteudo).withBody(corpo));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(409);
        assertThat(resposta.texto("$.status")).isEqualTo("SEM_ESTOQUE");
        verificarCobrancas(0);
    }

    @Test
    @DisplayName("ES-05: reserva responde 404 -> 422 com o SKU informado pelo Estoque")
    void skuInexistenteNaReserva() {
        estoqueProduto("SKU-1", 1000);
        estoqueReservaComResposta(jsonComStatus(404, """
                {"codigo":"PRODUTO_INEXISTENTE","mensagem":"SKU removido do catálogo","sku":"SKU-1"}"""));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(422);
        assertThat(resposta.texto("$.status")).isEqualTo("PRODUTO_INEXISTENTE");
        assertThat(resposta.texto("$.motivo")).contains("SKU-1");
        verificarCobrancas(0);
        verificarNenhumaLiberacao();
    }

    @ParameterizedTest(name = "HTTP {0} na consulta de preço")
    @ValueSource(ints = {400, 401, 403, 500, 502, 503})
    @DisplayName("ES-06: erro HTTP na consulta de preço -> 503, sem retentativa, sem reservar nem cobrar")
    void erroHttpNaConsultaDePreco(int statusHttp) {
        estoqueProdutoComResposta("SKU-1", aResponse().withStatus(statusHttp));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(503);
        assertThat(resposta.texto("$.status")).isEqualTo("FALHA_INTEGRACAO");
        assertThat(resposta.texto("$.motivo")).startsWith("estoque:");
        estoque.verify(1, getRequestedFor(urlEqualTo("/produtos/SKU-1")));
        verificarReservasCriadas(0);
        verificarCobrancas(0);
    }

    static Stream<Arguments> produtosForaDoContrato() {
        return Stream.of(
                arguments("sem precoCentavos", "{\"sku\":\"SKU-1\"}"),
                arguments("precoCentavos nulo", "{\"sku\":\"SKU-1\",\"precoCentavos\":null}"),
                arguments("precoCentavos negativo", "{\"sku\":\"SKU-1\",\"precoCentavos\":-1}"),
                arguments("precoCentavos com casa decimal (89.9)", "{\"sku\":\"SKU-1\",\"precoCentavos\":89.9}"),
                arguments("precoCentavos não numérico", "{\"sku\":\"SKU-1\",\"precoCentavos\":\"abc\"}"),
                arguments("corpo que não é JSON", "isto não é JSON"),
                arguments("corpo vazio", ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("produtosForaDoContrato")
    @DisplayName("ES-07: produto fora do contrato -> 503, sem reservar nem cobrar")
    void produtoForaDoContrato(String caso, String corpo) {
        estoqueProdutoComResposta("SKU-1", jsonComStatus(200, corpo));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(503);
        assertThat(resposta.texto("$.status")).isEqualTo("FALHA_INTEGRACAO");
        verificarReservasCriadas(0);
        verificarCobrancas(0);
    }

    @Test
    @DisplayName("ES-08: timeout na consulta de preço -> 503, uma única chamada")
    void timeoutNaConsultaDePreco() {
        estoqueProdutoComResposta("SKU-1",
                jsonComStatus(200, "{\"sku\":\"SKU-1\",\"precoCentavos\":1000}").withFixedDelay(ATRASO_MS));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(503);
        assertThat(resposta.texto("$.status")).isEqualTo("FALHA_INTEGRACAO");
        estoque.verify(1, getRequestedFor(urlEqualTo("/produtos/SKU-1")));
        verificarReservasCriadas(0);
        verificarCobrancas(0);
    }

    @ParameterizedTest(name = "HTTP {0} ao reservar")
    @ValueSource(ints = {400, 422, 500, 503})
    @DisplayName("ES-09: erro HTTP na reserva -> 503, sem retentativa, sem cobrar e sem liberar")
    void erroHttpNaReserva(int statusHttp) {
        estoqueProduto("SKU-1", 1000);
        estoqueReservaComResposta(aResponse().withStatus(statusHttp));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(503);
        assertThat(resposta.texto("$.status")).isEqualTo("FALHA_INTEGRACAO");
        assertThat(resposta.texto("$.motivo")).startsWith("estoque:");
        assertThat(resposta.texto("$.reservaId")).isNull();
        verificarReservasCriadas(1);
        verificarCobrancas(0);
        verificarNenhumaLiberacao();
    }

    @Test
    @DisplayName("ES-10: timeout na reserva -> 503, uma única chamada (reserva não é idempotente) e sem cobrança")
    void timeoutNaReserva() {
        estoqueProduto("SKU-1", 1000);
        estoqueReservaComResposta(
                jsonComStatus(201, "{\"id\":\"res-1\",\"status\":\"RESERVADA\"}").withFixedDelay(ATRASO_MS));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(503);
        assertThat(resposta.texto("$.status")).isEqualTo("FALHA_INTEGRACAO");
        assertThat(resposta.texto("$.reservaId")).isNull();
        verificarReservasCriadas(1);
        verificarCobrancas(0);
        verificarNenhumaLiberacao();
    }

    static Stream<Arguments> reservasForaDoContrato() {
        return Stream.of(
                arguments("sem id", "{\"status\":\"RESERVADA\"}"),
                arguments("com id nulo", "{\"id\":null,\"status\":\"RESERVADA\"}"),
                arguments("sem corpo", ""));
    }

    @ParameterizedTest(name = "reserva 201 {0}")
    @MethodSource("reservasForaDoContrato")
    @DisplayName("ES-11: reserva fora do contrato -> 503 e sem cobrança")
    void reservaForaDoContrato(String caso, String corpo) {
        estoqueProduto("SKU-1", 1000);
        estoqueReservaComResposta(jsonComStatus(201, corpo));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(503);
        assertThat(resposta.texto("$.status")).isEqualTo("FALHA_INTEGRACAO");
        assertThat(resposta.texto("$.reservaId")).isNull();
        verificarCobrancas(0);
        verificarNenhumaLiberacao();
    }
}
