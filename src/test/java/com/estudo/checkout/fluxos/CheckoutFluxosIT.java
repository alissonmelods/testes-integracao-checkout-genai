package com.estudo.checkout.fluxos;

// Gerada pelo prompt 1 (v2) e usada sem alterações: experimentos/respostas/1-cenarios-v2.md

import com.estudo.checkout.suporte.IntegracaoBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.UUID;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fluxos de negócio do checkout (POST /pedidos e GET /pedidos/{id}) entre Pedidos, Estoque e Pagamentos:
 * R1 a R5, R7, R8 e R12. Falhas de rede, timeouts, retentativas (R6) e falhas de contrato (R9 a R11) ficam para
 * outras classes.
 * <p>
 * Em cada teste: (1) status HTTP e campos do corpo que as regras definem; (2) chamadas feitas ao ESTOQUE e ao
 * PAGAMENTOS (quantidade, corpo, Idempotency-Key); (3) o que não pode ter sido chamado.
 */
class CheckoutFluxosIT extends IntegracaoBase {

    /** pedidoId dos exemplos dos contratos: o UUID do pedido nasce no serviço, então os stubs não têm como ecoá-lo. */
    private static final String PEDIDO_DOS_EXEMPLOS = "7b0c2f6e-1d4a-4c55-9a0e-3f1f0f6b2a10";
    private static final String TOKEN_CARTAO = "tok_visa_4242";

    // ------------------------------------------------------------------------------ caminhos de sucesso

    @Test
    @DisplayName("CF-01 [R2, R4, R5, R7] PIX com um item: consulta o preço, reserva, cobra o total e confirma")
    void cf01_pixComUmItem() {
        // Arrange
        String itens = itensJson(item("LIV-001", 2));
        estoqueTemProduto("LIV-001", "Livro Testes de Software", 8990, 10);
        estoqueAceitaReserva("res-123", itens);
        pagamentosAprova("pag-001", 17980, "PIX");

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(pedidoJson("PIX", null, itens));

        // Assert
        // 1) status HTTP e corpo
        assertThat(resposta.getStatusCode().value()).isEqualTo(201);
        JsonNode corpo = resposta.getBody();
        String pedidoId = corpo.path("id").asText();
        assertThat(corpo.path("status").asText()).isEqualTo("CONFIRMADO");
        assertThat(totalCentavos(corpo)).isEqualTo(17980L);
        assertThat(corpo.path("pagamentoId").asText()).isEqualTo("pag-001");
        // 2) chamadas feitas
        verificarConsultaDeProduto("LIV-001");
        verificarReserva(pedidoId, itens);
        verificarCobranca(pedidoId, cobrancaJson(pedidoId, 17980, "PIX", null));
        verificarConfirmacao("res-123");
        // 3) o que não pode ter sido chamado
        verificarQueNaoLiberou();
    }

    @Test
    @DisplayName("CF-02 [R5, R7] Cartão: repassa o tokenCartao ao Pagamentos e confirma")
    void cf02_cartaoComToken() {
        // Arrange
        String itens = itensJson(item("LIV-001", 2));
        estoqueTemProduto("LIV-001", "Livro Testes de Software", 8990, 10);
        estoqueAceitaReserva("res-123", itens);
        pagamentosAprova("pag-010", 17980, "CARTAO");

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(pedidoJson("CARTAO", TOKEN_CARTAO, itens));

        // Assert
        // 1) status HTTP e corpo
        assertThat(resposta.getStatusCode().value()).isEqualTo(201);
        JsonNode corpo = resposta.getBody();
        String pedidoId = corpo.path("id").asText();
        assertThat(corpo.path("status").asText()).isEqualTo("CONFIRMADO");
        assertThat(totalCentavos(corpo)).isEqualTo(17980L);
        assertThat(corpo.path("pagamentoId").asText()).isEqualTo("pag-010");
        // 2) chamadas feitas
        verificarConsultaDeProduto("LIV-001");
        verificarReserva(pedidoId, itens);
        verificarCobranca(pedidoId, cobrancaJson(pedidoId, 17980, "CARTAO", TOKEN_CARTAO));
        verificarConfirmacao("res-123");
        // 3) o que não pode ter sido chamado
        verificarQueNaoLiberou();
    }

