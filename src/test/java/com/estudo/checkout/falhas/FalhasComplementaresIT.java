package com.estudo.checkout.falhas;

import com.estudo.checkout.suporte.IntegracaoBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Casos que só a resposta v1 do prompt de falhas trouxe (o ID da v1 vai entre parênteses no nome), reescritos no
 * estilo da {@link IntegracaoBase} durante a curadoria. O resto da v1 já está coberto pela {@link FalhasComunicacaoIT}.
 * O X-01 veio do experimento do oráculo e achou um estouro de soma no total do pedido.
 */
class FalhasComplementaresIT extends IntegracaoBase {

    private static final String PEDIDO_PIX = """
            {"clienteId":"cli-1","itens":[{"sku":"LIV-001","quantidade":2}],"metodoPagamento":"PIX"}""";
    private static final String PRODUTO = """
            {"sku":"LIV-001","nome":"Livro Testes de Software","precoCentavos":8990,"disponivel":10}""";
    private static final String RESERVA = """
            {"id":"res-1","pedidoId":"p-1","status":"RESERVADA","itens":[{"sku":"LIV-001","quantidade":2}]}""";
    private static final String CORPO_SENSIVEL =
            "java.lang.IllegalStateException: senha=SEGREDO-123 em jdbc:postgresql://db-interno/pagamentos";

    @ParameterizedTest(name = "corpo do 409: [{0}]")
    @ValueSource(strings = {"", "<html>Conflict</html>", "{\"sku\":"})
    @DisplayName("(ER-06) [R4] 409 do Estoque com corpo ilegível continua sendo SEM_ESTOQUE, sem cobrança")
    void conflitoComCorpoIlegivel(String corpo) {
        ESTOQUE.stubFor(get("/produtos/LIV-001").willReturn(okJson(PRODUTO)));
        ESTOQUE.stubFor(post("/reservas").willReturn(aResponse()
                .withStatus(409).withHeader("Content-Type", "application/json").withBody(corpo)));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.getStatusCode().value()).isEqualTo(409);
        assertThat(resposta.getBody().get("status").asText()).isEqualTo("SEM_ESTOQUE");
        PAGAMENTOS.verify(0, postRequestedFor(urlEqualTo("/pagamentos")));
        ESTOQUE.verify(0, deleteRequestedFor(urlPathMatching("/reservas/.+")));
    }

    @Test
    @DisplayName("(ER-07) [R3] 404 do Estoque com página HTML na reserva continua sendo PRODUTO_INEXISTENTE, sem cobrança")
    void naoEncontradoComPaginaHtml() {
        ESTOQUE.stubFor(get("/produtos/LIV-001").willReturn(okJson(PRODUTO)));
        ESTOQUE.stubFor(post("/reservas").willReturn(aResponse()
                .withStatus(404).withHeader("Content-Type", "text/html").withBody("<html>Not Found</html>")));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.getStatusCode().value()).isEqualTo(422);
        assertThat(resposta.getBody().get("status").asText()).isEqualTo("PRODUTO_INEXISTENTE");
        PAGAMENTOS.verify(0, postRequestedFor(urlEqualTo("/pagamentos")));
    }

    @Test
    @DisplayName("(PN-03) [R9] Pagamentos devolve página HTML de proxy com 200: 503 pagamentos:, sem repetir e com a reserva liberada")
    void pagamentoComPaginaHtmlDeProxy() {
        estoqueSaudavel();
        PAGAMENTOS.stubFor(post("/pagamentos").willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "text/html").withBody("<html>Bad Gateway</html>")));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.getStatusCode().value()).isEqualTo(503);
        assertThat(resposta.getBody().get("motivo").asText()).startsWith("pagamentos:");
        PAGAMENTOS.verify(1, postRequestedFor(urlEqualTo("/pagamentos")));
        ESTOQUE.verify(1, deleteRequestedFor(urlEqualTo("/reservas/res-1")));
        ESTOQUE.verify(0, postRequestedFor(urlEqualTo("/reservas/res-1/confirmacao")));
    }

    @Test
    @DisplayName("(PR-07) [R5] Pedidos diferentes usam Idempotency-Key diferentes")
    void pedidosDiferentesUsamChavesDiferentes() {
        estoqueSaudavel();
        PAGAMENTOS.stubFor(post("/pagamentos").willReturn(jsonResponse("""
                {"codigo":"INDISPONIVEL","mensagem":"Serviço temporariamente indisponível"}""", 503)));

        String primeiro = postarPedido(PEDIDO_PIX).getBody().get("id").asText();
        String segundo = postarPedido(PEDIDO_PIX).getBody().get("id").asText();

        List<String> chaves = PAGAMENTOS.findAll(postRequestedFor(urlEqualTo("/pagamentos"))).stream()
                .map(requisicao -> requisicao.getHeader("Idempotency-Key"))
                .distinct()
                .toList();
        assertThat(chaves).containsExactlyInAnyOrder("pedido-" + primeiro, "pedido-" + segundo);
    }

    @Test
    @DisplayName("(SG-01) Erro do Estoque com detalhes internos no corpo não vaza para o cliente do checkout")
    void erroDoEstoqueNaoVaza() {
        ESTOQUE.stubFor(get("/produtos/LIV-001").willReturn(aResponse().withStatus(500).withBody(CORPO_SENSIVEL)));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.getStatusCode().value()).isEqualTo(503);
        assertThat(resposta.getBody().toString())
                .doesNotContain("SEGREDO-123", "db-interno", "IllegalStateException", "localhost:" + ESTOQUE.port());
    }

    @Test
    @DisplayName("(SG-02) Erro do Pagamentos com detalhes internos no corpo não vaza para o cliente do checkout")
    void erroDoPagamentosNaoVaza() {
        estoqueSaudavel();
        PAGAMENTOS.stubFor(post("/pagamentos").willReturn(aResponse().withStatus(500).withBody(CORPO_SENSIVEL)));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.getStatusCode().value()).isEqualTo(503);
        assertThat(resposta.getBody().toString())
                .doesNotContain("SEGREDO-123", "db-interno", "IllegalStateException", "localhost:" + PAGAMENTOS.port());
    }

    @Test
    @DisplayName("(X-01, experimento do oráculo) [R10] Preço absurdo do Estoque não estoura o total: 503 estoque:, sem reserva nem cobrança")
    void precoAbsurdoNaoEstouraOTotal() {
        // quantidade 2 x Long.MAX_VALUE estouraria para -2 e o pedido seria confirmado sem cobrança
        ESTOQUE.stubFor(get("/produtos/LIV-001").willReturn(okJson("""
                {"sku":"LIV-001","nome":"Livro Testes de Software","precoCentavos":9223372036854775807,"disponivel":10}""")));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.getStatusCode().value()).isEqualTo(503);
        assertThat(resposta.getBody().get("motivo").asText()).startsWith("estoque:");
        ESTOQUE.verify(0, postRequestedFor(urlEqualTo("/reservas")));
        PAGAMENTOS.verify(0, postRequestedFor(urlEqualTo("/pagamentos")));
    }

    private static void estoqueSaudavel() {
        ESTOQUE.stubFor(get("/produtos/LIV-001").willReturn(okJson(PRODUTO)));
        ESTOQUE.stubFor(post("/reservas").willReturn(jsonResponse(RESERVA, 201)));
        ESTOQUE.stubFor(delete("/reservas/res-1").willReturn(noContent()));
    }
}
