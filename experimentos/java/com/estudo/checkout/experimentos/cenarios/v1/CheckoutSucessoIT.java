package com.estudo.checkout.experimentos.cenarios.v1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Checkout: caminho feliz")
class CheckoutSucessoIT extends IntegracaoBase {

    @Test
    @DisplayName("CF-01: PIX com um item confirma o pedido, cobra o total e confirma a reserva")
    void pixComUmItemConfirmaOPedido() {
        prepararCaminhoFeliz();

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.texto("$.status")).isEqualTo("CONFIRMADO");
        assertThat(resposta.texto("$.motivo")).isNull();
        assertThat(resposta.texto("$.clienteId")).isEqualTo("cli-1");
        assertThat(resposta.texto("$.metodoPagamento")).isEqualTo("PIX");
        assertThat(resposta.numero("$.totalCentavos")).isEqualTo(2000L);
        assertThat(resposta.texto("$.itens[0].sku")).isEqualTo("SKU-1");
        assertThat(resposta.numero("$.itens[0].quantidade")).isEqualTo(2L);
        assertThat(resposta.numero("$.itens[0].precoUnitarioCentavos")).isEqualTo(1000L);
        assertThat(resposta.texto("$.reservaId")).isEqualTo("res-1");
        assertThat(resposta.texto("$.pagamentoId")).isEqualTo("pag-1");