    @Test
    @DisplayName("CF-03 [R5] PIX com tokenCartao informado: o token é descartado na cobrança")
    void cf03_pixDescartaOTokenDoCartao() {
        // Arrange
        String itens = itensJson(item("LIV-001", 2));
        estoqueTemProduto("LIV-001", "Livro Testes de Software", 8990, 10);
        estoqueAceitaReserva("res-123", itens);
        pagamentosAprova("pag-001", 17980, "PIX");

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(pedidoJson("PIX", TOKEN_CARTAO, itens));

        // Assert
        // 1) status HTTP e corpo
        assertThat(resposta.getStatusCode().value()).isEqualTo(201);
        JsonNode corpo = resposta.getBody();
        String pedidoId = corpo.path("id").asText();
        assertThat(corpo.path("status").asText()).isEqualTo("CONFIRMADO");
        assertThat(totalCentavos(corpo)).isEqualTo(17980L);
        assertThat(corpo.path("pagamentoId").asText()).isEqualTo("pag-001");
        // 2) chamadas feitas: o corpo da cobrança não tem tokenCartao (equalToJson é estrito com campos extras)
        verificarConsultaDeProduto("LIV-001");
        verificarReserva(pedidoId, itens);
        verificarCobranca(pedidoId, cobrancaJson(pedidoId, 17980, "PIX", null));
        verificarConfirmacao("res-123");
        // 3) o que não pode ter sido chamado
        verificarQueNaoLiberou();
    }

    @Test
    @DisplayName("CF-04 [R2, R4, R5] Vários itens e quantidades: o total é a soma de preço x quantidade")
    void cf04_variosItensEQuantidades() {
        // Arrange: 8990 x 2 + 4590 x 3 + 1299 x 5 = 17980 + 13770 + 6495 = 38245
        String itens = itensJson(item("LIV-001", 2), item("MOU-003", 3), item("CAB-002", 5));
        estoqueTemProduto("LIV-001", "Livro Testes de Software", 8990, 10);
        estoqueTemProduto("MOU-003", "Mouse sem fio", 4590, 20);
        estoqueTemProduto("CAB-002", "Cabo USB-C", 1299, 50);
        estoqueAceitaReserva("res-124", itens);
        pagamentosAprova("pag-020", 38245, "PIX");

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(pedidoJson("PIX", null, itens));

        // Assert
        // 1) status HTTP e corpo
        assertThat(resposta.getStatusCode().value()).isEqualTo(201);
        JsonNode corpo = resposta.getBody();
        String pedidoId = corpo.path("id").asText();
        assertThat(corpo.path("status").asText()).isEqualTo("CONFIRMADO");
        assertThat(totalCentavos(corpo)).isEqualTo(38245L);
        assertThat(corpo.path("pagamentoId").asText()).isEqualTo("pag-020");
        // 2) chamadas feitas
        verificarConsultaDeProduto("LIV-001");
        verificarConsultaDeProduto("MOU-003");
        verificarConsultaDeProduto("CAB-002");
        verificarReserva(pedidoId, itens);
        verificarCobranca(pedidoId, cobrancaJson(pedidoId, 38245, "PIX", null));
        verificarConfirmacao("res-124");
        // 3) o que não pode ter sido chamado
        verificarQueNaoLiberou();
    }

    @Test
    @DisplayName("CF-05 [R5, R7] Pedido com total zero: não cobra e segue direto para a confirmação")
    void cf05_totalZeroNaoCobra() {
        // Arrange: só um item gratuito (o Pagamentos exigiria valorCentavos >= 1), por isso não há stub de cobrança
        String itens = itensJson(item("BRD-000", 3));
        estoqueTemProduto("BRD-000", "Brinde Adesivo Testes", 0, 500);
        estoqueAceitaReserva("res-125", itens);

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(pedidoJson("PIX", null, itens));

        // Assert
        // 1) status HTTP e corpo
        assertThat(resposta.getStatusCode().value()).isEqualTo(201);
        JsonNode corpo = resposta.getBody();
        String pedidoId = corpo.path("id").asText();
        assertThat(corpo.path("status").asText()).isEqualTo("CONFIRMADO");
        assertThat(totalCentavos(corpo)).isZero();
        assertThat(corpo.hasNonNull("pagamentoId")).as("sem cobrança, não há pagamento").isFalse();
        // 2) chamadas feitas
        verificarConsultaDeProduto("BRD-000");
        verificarReserva(pedidoId, itens);
        verificarConfirmacao("res-125");
        // 3) o que não pode ter sido chamado
        verificarQueNaoCobrou();
        verificarQueNaoLiberou();
    }

