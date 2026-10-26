package com.estudo.checkout.experimentos.contrato.v1;

import com.estudo.checkout.pedido.MetodoPagamento;
import com.estudo.checkout.pedido.NovoPedidoRequest;
import com.estudo.checkout.pedido.Pedido;
import com.estudo.checkout.pedido.PedidoService;
import com.estudo.checkout.pedido.StatusPedido;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

class CheckoutContratoTest extends ContratoBase {

    private static final String RESERVA = """
            {"id":"res-123","pedidoId":"pedido-do-teste","status":"RESERVADA","itens":[{"sku":"LIV-001","quantidade":2}]}""";
    private static final String RESERVA_CONFIRMADA = RESERVA.replace("RESERVADA", "CONFIRMADA");
    private static final String APROVADO = """
            {"id":"pag-001","pedidoId":"pedido-do-teste","valorCentavos":17980,"metodo":"PIX","status":"APROVADO","motivo":null}""";
    private static final String RECUSADO = """
            {"id":"pag-002","pedidoId":"pedido-do-teste","valorCentavos":17980,"metodo":"PIX","status":"RECUSADO","motivo":"SALDO_INSUFICIENTE"}""";
    private static final String INDISPONIVEL = """
            {"codigo":"INDISPONIVEL","mensagem":"Servico temporariamente indisponivel"}""";
    private static final String ERRO_ESTOQUE = """
            {"codigo":"RESERVA_INEXISTENTE","mensagem":"erro de exemplo"}""";
    private static final String ERRO_ESTOQUE_INSUFICIENTE = """
            {"codigo":"ESTOQUE_INSUFICIENTE","mensagem":"Quantidade indisponivel para MOU-003","sku":"MOU-003","disponivel":1}""";
    private static final String ERRO_PRODUTO_INEXISTENTE = """
            {"codigo":"PRODUTO_INEXISTENTE","mensagem":"Produto XYZ-999 nao encontrado","sku":"XYZ-999"}""";

    private static final NovoPedidoRequest.Item LIVRO = new NovoPedidoRequest.Item("LIV-001", 2);
    private static final NovoPedidoRequest.Item MOUSE = new NovoPedidoRequest.Item("MOU-003", 1);

    @Autowired
    private PedidoService pedidoService;

    @Value("${servicos.pagamentos.max-tentativas}")
    private int maxTentativas;

    // ------------------------------------------------------------------ caminho feliz