        String pedidoId = resposta.texto("$.id");
        assertThat(pedidoId).isNotBlank();
        estoque.verify(1, getRequestedFor(urlEqualTo("/produtos/SKU-1")));
        estoque.verify(1, postRequestedFor(urlEqualTo("/reservas"))
                .withRequestBody(equalToJson("""
                        {"pedidoId":"%s","itens":[{"sku":"SKU-1","quantidade":2}]}""".formatted(pedidoId))));
        pagamentos.verify(1, postRequestedFor(urlEqualTo("/pagamentos"))
                .withHeader("Idempotency-Key", equalTo("pedido-" + pedidoId))
                .withRequestBody(equalToJson("""
                        {"pedidoId":"%s","valorCentavos":2000,"metodo":"PIX"}""".formatted(pedidoId))));
        verificarConfirmacoes("res-1", 1);
        verificarNenhumaLiberacao();
    }

    @Test
    @DisplayName("CF-02: CARTAO envia o tokenCartao ao Pagamentos")
    void cartaoEnviaOTokenAoPagamentos() {
        prepararCaminhoFeliz();

        Resposta resposta = postarPedido("""
                {"clienteId":"cli-1","metodoPagamento":"CARTAO","tokenCartao":"tok_abc123",
                 "itens":[{"sku":"SKU-1","quantidade":2}]}""");

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.texto("$.status")).isEqualTo("CONFIRMADO");
        assertThat(resposta.texto("$.metodoPagamento")).isEqualTo("CARTAO");
        pagamentos.verify(1, postRequestedFor(urlEqualTo("/pagamentos"))
                .withRequestBody(equalToJson("""
                        {"pedidoId":"%s","valorCentavos":2000,"metodo":"CARTAO","tokenCartao":"tok_abc123"}"""
                        .formatted(resposta.texto("$.id")))));
    }

    @Test
    @DisplayName("CF-03: vários itens somam preço x quantidade e vão na mesma reserva")
    void variosItensSomamPrecoVezesQuantidade() {
        estoqueProduto("SKU-1", 1000);
        estoqueProduto("SKU-2", 550);
        estoqueReserva("res-1");
        pagamentoResponde(respostaAprovada("pag-1"));
        estoqueConfirmacaoOk("res-1");

        Resposta resposta = postarPedido("""
                {"clienteId":"cli-1","metodoPagamento":"PIX","itens":[
                  {"sku":"SKU-1","quantidade":2},{"sku":"SKU-2","quantidade":3}]}""");

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.numero("$.totalCentavos")).isEqualTo(3650L); // 2 x 1000 + 3 x 550
        assertThat(resposta.numero("$.itens[1].precoUnitarioCentavos")).isEqualTo(550L);

        String pedidoId = resposta.texto("$.id");
        estoque.verify(1, getRequestedFor(urlEqualTo("/produtos/SKU-1")));
        estoque.verify(1, getRequestedFor(urlEqualTo("/produtos/SKU-2")));
        estoque.verify(1, postRequestedFor(urlEqualTo("/reservas"))
                .withRequestBody(equalToJson("""
                        {"pedidoId":"%s","itens":[{"sku":"SKU-1","quantidade":2},{"sku":"SKU-2","quantidade":3}]}"""
                        .formatted(pedidoId))));
        pagamentos.verify(1, postRequestedFor(urlEqualTo("/pagamentos"))
                .withRequestBody(equalToJson("""
                        {"pedidoId":"%s","valorCentavos":3650,"metodo":"PIX"}""".formatted(pedidoId))));
    }

    @Test
    @DisplayName("CF-04: o preço vem do Estoque; preço e total enviados pelo cliente são ignorados")
    void precoEnviadoPeloClienteEIgnorado() {
        prepararCaminhoFeliz();

        Resposta resposta = postarPedido("""
                {"clienteId":"cli-1","metodoPagamento":"PIX","totalCentavos":1,
                 "itens":[{"sku":"SKU-1","quantidade":2,"precoUnitarioCentavos":1}]}""");

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.numero("$.totalCentavos")).isEqualTo(2000L);
        assertThat(resposta.numero("$.itens[0].precoUnitarioCentavos")).isEqualTo(1000L);
        pagamentos.verify(1, postRequestedFor(urlEqualTo("/pagamentos"))
                .withRequestBody(equalToJson("""
                        {"pedidoId":"%s","valorCentavos":2000,"metodo":"PIX"}"""
                        .formatted(resposta.texto("$.id")))));
    }

    @Test
    @DisplayName("CF-05: campos extras nas respostas do Estoque e do Pagamentos são ignorados")
    void camposExtrasNasRespostasSaoIgnorados() {
        estoqueProdutoComResposta("SKU-1", jsonComStatus(200,
                "{\"sku\":\"SKU-1\",\"nome\":\"Camiseta\",\"precoCentavos\":1000,\"disponivel\":50}"));
        estoqueReservaComResposta(jsonComStatus(201,
                "{\"id\":\"res-1\",\"status\":\"RESERVADA\",\"expiraEm\":\"2030-01-01T00:00:00Z\"}"));
        pagamentoResponde(jsonComStatus(201,
                "{\"id\":\"pag-1\",\"status\":\"APROVADO\",\"adquirente\":\"X\",\"valorCentavos\":2000}"));
        estoqueConfirmacaoOk("res-1");

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.texto("$.status")).isEqualTo("CONFIRMADO");
        assertThat(resposta.numero("$.totalCentavos")).isEqualTo(2000L);
    }

    @ParameterizedTest(name = "quantidade {0}")
    @ValueSource(ints = {1, 99})
    @DisplayName("CF-06: quantidades-limite (1 e 99) são aceitas")
    void quantidadesLimiteSaoAceitas(int quantidade) {
        prepararCaminhoFeliz();

        Resposta resposta = postarPedido("""
                {"clienteId":"cli-1","metodoPagamento":"PIX","itens":[{"sku":"SKU-1","quantidade":%d}]}"""
                .formatted(quantidade));

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.numero("$.totalCentavos")).isEqualTo(1000L * quantidade);
    }

    @Test
    @DisplayName("CF-07: pedido com exatamente 20 itens (limite) é aceito")
    void pedidoComVinteItensEAceito() {
        estoqueTodosOsProdutos(100);
        estoqueReserva("res-1");
        pagamentoResponde(respostaAprovada("pag-1"));
        estoqueConfirmacaoOk("res-1");

        Resposta resposta = postarPedido(pedidoComItens(20));

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.numero("$.totalCentavos")).isEqualTo(2000L);
        assertThat(resposta.lista("$.itens[*].sku")).hasSize(20);
        estoque.verify(20, getRequestedFor(urlPathMatching("/produtos/.+")));
    }
}