    // ------------------------------------------------------------------ produto inexistente, sem estoque, recusa

    @Test
    @DisplayName("CF-06 [R3] Produto inexistente na consulta de preço: 422, sem reserva nem cobrança")
    void cf06_produtoInexistenteNaConsulta() {
        // Arrange: o primeiro item existe, o segundo não
        estoqueTemProduto("LIV-001", "Livro Testes de Software", 8990, 10);
        estoqueNaoTemProduto("XYZ-999");
        String itens = itensJson(item("LIV-001", 1), item("XYZ-999", 1));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(pedidoJson("PIX", null, itens));

        // Assert
        // 1) status HTTP e corpo
        assertThat(resposta.getStatusCode().value()).isEqualTo(422);
        assertThat(resposta.getBody().path("status").asText()).isEqualTo("PRODUTO_INEXISTENTE");
        // 2) chamadas feitas
        verificarConsultaDeProduto("LIV-001");
        verificarConsultaDeProduto("XYZ-999");
        // 3) o que não pode ter sido chamado
        verificarQueNaoReservou();
        verificarQueNaoCobrou();
        verificarQueNaoConfirmou();
        verificarQueNaoLiberou();
    }

    @Test
    @DisplayName("CF-07 [R3, R4] Produto inexistente na reserva: 422 e o Pagamentos não é chamado")
    void cf07_produtoInexistenteNaReserva() {
        // Arrange: o XYZ-999 existe na consulta, mas sai do catálogo antes da reserva (tudo ou nada)
        estoqueTemProduto("LIV-001", "Livro Testes de Software", 8990, 10);
        estoqueTemProduto("XYZ-999", "Produto Descontinuado", 1500, 3);
        estoqueRejeitaReservaPorProdutoInexistente("XYZ-999");
        String itens = itensJson(item("LIV-001", 1), item("XYZ-999", 1));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(pedidoJson("PIX", null, itens));

        // Assert
        // 1) status HTTP e corpo
        assertThat(resposta.getStatusCode().value()).isEqualTo(422);
        JsonNode corpo = resposta.getBody();
        String pedidoId = corpo.path("id").asText();
        assertThat(corpo.path("status").asText()).isEqualTo("PRODUTO_INEXISTENTE");
        // 2) chamadas feitas
        verificarConsultaDeProduto("LIV-001");
        verificarConsultaDeProduto("XYZ-999");
        verificarReserva(pedidoId, itens);
        // 3) o que não pode ter sido chamado (sem reserva, não há o que confirmar nem liberar)
        verificarQueNaoCobrou();
        verificarQueNaoConfirmou();
        verificarQueNaoLiberou();
    }

    @Test
    @DisplayName("CF-08 [R4] Sem estoque: 409 SEM_ESTOQUE e o Pagamentos não é chamado")
    void cf08_semEstoque() {
        // Arrange: o Estoque tem 1 unidade e o pedido pede 2
        String itens = itensJson(item("MOU-003", 2));
        estoqueTemProduto("MOU-003", "Mouse sem fio", 4590, 1);
        estoqueRejeitaReservaPorFaltaDeSaldo("MOU-003", 1);

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(pedidoJson("PIX", null, itens));

        // Assert
        // 1) status HTTP e corpo
        assertThat(resposta.getStatusCode().value()).isEqualTo(409);
        JsonNode corpo = resposta.getBody();
        String pedidoId = corpo.path("id").asText();
        assertThat(corpo.path("status").asText()).isEqualTo("SEM_ESTOQUE");
        // 2) chamadas feitas
        verificarConsultaDeProduto("MOU-003");
        verificarReserva(pedidoId, itens);
        // 3) o que não pode ter sido chamado
        verificarQueNaoCobrou();
        verificarQueNaoConfirmou();
        verificarQueNaoLiberou();
    }

