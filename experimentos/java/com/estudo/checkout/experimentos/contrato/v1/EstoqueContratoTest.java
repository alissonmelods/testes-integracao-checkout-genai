package com.estudo.checkout.experimentos.contrato.v1;

import com.estudo.checkout.integracao.FalhaIntegracaoException;
import com.estudo.checkout.integracao.estoque.ContratoEstoque;
import com.estudo.checkout.integracao.estoque.EstoqueClient;
import com.estudo.checkout.integracao.estoque.EstoqueInsuficienteException;
import com.estudo.checkout.integracao.estoque.ProdutoInexistenteException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EstoqueContratoTest extends ContratoBase {

    private static final String PEDIDO_ID = "7b0c2f6e-1d4a-4c55-9a0e-3f1f0f6b2a10";
    private static final List<ContratoEstoque.ItemReserva> ITENS =
            List.of(new ContratoEstoque.ItemReserva("LIV-001", 2));

    // Corpos baseados nos exemplos do próprio contrato (estoque-api.yaml)
    private static final String PRODUTO = """
            {"sku":"LIV-001","nome":"Livro Testes de Software","precoCentavos":8990,"disponivel":10}""";
    private static final String RESERVA = """
            {"id":"res-123","pedidoId":"7b0c2f6e-1d4a-4c55-9a0e-3f1f0f6b2a10","status":"RESERVADA","itens":[{"sku":"LIV-001","quantidade":2}]}""";
    private static final String RESERVA_CONFIRMADA = RESERVA.replace("RESERVADA", "CONFIRMADA");
    private static final String CORPO_NOVA_RESERVA = """
            {"pedidoId":"7b0c2f6e-1d4a-4c55-9a0e-3f1f0f6b2a10","itens":[{"sku":"LIV-001","quantidade":2}]}""";
    private static final String ERRO_PRODUTO_INEXISTENTE = """
            {"codigo":"PRODUTO_INEXISTENTE","mensagem":"Produto XYZ-999 nao encontrado","sku":"XYZ-999"}""";
    private static final String ERRO_ESTOQUE_INSUFICIENTE = """
            {"codigo":"ESTOQUE_INSUFICIENTE","mensagem":"Quantidade indisponivel para MOU-003","sku":"MOU-003","disponivel":1}""";
    private static final String ERRO_GENERICO = """
            {"codigo":"RESERVA_INEXISTENTE","mensagem":"erro de exemplo"}""";

    @Autowired
    private EstoqueClient client;

    // ------------------------------------------------------------------ GET /produtos/:sku

    @Test
    @DisplayName("GET /produtos/{sku} 200: lê precoCentavos e ignora campos extras (nome, disponivel)")
    void produtoEncontrado() {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/LIV-001")).willReturn(json(200, PRODUTO)));

        ContratoEstoque.Produto produto = client.consultarProduto("LIV-001");

        assertThat(produto.sku()).isEqualTo("LIV-001");
        assertThat(produto.precoCentavos()).isEqualTo(8990L);
        ESTOQUE.verify(1, getRequestedFor(urlPathEqualTo("/produtos/LIV-001")));
    }

    @Test
    @DisplayName("GET /produtos/{sku} 200: preço 0 é válido no contrato (minimum 0)")
    void produtoComPrecoZero() {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/BRD-000")).willReturn(json(200,
                "{\"sku\":\"BRD-000\",\"nome\":\"Brinde\",\"precoCentavos\":0,\"disponivel\":3}")));

        assertThat(client.consultarProduto("BRD-000").precoCentavos()).isZero();
    }

    @Test
    @DisplayName("GET /produtos/{sku} 200: precoCentavos é int64 e não pode estourar um int")
    void produtoComPrecoInt64() {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/CAR-777")).willReturn(json(200,
                "{\"sku\":\"CAR-777\",\"nome\":\"Carro\",\"precoCentavos\":9000000000,\"disponivel\":1}")));

        assertThat(client.consultarProduto("CAR-777").precoCentavos()).isEqualTo(9_000_000_000L);
    }

    @Test
    @DisplayName("GET /produtos/{sku} 404: SKU não cadastrado vira ProdutoInexistenteException")
    void produtoNaoCadastrado() {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/XYZ-999")).willReturn(json(404, ERRO_PRODUTO_INEXISTENTE)));

        assertThatThrownBy(() -> client.consultarProduto("XYZ-999"))
                .isInstanceOf(ProdutoInexistenteException.class)
                .hasMessageContaining("XYZ-999");
    }

    @Test
    @DisplayName("GET /produtos/{sku} 404 sem corpo continua sendo produto inexistente")
    void produtoNaoCadastradoSemCorpo() {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/XYZ-999")).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> client.consultarProduto("XYZ-999"))
                .isInstanceOf(ProdutoInexistenteException.class);
    }

    // Status que o contrato não prevê para esta operação: falha de integração, sem nova tentativa.
    @ParameterizedTest(name = "GET /produtos respondendo {0}")
    @ValueSource(ints = {400, 500, 502, 503})
    void produtoComStatusForaDoContrato(int status) {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/LIV-001")).willReturn(json(status, ERRO_GENERICO)));

        assertThatThrownBy(() -> client.consultarProduto("LIV-001"))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageStartingWith("estoque:");
        ESTOQUE.verify(1, getRequestedFor(urlPathEqualTo("/produtos/LIV-001")));
    }

    // 89.9: o application.yml proíbe truncar centavos fracionados em silêncio (accept-float-as-int: false).
    @ParameterizedTest(name = "precoCentavos = {0}")
    @ValueSource(strings = {"-1", "89.9", "null"})
    void produtoComPrecoForaDoContrato(String preco) {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/LIV-001")).willReturn(json(200,
                "{\"sku\":\"LIV-001\",\"nome\":\"Livro\",\"precoCentavos\":%s,\"disponivel\":1}".formatted(preco))));

        assertThatThrownBy(() -> client.consultarProduto("LIV-001"))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageStartingWith("estoque:");
    }

    @ParameterizedTest(name = "corpo 200 = [{0}]")
    @ValueSource(strings = {"{\"sku\":\"LIV-001\",\"nome\":\"Livro\",\"disponivel\":1}", ""})
    void produtoSemPrecoOuSemCorpo(String corpo) {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/LIV-001")).willReturn(json(200, corpo)));

        assertThatThrownBy(() -> client.consultarProduto("LIV-001"))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageContaining("fora do contrato");
    }

    @Test
    @DisplayName("GET /produtos/{sku}: timeout vira FalhaIntegracaoException, sem nova tentativa")
    void produtoComTimeout() {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/LIV-001"))
                .willReturn(json(200, PRODUTO).withFixedDelay(ATRASO_ACIMA_DO_TIMEOUT_MS)));

        assertThatThrownBy(() -> client.consultarProduto("LIV-001"))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageStartingWith("estoque:");
        ESTOQUE.verify(1, getRequestedFor(urlPathEqualTo("/produtos/LIV-001")));
    }

    // ------------------------------------------------------------------ POST /reservas

    @Test
    @DisplayName("POST /reservas 201: envia exatamente NovaReserva e devolve o id da reserva")
    void reservaCriada() {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(201, RESERVA)));

        ContratoEstoque.Reserva reserva = client.reservar(PEDIDO_ID, ITENS);

        assertThat(reserva.id()).isEqualTo("res-123");
        assertThat(reserva.status()).isEqualTo("RESERVADA");
        ESTOQUE.verify(1, postRequestedFor(urlPathEqualTo("/reservas"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson(CORPO_NOVA_RESERVA, false, false)));
    }

    @Test
    @DisplayName("POST /reservas 409: sem estoque (tudo ou nada) vira EstoqueInsuficienteException com sku e disponivel")
    void reservaSemEstoque() {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(409, ERRO_ESTOQUE_INSUFICIENTE)));

        assertThatThrownBy(() -> client.reservar(PEDIDO_ID, ITENS))
                .isInstanceOf(EstoqueInsuficienteException.class)
                .hasMessageContaining("MOU-003")
                .hasMessageEndingWith(": 1)");
        ESTOQUE.verify(1, postRequestedFor(urlPathEqualTo("/reservas")));
    }

    @Test
    @DisplayName("POST /reservas 404: SKU não cadastrado vira ProdutoInexistenteException com o sku do corpo de erro")
    void reservaComProdutoInexistente() {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(404, ERRO_PRODUTO_INEXISTENTE)));

        assertThatThrownBy(() -> client.reservar(PEDIDO_ID, ITENS))
                .isInstanceOf(ProdutoInexistenteException.class)
                .hasMessageContaining("XYZ-999");
    }

    @Test
    @DisplayName("POST /reservas 404 e 409 com corpo de erro ilegível: o motivo do status não pode ser escondido")
    void reservaComCorpoDeErroIlegivel() {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(409, "isto nao e json")));
        assertThatThrownBy(() -> client.reservar(PEDIDO_ID, ITENS))
                .isInstanceOf(EstoqueInsuficienteException.class);

        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(404, "isto nao e json")));
        assertThatThrownBy(() -> client.reservar(PEDIDO_ID, ITENS))
                .isInstanceOf(ProdutoInexistenteException.class);
    }

    @Test
    @DisplayName("POST /reservas 422: requisição inválida vira FalhaIntegracaoException")
    void reservaRejeitadaComDadosInvalidos() {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(
                json(422, "{\"codigo\":\"DADOS_INVALIDOS\",\"mensagem\":\"lista de itens vazia\"}")));

        assertThatThrownBy(() -> client.reservar(PEDIDO_ID, ITENS))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageContaining("POST /reservas");
    }

    // A reserva não é idempotente no contrato: repetir poderia reservar duas vezes.
    @ParameterizedTest(name = "POST /reservas respondendo {0}")
    @ValueSource(ints = {500, 502, 503, 504})
    void reservaComErroDoServidorNaoERepetida(int status) {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(status, ERRO_GENERICO)));

        assertThatThrownBy(() -> client.reservar(PEDIDO_ID, ITENS))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageStartingWith("estoque:");
        ESTOQUE.verify(1, postRequestedFor(urlPathEqualTo("/reservas")));
    }

    @Test
    @DisplayName("POST /reservas: timeout vira FalhaIntegracaoException, sem nova tentativa")
    void reservaComTimeoutNaoERepetida() {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas"))
                .willReturn(json(201, RESERVA).withFixedDelay(ATRASO_ACIMA_DO_TIMEOUT_MS)));

        assertThatThrownBy(() -> client.reservar(PEDIDO_ID, ITENS))
                .isInstanceOf(FalhaIntegracaoException.class);
        ESTOQUE.verify(1, postRequestedFor(urlPathEqualTo("/reservas")));
    }

    @ParameterizedTest(name = "corpo 201 = [{0}]")
    @ValueSource(strings = {"{\"pedidoId\":\"x\",\"status\":\"RESERVADA\",\"itens\":[]}", ""})
    void reservaCriadaSemIdEstaForaDoContrato(String corpo) {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(201, corpo)));

        assertThatThrownBy(() -> client.reservar(PEDIDO_ID, ITENS))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageContaining("fora do contrato");
    }

    // ------------------------------------------------------------------ POST /reservas/:id/confirmacao

    @Test
    @DisplayName("POST /reservas/{id}/confirmacao 200: aceita a confirmação e a repetição idempotente")
    void confirmacaoEIdempotente() {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas/res-123/confirmacao"))
                .willReturn(json(200, RESERVA_CONFIRMADA)));

        assertThatCode(() -> {
            client.confirmar("res-123");
            client.confirmar("res-123");
        }).doesNotThrowAnyException();
        ESTOQUE.verify(2, postRequestedFor(urlPathEqualTo("/reservas/res-123/confirmacao")));
    }

    // 404: reserva inexistente ou expirada (15 minutos). 409: reserva já liberada.
    @ParameterizedTest(name = "confirmação respondendo {0}")
    @ValueSource(ints = {404, 409, 500, 503})
    void confirmacaoRejeitada(int status) {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas/res-123/confirmacao"))
                .willReturn(json(status, ERRO_GENERICO)));

        assertThatThrownBy(() -> client.confirmar("res-123"))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageStartingWith("estoque:")
                .hasMessageContaining("res-123");
        ESTOQUE.verify(1, postRequestedFor(urlPathEqualTo("/reservas/res-123/confirmacao")));
    }

    // ------------------------------------------------------------------ DELETE /reservas/:id

    @Test
    @DisplayName("DELETE /reservas/{id} 204: libera a reserva e aceita a repetição idempotente")
    void liberacaoEIdempotente() {
        ESTOQUE.stubFor(delete(urlPathEqualTo("/reservas/res-123")).willReturn(aResponse().withStatus(204)));

        assertThatCode(() -> {
            client.liberar("res-123");
            client.liberar("res-123");
        }).doesNotThrowAnyException();
        ESTOQUE.verify(2, deleteRequestedFor(urlPathEqualTo("/reservas/res-123")));
    }

    // 404: reserva inexistente ou expirada. 409: reserva já confirmada.
    @ParameterizedTest(name = "liberação respondendo {0}")
    @ValueSource(ints = {404, 409, 500, 503})
    void liberacaoRejeitada(int status) {
        ESTOQUE.stubFor(delete(urlPathEqualTo("/reservas/res-123")).willReturn(json(status, ERRO_GENERICO)));

        assertThatThrownBy(() -> client.liberar("res-123"))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageStartingWith("estoque:")
                .hasMessageContaining("res-123");
        ESTOQUE.verify(1, deleteRequestedFor(urlPathEqualTo("/reservas/res-123")));
    }
}