    @Test
    @DisplayName("Checkout aprovado: consulta preços, reserva, cobra o total e confirma, tudo com o mesmo pedidoId")
    void checkoutAprovado() {
        stubProduto("LIV-001", 8990);
        stubProduto("MOU-003", 4500);
        stubReserva();
        stubPagamento(json(201, APROVADO));
        stubConfirmacao(200);

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, LIVRO, MOUSE));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.CONFIRMADO);
        assertThat(pedido.getTotalCentavos()).isEqualTo(22480L); // 2 x 8990 + 1 x 4500
        assertThat(pedido.getReservaId()).isEqualTo("res-123");
        assertThat(pedido.getPagamentoId()).isEqualTo("pag-001");

        ESTOQUE.verify(1, getRequestedFor(urlPathEqualTo("/produtos/LIV-001")));
        ESTOQUE.verify(1, getRequestedFor(urlPathEqualTo("/produtos/MOU-003")));

        JsonNode reserva = corpoDaUnicaChamada(ESTOQUE, reservas());
        assertThat(reserva.get("pedidoId").asText()).isEqualTo(pedido.getId());
        assertThat(reserva.get("itens").size()).isEqualTo(2);
        assertThat(reserva.get("itens").get(0).get("sku").asText()).isEqualTo("LIV-001");
        assertThat(reserva.get("itens").get(0).get("quantidade").asInt()).isEqualTo(2);
        assertThat(reserva.get("itens").get(1).get("sku").asText()).isEqualTo("MOU-003");
        assertThat(reserva.get("itens").get(1).get("quantidade").asInt()).isEqualTo(1);

        JsonNode cobranca = corpoDaUnicaChamada(PAGAMENTOS, cobrancas());
        assertThat(cobranca.get("pedidoId").asText()).isEqualTo(pedido.getId());
        assertThat(cobranca.get("valorCentavos").asLong()).isEqualTo(22480L);
        assertThat(cobranca.get("metodo").asText()).isEqualTo("PIX");
        PAGAMENTOS.verify(1, cobrancas().withHeader("Idempotency-Key", equalTo("pedido-" + pedido.getId())));

        ESTOQUE.verify(1, postRequestedFor(urlPathEqualTo("/reservas/res-123/confirmacao")));
        ESTOQUE.verify(0, liberacoes());
    }

    @Test
    @DisplayName("Checkout com CARTAO: o tokenCartao do pedido vai para o Pagamentos (obrigatório no contrato)")
    void checkoutComCartaoEnviaOToken() {
        stubProduto("LIV-001", 8990);
        stubReserva();
        stubPagamento(json(201, APROVADO));
        stubConfirmacao(200);

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.CARTAO, "tok_visa_4242", LIVRO));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.CONFIRMADO);
        JsonNode cobranca = corpoDaUnicaChamada(PAGAMENTOS, cobrancas());
        assertThat(cobranca.get("metodo").asText()).isEqualTo("CARTAO");
        assertThat(cobranca.get("tokenCartao").asText()).isEqualTo("tok_visa_4242");
        assertThat(cobranca.get("valorCentavos").asLong()).isEqualTo(17980L);
    }

    // ------------------------------------------------------------------ compensação e falhas

    @Test
    @DisplayName("Pagamento recusado (201 + RECUSADO): devolve a reserva ao estoque e não confirma")
    void pagamentoRecusadoLiberaAReserva() {
        stubProduto("LIV-001", 8990);
        stubReserva();
        stubPagamento(json(201, RECUSADO));
        stubLiberacao(204);

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, LIVRO));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.PAGAMENTO_RECUSADO);
        assertThat(pedido.getMotivo()).isEqualTo("SALDO_INSUFICIENTE");
        ESTOQUE.verify(1, deleteRequestedFor(urlPathEqualTo("/reservas/res-123")));
        ESTOQUE.verify(0, confirmacoes());
        assertThat(chamadas(PAGAMENTOS, cobrancas())).hasSize(1);
    }

    @Test
    @DisplayName("Liberação da reserva falha (500) depois da recusa: o resultado do pedido não muda e não há nova tentativa")
    void falhaNaLiberacaoNaoMudaOResultado() {
        stubProduto("LIV-001", 8990);
        stubReserva();
        stubPagamento(json(201, RECUSADO));
        ESTOQUE.stubFor(delete(urlPathEqualTo("/reservas/res-123")).willReturn(json(500, ERRO_ESTOQUE)));

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, LIVRO));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.PAGAMENTO_RECUSADO);
        ESTOQUE.verify(1, liberacoes());
    }

    @Test
    @DisplayName("Pagamentos 503 em todas as tentativas: FALHA_INTEGRACAO, mesma Idempotency-Key e reserva liberada")
    void pagamentosIndisponivelLiberaAReserva() {
        stubProduto("LIV-001", 8990);
        stubReserva();
        stubPagamento(json(503, INDISPONIVEL));
        stubLiberacao(204);

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, LIVRO));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.FALHA_INTEGRACAO);
        List<LoggedRequest> tentativas = chamadas(PAGAMENTOS, cobrancas());
        assertThat(tentativas).hasSize(maxTentativas);
        assertThat(tentativas.stream().map(r -> r.getHeader("Idempotency-Key")).distinct().toList())
                .containsExactly("pedido-" + pedido.getId());
        ESTOQUE.verify(1, deleteRequestedFor(urlPathEqualTo("/reservas/res-123")));
        ESTOQUE.verify(0, confirmacoes());
    }

    @Test
    @DisplayName("Pagamentos 503 e depois 201: o pedido é confirmado e a reserva não é liberada")
    void pagamentosSeRecuperaDeFalhaTransitoria() {
        stubProduto("LIV-001", 8990);
        stubReserva();
        PAGAMENTOS.stubFor(post(urlPathEqualTo("/pagamentos"))
                .inScenario("recuperacao")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("recuperado")
                .willReturn(json(503, INDISPONIVEL)));
        PAGAMENTOS.stubFor(post(urlPathEqualTo("/pagamentos"))
                .inScenario("recuperacao")
                .whenScenarioStateIs("recuperado")
                .willReturn(json(201, APROVADO)));
        stubConfirmacao(200);

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, LIVRO));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.CONFIRMADO);
        List<LoggedRequest> tentativas = chamadas(PAGAMENTOS, cobrancas());
        assertThat(tentativas).hasSize(2);
        assertThat(tentativas.stream().map(r -> r.getHeader("Idempotency-Key")).distinct().toList()).hasSize(1);
        ESTOQUE.verify(1, confirmacoes());
        ESTOQUE.verify(0, liberacoes());
    }

    // O pagamento já foi aprovado: 404 (reserva expirada), 409 (já liberada) ou 503 na confirmação
    // não cancelam o pedido nem liberam a reserva.
    @ParameterizedTest(name = "confirmação respondendo {0}")
    @ValueSource(ints = {404, 409, 503})
    void falhaNaConfirmacaoNaoCancelaPedidoPago(int status) {
        stubProduto("LIV-001", 8990);
        stubReserva();
        stubPagamento(json(201, APROVADO));
        stubConfirmacao(status);

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, LIVRO));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.CONFIRMADO);
        ESTOQUE.verify(1, confirmacoes());
        ESTOQUE.verify(0, liberacoes());
    }

    @Test
    @DisplayName("Reserva 409 (tudo ou nada): nada é cobrado, confirmado nem liberado")
    void semEstoqueNaoCobra() {
        stubProduto("MOU-003", 4500);
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(409, ERRO_ESTOQUE_INSUFICIENTE)));

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, MOUSE));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.SEM_ESTOQUE);
        assertThat(pedido.getReservaId()).isNull();
        assertThat(totalDeChamadas(PAGAMENTOS)).isZero();
        ESTOQUE.verify(0, confirmacoes());
        ESTOQUE.verify(0, liberacoes());
    }

    @Test
    @DisplayName("Produto 404 na consulta de preço: não reserva nem cobra")
    void produtoInexistenteNaConsultaNaoReservaNemCobra() {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/LIV-001")).willReturn(json(404, ERRO_PRODUTO_INEXISTENTE)));

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, LIVRO));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.PRODUTO_INEXISTENTE);
        ESTOQUE.verify(0, reservas());
        assertThat(totalDeChamadas(PAGAMENTOS)).isZero();
    }

    @Test
    @DisplayName("Reserva 404 (algum SKU não cadastrado): não cobra e não há reserva para liberar")
    void produtoInexistenteNaReservaNaoCobraNemLibera() {
        stubProduto("LIV-001", 8990);
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(404, ERRO_PRODUTO_INEXISTENTE)));

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, LIVRO));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.PRODUTO_INEXISTENTE);
        assertThat(totalDeChamadas(PAGAMENTOS)).isZero();
        ESTOQUE.verify(0, liberacoes());
    }

    @Test
    @DisplayName("Estoque 503 na consulta de preço: FALHA_INTEGRACAO, sem reserva, sem cobrança, sem liberação")
    void estoqueIndisponivelNaConsultaNaoReservaNemCobra() {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/LIV-001")).willReturn(json(503, ERRO_ESTOQUE)));

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, LIVRO));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.FALHA_INTEGRACAO);
        ESTOQUE.verify(0, reservas());
        ESTOQUE.verify(0, liberacoes());
        assertThat(totalDeChamadas(PAGAMENTOS)).isZero();
    }

    @Test
    @DisplayName("Estoque 503 na reserva: FALHA_INTEGRACAO, uma única tentativa, sem cobrança e sem liberação")
    void estoqueIndisponivelNaReservaNaoCobraNemLibera() {
        stubProduto("LIV-001", 8990);
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(503, ERRO_ESTOQUE)));

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, LIVRO));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.FALHA_INTEGRACAO);
        ESTOQUE.verify(1, reservas());
        ESTOQUE.verify(0, liberacoes());
        assertThat(totalDeChamadas(PAGAMENTOS)).isZero();
    }

    // ------------------------------------------------------------------ lacunas (devem falhar com o código atual)

    @Test
    @Tag("lacuna")
    @DisplayName("LACUNA: PIX com tokenCartao no pedido não pode repassar tokenCartao ao Pagamentos (contrato: não enviar com PIX)")
    void pixComTokenNaoEnviaTokenCartaoAoPagamentos() {
        stubProduto("LIV-001", 8990);
        stubReserva();
        stubPagamento(json(201, APROVADO));
        stubConfirmacao(200);

        pedidoService.criar(pedido(MetodoPagamento.PIX, "tok_visa_4242", LIVRO));

        for (LoggedRequest cobranca : chamadas(PAGAMENTOS, cobrancas())) {
            assertThat(ContratoRegras.lerJson(cobranca.getBodyAsString()).has("tokenCartao"))
                    .as("tokenCartao não pode ser enviado quando metodo = PIX").isFalse();
        }
    }

    @Test
    @Tag("lacuna")
    @DisplayName("LACUNA: preço 0 é válido no Estoque, mas o Pagamentos exige valorCentavos >= 1")
    void totalZeroNaoPodeViolarOMinimoDoContratoDePagamentos() {
        stubProduto("BRD-000", 0);
        stubReserva();
        // o provedor real responde 422 para valorCentavos menor que 1
        stubPagamento(json(422, "{\"codigo\":\"DADOS_INVALIDOS\",\"mensagem\":\"valor menor que 1\"}"));
        stubLiberacao(204);

        pedidoService.criar(pedido(MetodoPagamento.PIX, null, new NovoPedidoRequest.Item("BRD-000", 1)));

        for (LoggedRequest cobranca : chamadas(PAGAMENTOS, cobrancas())) {
            assertThat(ContratoRegras.lerJson(cobranca.getBodyAsString()).get("valorCentavos").asLong())
                    .as("valorCentavos enviado ao Pagamentos").isGreaterThanOrEqualTo(1L);
        }
    }

    // ------------------------------------------------------------------ apoio

    private static NovoPedidoRequest pedido(MetodoPagamento metodo, String tokenCartao, NovoPedidoRequest.Item... itens) {
        return new NovoPedidoRequest("cliente-1", List.of(itens), metodo, tokenCartao);
    }

    private static void stubProduto(String sku, long precoCentavos) {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/" + sku)).willReturn(json(200,
                "{\"sku\":\"%s\",\"nome\":\"Produto %s\",\"precoCentavos\":%d,\"disponivel\":10}"
                        .formatted(sku, sku, precoCentavos))));
    }

    private static void stubReserva() {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(201, RESERVA)));
    }

    private static void stubConfirmacao(int status) {
        ResponseDefinitionBuilder resposta = status == 200 ? json(200, RESERVA_CONFIRMADA) : json(status, ERRO_ESTOQUE);
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas/res-123/confirmacao")).willReturn(resposta));
    }

    private static void stubLiberacao(int status) {
        ESTOQUE.stubFor(delete(urlPathEqualTo("/reservas/res-123")).willReturn(aResponse().withStatus(status)));
    }

    private static void stubPagamento(ResponseDefinitionBuilder resposta) {
        PAGAMENTOS.stubFor(post(urlPathEqualTo("/pagamentos")).willReturn(resposta));
    }

    private static RequestPatternBuilder reservas() {
        return postRequestedFor(urlPathEqualTo("/reservas"));
    }

    private static RequestPatternBuilder confirmacoes() {
        return postRequestedFor(urlPathMatching("/reservas/[^/]+/confirmacao"));
    }

    private static RequestPatternBuilder liberacoes() {
        return deleteRequestedFor(urlPathMatching("/reservas/[^/]+"));
    }

    private static RequestPatternBuilder cobrancas() {
        return postRequestedFor(urlPathEqualTo("/pagamentos"));
    }

    private static JsonNode corpoDaUnicaChamada(WireMockServer servidor, RequestPatternBuilder padrao) {
        List<LoggedRequest> requisicoes = chamadas(servidor, padrao);
        assertThat(requisicoes).hasSize(1);
        return ContratoRegras.lerJson(requisicoes.get(0).getBodyAsString());
    }
}