    @Test
    @DisplayName("CF-09 [R8] Pagamento recusado: libera a reserva, responde 402 com o motivo e não confirma")
    void cf09_pagamentoRecusado() {
        // Arrange
        String itens = itensJson(item("LIV-001", 2));
        estoqueTemProduto("LIV-001", "Livro Testes de Software", 8990, 10);
        estoqueAceitaReserva("res-123", itens);
        pagamentosRecusa("pag-002", 17980, "CARTAO", "SALDO_INSUFICIENTE");

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(pedidoJson("CARTAO", TOKEN_CARTAO, itens));

        // Assert
        // 1) status HTTP e corpo
        assertThat(resposta.getStatusCode().value()).isEqualTo(402);
        JsonNode corpo = resposta.getBody();
        String pedidoId = corpo.path("id").asText();
        assertThat(corpo.path("status").asText()).isEqualTo("PAGAMENTO_RECUSADO");
        assertThat(corpo.path("motivo").asText()).isEqualTo("SALDO_INSUFICIENTE");
        assertThat(totalCentavos(corpo)).isEqualTo(17980L);
        // 2) chamadas feitas: uma única cobrança (recusa não é repetida) e a compensação da reserva
        verificarConsultaDeProduto("LIV-001");
        verificarReserva(pedidoId, itens);
        verificarCobranca(pedidoId, cobrancaJson(pedidoId, 17980, "CARTAO", TOKEN_CARTAO));
        verificarLiberacao("res-123");
        // 3) o que não pode ter sido chamado
        verificarQueNaoConfirmou();
    }

    // ------------------------------------------------------------------------------ R1: validação de entrada

    @Test
    @DisplayName("CF-10 [R1] CARTAO sem tokenCartao: 400 DADOS_INVALIDOS, sem chamadas externas")
    void cf10_cartaoSemToken() {
        // Arrange: nenhum stub; se o serviço chamasse alguém, o WireMock responderia 404
        String itens = itensJson(item("LIV-001", 2));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(pedidoJson("CARTAO", null, itens));

        // Assert: 400, DADOS_INVALIDOS, nenhum pedido criado e nenhuma chamada ao Estoque nem ao Pagamentos
        verificarEntradaRejeitada(resposta);
    }

    @Test
    @DisplayName("CF-11 [R1] Quantidade acima de 99: 400 DADOS_INVALIDOS, sem chamadas externas")
    void cf11_quantidadeAcimaDoLimite() {
        // Arrange
        String itens = itensJson(item("LIV-001", 100));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(pedidoJson("PIX", null, itens));

        // Assert
        verificarEntradaRejeitada(resposta);
    }

    @Test
    @DisplayName("CF-12 [R1] Mais de 20 itens: 400 DADOS_INVALIDOS, sem chamadas externas")
    void cf12_maisDeVinteItens() {
        // Arrange: 21 itens válidos, um a mais que o limite
        String itens = itensJson(IntStream.rangeClosed(1, 21)
                .mapToObj(i -> item("SKU-%02d".formatted(i), 1))
                .toArray(String[]::new));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(pedidoJson("PIX", null, itens));

        // Assert
        verificarEntradaRejeitada(resposta);
    }

    // ------------------------------------------------------------------------------ R12: consulta do pedido

    @Test
    @DisplayName("CF-13 [R12] Consulta de pedido confirmado: devolve o pedido gravado, sem novas chamadas externas")
    void cf13_consultaDePedidoConfirmado() {
        // Arrange: checkout confirmado
        String itens = itensJson(item("LIV-001", 2));
        estoqueTemProduto("LIV-001", "Livro Testes de Software", 8990, 10);
        estoqueAceitaReserva("res-123", itens);
        pagamentosAprova("pag-001", 17980, "PIX");
        ResponseEntity<JsonNode> criacao = postarPedido(pedidoJson("PIX", null, itens));
        assertThat(criacao.getStatusCode().value()).as("pré-condição: checkout confirmado").isEqualTo(201);
        String pedidoId = criacao.getBody().path("id").asText();

        // Act
        ResponseEntity<JsonNode> consulta = consultarPedido(pedidoId);

        // Assert
        // 1) status HTTP e corpo
        assertThat(consulta.getStatusCode().value()).isEqualTo(200);
        JsonNode corpo = consulta.getBody();
        assertThat(corpo.path("id").asText()).isEqualTo(pedidoId);
        assertThat(corpo.path("status").asText()).isEqualTo("CONFIRMADO");
        assertThat(totalCentavos(corpo)).isEqualTo(17980L);
        assertThat(corpo.path("pagamentoId").asText()).isEqualTo("pag-001");
        // 2) chamadas feitas: as contagens exatas (1x) provam que a consulta não repetiu nada do checkout
        verificarConsultaDeProduto("LIV-001");
        verificarReserva(pedidoId, itens);
        verificarCobranca(pedidoId, cobrancaJson(pedidoId, 17980, "PIX", null));
        verificarConfirmacao("res-123");
        // 3) o que não pode ter sido chamado
        verificarQueNaoLiberou();
    }

    @Test
    @DisplayName("CF-14 [R12] Consulta de pedido recusado: o status final e o motivo foram gravados")
    void cf14_consultaDePedidoRecusado() {
        // Arrange: checkout que termina em pagamento recusado
        String itens = itensJson(item("LIV-001", 2));
        estoqueTemProduto("LIV-001", "Livro Testes de Software", 8990, 10);
        estoqueAceitaReserva("res-123", itens);
        pagamentosRecusa("pag-002", 17980, "CARTAO", "SALDO_INSUFICIENTE");
        ResponseEntity<JsonNode> criacao = postarPedido(pedidoJson("CARTAO", TOKEN_CARTAO, itens));
        assertThat(criacao.getStatusCode().value()).as("pré-condição: checkout recusado").isEqualTo(402);
        String pedidoId = criacao.getBody().path("id").asText();

        // Act
        ResponseEntity<JsonNode> consulta = consultarPedido(pedidoId);

        // Assert
        // 1) status HTTP e corpo
        assertThat(consulta.getStatusCode().value()).isEqualTo(200);
        JsonNode corpo = consulta.getBody();
        assertThat(corpo.path("id").asText()).isEqualTo(pedidoId);
        assertThat(corpo.path("status").asText()).isEqualTo("PAGAMENTO_RECUSADO");
        assertThat(corpo.path("motivo").asText()).isEqualTo("SALDO_INSUFICIENTE");
        assertThat(totalCentavos(corpo)).isEqualTo(17980L);
        // 2) chamadas feitas: as do checkout, uma vez cada (a consulta não repetiu nada)
        verificarConsultaDeProduto("LIV-001");
        verificarReserva(pedidoId, itens);
        verificarCobranca(pedidoId, cobrancaJson(pedidoId, 17980, "CARTAO", TOKEN_CARTAO));
        verificarLiberacao("res-123");
        // 3) o que não pode ter sido chamado
        verificarQueNaoConfirmou();
    }

    @Test
    @DisplayName("CF-15 [R12] Consulta de id inexistente: 404 e nenhuma chamada externa")
    void cf15_consultaDePedidoInexistente() {
        // Arrange
        String idInexistente = UUID.randomUUID().toString();

        // Act
        ResponseEntity<JsonNode> resposta = consultarPedido(idInexistente);

        // Assert
        // 1) status HTTP
        assertThat(resposta.getStatusCode().value()).isEqualTo(404);
        // 3) o que não pode ter sido chamado
        ESTOQUE.verify(0, anyRequestedFor(anyUrl()));
        PAGAMENTOS.verify(0, anyRequestedFor(anyUrl()));
    }

    // ------------------------------------------------------------------------------ corpos JSON

    private static String item(String sku, int quantidade) {
        return """
                {"sku": "%s", "quantidade": %d}"""
                .formatted(sku, quantidade);
    }

    private static String itensJson(String... itens) {
        return "[" + String.join(", ", itens) + "]";
    }

    /** Corpo de POST /pedidos. O tokenCartao só entra no JSON quando informado. */
    private static String pedidoJson(String metodo, String tokenCartao, String itensJson) {
        String token = tokenCartao == null ? "" : ", \"tokenCartao\": \"%s\"".formatted(tokenCartao);
        return """
                {"clienteId": "cli-001", "metodoPagamento": "%s", "itens": %s%s}"""
                .formatted(metodo, itensJson, token);
    }

    /** Corpo esperado de POST /pagamentos (contrato NovoPagamento): tokenCartao só com CARTAO. */
    private static String cobrancaJson(String pedidoId, long valorCentavos, String metodo, String tokenCartao) {
        String token = tokenCartao == null ? "" : ", \"tokenCartao\": \"%s\"".formatted(tokenCartao);
        return """
                {"pedidoId": "%s", "valorCentavos": %d, "metodo": "%s"%s}"""
                .formatted(pedidoId, valorCentavos, metodo, token);
    }

    // ------------------------------------------------------------------------------ stubs do Estoque

    private static ResponseDefinitionBuilder json(int status, String corpo) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(corpo);
    }

    /** 200 de GET /produtos/{sku} com todos os campos obrigatórios do contrato (Produto). */
    private static void estoqueTemProduto(String sku, String nome, long precoCentavos, int disponivel) {
        String corpo = """
                {"sku": "%s", "nome": "%s", "precoCentavos": %d, "disponivel": %d}"""
                .formatted(sku, nome, precoCentavos, disponivel);
        ESTOQUE.stubFor(get(urlEqualTo("/produtos/" + sku)).willReturn(json(200, corpo)));
    }

    private static String erroProdutoInexistente(String sku) {
        return """
                {"codigo": "PRODUTO_INEXISTENTE", "mensagem": "Produto %1$s não encontrado", "sku": "%1$s"}"""
                .formatted(sku);
    }

    /** 404 de GET /produtos/{sku}. */
    private static void estoqueNaoTemProduto(String sku) {
        ESTOQUE.stubFor(get(urlEqualTo("/produtos/" + sku)).willReturn(json(404, erroProdutoInexistente(sku))));
    }

    private static String reservaJson(String reservaId, String status, String itensJson) {
        return """
                {"id": "%s", "pedidoId": "%s", "status": "%s", "itens": %s}"""
                .formatted(reservaId, PEDIDO_DOS_EXEMPLOS, status, itensJson);
    }

    /** 201 de POST /reservas e, depois, 200 na confirmação e 204 na liberação dessa reserva. */
    private static void estoqueAceitaReserva(String reservaId, String itensJson) {
        ESTOQUE.stubFor(post(urlEqualTo("/reservas"))
                .willReturn(json(201, reservaJson(reservaId, "RESERVADA", itensJson))));
        ESTOQUE.stubFor(post(urlEqualTo("/reservas/" + reservaId + "/confirmacao"))
                .willReturn(json(200, reservaJson(reservaId, "CONFIRMADA", itensJson))));
        ESTOQUE.stubFor(delete(urlEqualTo("/reservas/" + reservaId))
                .willReturn(aResponse().withStatus(204)));
    }

    /** 404 de POST /reservas: algum SKU não está cadastrado e nenhum item é reservado. */
    private static void estoqueRejeitaReservaPorProdutoInexistente(String sku) {
        ESTOQUE.stubFor(post(urlEqualTo("/reservas")).willReturn(json(404, erroProdutoInexistente(sku))));
    }

    /** 409 de POST /reservas: quantidade indisponível e nenhum item é reservado. */
    private static void estoqueRejeitaReservaPorFaltaDeSaldo(String sku, int disponivel) {
        String corpo = """
                {"codigo": "ESTOQUE_INSUFICIENTE", "mensagem": "Quantidade indisponível para %1$s", "sku": "%1$s", "disponivel": %2$d}"""
                .formatted(sku, disponivel);
        ESTOQUE.stubFor(post(urlEqualTo("/reservas")).willReturn(json(409, corpo)));
    }

    // ------------------------------------------------------------------------------ stubs do Pagamentos

    /** 201 de POST /pagamentos com todos os campos obrigatórios do contrato (Pagamento); o status diz se aprovou. */
    private static void pagamentosResponde(String pagamentoId, long valorCentavos, String metodo,
                                           String status, String motivo) {
        String motivoJson = motivo == null ? "null" : "\"" + motivo + "\"";
        String corpo = """
                {"id": "%s", "pedidoId": "%s", "valorCentavos": %d, "metodo": "%s", "status": "%s", "motivo": %s}"""
                .formatted(pagamentoId, PEDIDO_DOS_EXEMPLOS, valorCentavos, metodo, status, motivoJson);
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).willReturn(json(201, corpo)));
    }

    private static void pagamentosAprova(String pagamentoId, long valorCentavos, String metodo) {
        pagamentosResponde(pagamentoId, valorCentavos, metodo, "APROVADO", null);
    }

    private static void pagamentosRecusa(String pagamentoId, long valorCentavos, String metodo, String motivo) {
        pagamentosResponde(pagamentoId, valorCentavos, metodo, "RECUSADO", motivo);
    }

    // ------------------------------------------------------------------------------ verificações

    private static long totalCentavos(JsonNode corpo) {
        JsonNode total = corpo.path("totalCentavos");
        assertThat(total.isIntegralNumber()).as("totalCentavos deve ser um inteiro, em centavos").isTrue();
        return total.asLong();
    }

    /** R1: 400 com DADOS_INVALIDOS, nenhum pedido criado e nenhuma chamada ao Estoque nem ao Pagamentos. */
    private static void verificarEntradaRejeitada(ResponseEntity<JsonNode> resposta) {
        assertThat(resposta.getStatusCode().value()).isEqualTo(400);
        // a regra não diz em qual campo vai o código: basta que DADOS_INVALIDOS esteja no corpo
        assertThat(resposta.getBody().toString()).contains("DADOS_INVALIDOS");
        assertThat(resposta.getBody().hasNonNull("id")).as("o corpo não pode ser um pedido gravado").isFalse();
        ESTOQUE.verify(0, anyRequestedFor(anyUrl()));
        PAGAMENTOS.verify(0, anyRequestedFor(anyUrl()));
    }

    /** R2: um GET /produtos/{sku} para o item. */
    private static void verificarConsultaDeProduto(String sku) {
        ESTOQUE.verify(1, getRequestedFor(urlEqualTo("/produtos/" + sku)));
    }

    /** R4: uma reserva com o pedidoId e todos os itens (a ordem dos itens não importa). */
    private static void verificarReserva(String pedidoId, String itensJson) {
        String esperado = """
                {"pedidoId": "%s", "itens": %s}"""
                .formatted(pedidoId, itensJson);
        ESTOQUE.verify(1, postRequestedFor(urlEqualTo("/reservas"))
                .withRequestBody(equalToJson(esperado, true, false)));
    }

    /** R5: uma única cobrança, com o corpo exato e a Idempotency-Key do pedido. */
    private static void verificarCobranca(String pedidoId, String corpoEsperado) {
        PAGAMENTOS.verify(1, postRequestedFor(urlEqualTo("/pagamentos"))
                .withHeader("Idempotency-Key", equalTo("pedido-" + pedidoId))
                .withRequestBody(equalToJson(corpoEsperado)));
    }

    private static void verificarConfirmacao(String reservaId) {
        ESTOQUE.verify(1, postRequestedFor(urlEqualTo("/reservas/" + reservaId + "/confirmacao")));
    }

    private static void verificarLiberacao(String reservaId) {
        ESTOQUE.verify(1, deleteRequestedFor(urlEqualTo("/reservas/" + reservaId)));
    }

    private static void verificarQueNaoReservou() {
        ESTOQUE.verify(0, postRequestedFor(urlEqualTo("/reservas")));
    }

    private static void verificarQueNaoConfirmou() {
        ESTOQUE.verify(0, postRequestedFor(urlPathMatching("/reservas/.+/confirmacao")));
    }

    private static void verificarQueNaoLiberou() {
        ESTOQUE.verify(0, deleteRequestedFor(urlPathMatching("/reservas/.+")));
    }

    /** Nenhuma requisição, de qualquer tipo, chegou ao Pagamentos. */
    private static void verificarQueNaoCobrou() {
        PAGAMENTOS.verify(0, anyRequestedFor(anyUrl()));
    }
}
