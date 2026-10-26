package com.estudo.checkout.experimentos.falhas.v1;

// AJUSTE MANUAL (experimento): incluído @DirtiesContext. Sem ele, o WireMockExtension para e sobe de novo, em outra
// porta, a cada classe @Nested, mas o contexto do Spring fica em cache apontando para a porta antiga: só a primeira
// classe @Nested funcionava e alguns testes de "estoque fora do ar" passavam pelo motivo errado.
import org.springframework.test.annotation.DirtiesContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "servicos.estoque.timeout-ms=1000",
        "servicos.pagamentos.timeout-ms=1000",
        "servicos.pagamentos.max-tentativas=3"
})
@AutoConfigureMockMvc
@DirtiesContext
@DisplayName("POST /pedidos: falhas na comunicação com Estoque e Pagamentos")
class CheckoutFalhasIntegracaoTest {

    // Devem coincidir com as properties acima.
    static final int MAX_TENTATIVAS = 3;
    /** Maior que o timeout de 1 s: o client desiste antes de a resposta chegar. */
    static final int ATRASO_MS = 2_500;

    static final String SKU = "SKU-1";
    static final String RESERVA_ID = "RES-1";
    static final String PAGAMENTO_ID = "PAG-1";
    static final long PRECO_CENTAVOS = 2_500;

    static final String PEDIDO_PIX = """
            {"clienteId":"cli-1","itens":[{"sku":"SKU-1","quantidade":2}],"metodoPagamento":"PIX"}""";
    static final String PEDIDO_PIX_DOIS_ITENS = """
            {"clienteId":"cli-1","itens":[{"sku":"SKU-1","quantidade":1},{"sku":"SKU-2","quantidade":3}],"metodoPagamento":"PIX"}""";
    static final String PEDIDO_CARTAO = """
            {"clienteId":"cli-1","itens":[{"sku":"SKU-1","quantidade":2}],"metodoPagamento":"CARTAO","tokenCartao":"tok_teste"}""";

    @RegisterExtension
    static WireMockExtension estoque = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static WireMockExtension pagamentos = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void urlsDosServicos(DynamicPropertyRegistry registry) {
        registry.add("servicos.estoque.url", () -> "http://localhost:" + estoque.getPort());
        registry.add("servicos.pagamentos.url", () -> "http://localhost:" + pagamentos.getPort());
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    /** Caminho feliz completo. Cada teste sobrescreve só o stub que deve falhar (o stub mais recente vence). */
    @BeforeEach
    void servicosSaudaveis() {
        estoque.stubFor(get(urlEqualTo("/produtos/" + SKU))
                .willReturn(respostaJson(200, produtoJson(SKU, PRECO_CENTAVOS))));
        estoque.stubFor(post(urlEqualTo("/reservas"))
                .willReturn(respostaJson(201, "{\"id\":\"" + RESERVA_ID + "\",\"status\":\"RESERVADA\"}")));
        estoque.stubFor(post(urlEqualTo("/reservas/" + RESERVA_ID + "/confirmacao"))
                .willReturn(aResponse().withStatus(200)));
        estoque.stubFor(delete(urlEqualTo("/reservas/" + RESERVA_ID))
                .willReturn(aResponse().withStatus(204)));
        pagamentos.stubFor(post(urlEqualTo("/pagamentos"))
                .willReturn(respostaJson(201, pagamentoJson("APROVADO"))));
    }

    // ------------------------------------------------------------------ apoio

    record Resposta(int http, JsonNode corpo, String bruto) {
        String id() { return corpo.path("id").asText(); }
        String status() { return corpo.path("status").asText(); }
        String motivo() { return corpo.path("motivo").asText(""); }

        /** Valor do campo, ou null quando ele está ausente ou nulo. */
        String campo(String nome) { return corpo.hasNonNull(nome) ? corpo.get(nome).asText() : null; }
    }

    Resposta criarPedido(String json) throws Exception {
        var resposta = mockMvc.perform(MockMvcRequestBuilders.post("/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn().getResponse();
        String corpo = resposta.getContentAsString(StandardCharsets.UTF_8);
        return new Resposta(resposta.getStatus(), mapper.readTree(corpo), corpo);
    }

    /** 503 FALHA_INTEGRACAO atribuída ao serviço informado, com o pedido gravado e consultável. */
    void assertFalhaDeIntegracao(Resposta r, String servico) throws Exception {
        assertThat(r.http()).isEqualTo(503);
        assertThat(r.status()).isEqualTo("FALHA_INTEGRACAO");
        assertThat(r.motivo()).startsWith(servico + ":");

        var consulta = mockMvc.perform(MockMvcRequestBuilders.get("/pedidos/{id}", r.id()))
                .andReturn().getResponse();
        assertThat(consulta.getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(consulta.getContentAsString(StandardCharsets.UTF_8)).path("status").asText())
                .isEqualTo("FALHA_INTEGRACAO");
    }

    /** Falhou antes de existir reserva: nada a liberar e nada cobrado. */
    void assertNadaReservadoNemCobrado() {
        estoque.verify(0, postReservas());
        estoque.verify(0, deleteReserva());
        pagamentos.verify(0, postPagamentos());
    }

    static ResponseDefinitionBuilder respostaJson(int status, String corpo) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(corpo);
    }

    static String produtoJson(String sku, long precoCentavos) {
        return "{\"sku\":\"" + sku + "\",\"precoCentavos\":" + precoCentavos + "}";
    }

    static String pagamentoJson(String status) {
        return "{\"id\":\"" + PAGAMENTO_ID + "\",\"status\":\"" + status + "\"}";
    }

    static RequestPatternBuilder getProduto(String sku) {
        return getRequestedFor(urlEqualTo("/produtos/" + sku));
    }

    static RequestPatternBuilder postReservas() {
        return postRequestedFor(urlEqualTo("/reservas"));
    }

    static RequestPatternBuilder postConfirmacao() {
        return postRequestedFor(urlEqualTo("/reservas/" + RESERVA_ID + "/confirmacao"));
    }

    static RequestPatternBuilder deleteReserva() {
        return deleteRequestedFor(urlEqualTo("/reservas/" + RESERVA_ID));
    }

    static RequestPatternBuilder postPagamentos() {
        return postRequestedFor(urlEqualTo("/pagamentos"));
    }

    // ------------------------------------------------- Estoque: consulta de preço

    @Nested
    @DisplayName("Estoque: consulta de preço (GET /produtos/{sku})")
    class ConsultaDePreco {

        @ParameterizedTest(name = "EP-01 HTTP {0}")
        @ValueSource(ints = {500, 502, 503, 504})
        @DisplayName("erro 5xx: FALHA_INTEGRACAO sem repetir a chamada, sem reservar e sem cobrar")
        void erro5xx(int statusHttp) throws Exception {
            estoque.stubFor(get(urlEqualTo("/produtos/" + SKU))
                    .willReturn(aResponse().withStatus(statusHttp).withBody("falha interna")));

            Resposta r = criarPedido(PEDIDO_PIX);

            assertFalhaDeIntegracao(r, "estoque");
            estoque.verify(1, getProduto(SKU));
            assertNadaReservadoNemCobrado();
        }

        @Test
        @DisplayName("EP-02 timeout: FALHA_INTEGRACAO sem repetir a chamada, sem reservar e sem cobrar")
        void timeout() throws Exception {
            estoque.stubFor(get(urlEqualTo("/produtos/" + SKU))
                    .willReturn(respostaJson(200, produtoJson(SKU, PRECO_CENTAVOS)).withFixedDelay(ATRASO_MS)));

            Resposta r = criarPedido(PEDIDO_PIX);

            assertFalhaDeIntegracao(r, "estoque");
            estoque.verify(1, getProduto(SKU));
            assertNadaReservadoNemCobrado();
        }

        @Test
        @DisplayName("EP-03 conexão encerrada pelo servidor: FALHA_INTEGRACAO, sem reservar e sem cobrar")
        void conexaoEncerrada() throws Exception {
            estoque.stubFor(get(urlEqualTo("/produtos/" + SKU))
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            Resposta r = criarPedido(PEDIDO_PIX);

            // Sem contar chamadas: o HttpClient do JDK pode refazer um GET sozinho quando a conexão cai.
            assertFalhaDeIntegracao(r, "estoque");
            assertNadaReservadoNemCobrado();
        }

        @ParameterizedTest(name = "EP-04 HTTP {0}")
        @ValueSource(ints = {400, 401, 403, 429})
        @DisplayName("4xx diferente de 404 não é produto inexistente: FALHA_INTEGRACAO")
        void erro4xxInesperado(int statusHttp) throws Exception {
            estoque.stubFor(get(urlEqualTo("/produtos/" + SKU))
                    .willReturn(respostaJson(statusHttp, "{\"codigo\":\"ERRO\"}")));

            Resposta r = criarPedido(PEDIDO_PIX);

            assertFalhaDeIntegracao(r, "estoque");
            estoque.verify(1, getProduto(SKU));
            assertNadaReservadoNemCobrado();
        }

        @ParameterizedTest(name = "EP-05 corpo: {0}")
        @ValueSource(strings = {
                "",
                "{}",
                "{\"sku\":\"SKU-1\"}",
                "{\"sku\":\"SKU-1\",\"precoCentavos\":null}",
                "{\"sku\":\"SKU-1\",\"precoCentavos\":-1}",
                "{\"sku\":\"SKU-1\",\"precoCentavos\":89.9}",
                "{\"sku\":\"SKU-1\",\"precoCentavos\":\"caro\"}",
                "{\"sku\":\"SKU-1\",\"precoCent"})
        @DisplayName("resposta 200 fora do contrato: FALHA_INTEGRACAO, sem total, sem reservar e sem cobrar")
        void respostaForaDoContrato(String corpo) throws Exception {
            estoque.stubFor(get(urlEqualTo("/produtos/" + SKU)).willReturn(respostaJson(200, corpo)));

            Resposta r = criarPedido(PEDIDO_PIX);

            assertFalhaDeIntegracao(r, "estoque");
            assertThat(r.campo("totalCentavos")).isNull();
            assertNadaReservadoNemCobrado();
        }

        @Test
        @DisplayName("EP-06 falha na consulta do 2º item: nada é reservado nem cobrado")
        void falhaNoSegundoItem() throws Exception {
            estoque.stubFor(get(urlEqualTo("/produtos/SKU-2")).willReturn(aResponse().withStatus(500)));

            Resposta r = criarPedido(PEDIDO_PIX_DOIS_ITENS);

            assertFalhaDeIntegracao(r, "estoque");
            estoque.verify(1, getProduto("SKU-1"));
            estoque.verify(1, getProduto("SKU-2"));
            assertNadaReservadoNemCobrado();
        }
    }

    // ------------------------------------------------------- Estoque: reserva

    @Nested
    @DisplayName("Estoque: reserva (POST /reservas)")
    class ReservaDeItens {

        @ParameterizedTest(name = "ER-01 HTTP {0}")
        @ValueSource(ints = {500, 503})
        @DisplayName("erro 5xx: FALHA_INTEGRACAO, uma única tentativa e sem cobrar")
        void erro5xx(int statusHttp) throws Exception {
            estoque.stubFor(post(urlEqualTo("/reservas")).willReturn(aResponse().withStatus(statusHttp)));

            Resposta r = criarPedido(PEDIDO_PIX);

            assertFalhaDeIntegracao(r, "estoque");
            estoque.verify(1, postReservas());   // a reserva não é idempotente: nunca se repete
            estoque.verify(0, deleteReserva());  // não existe reserva a liberar
            pagamentos.verify(0, postPagamentos());
        }

        @Test
        @DisplayName("ER-02 timeout: FALHA_INTEGRACAO, uma única tentativa e sem cobrar")
        void timeout() throws Exception {
            estoque.stubFor(post(urlEqualTo("/reservas"))
                    .willReturn(respostaJson(201, "{\"id\":\"" + RESERVA_ID + "\"}").withFixedDelay(ATRASO_MS)));

            Resposta r = criarPedido(PEDIDO_PIX);

            assertFalhaDeIntegracao(r, "estoque");
            estoque.verify(1, postReservas());
            estoque.verify(0, deleteReserva());
            pagamentos.verify(0, postPagamentos());
        }

        @Test
        @DisplayName("ER-03 conexão encerrada pelo servidor: FALHA_INTEGRACAO e sem cobrar")
        void conexaoEncerrada() throws Exception {
            estoque.stubFor(post(urlEqualTo("/reservas"))
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            Resposta r = criarPedido(PEDIDO_PIX);

            assertFalhaDeIntegracao(r, "estoque");
            estoque.verify(0, deleteReserva());
            pagamentos.verify(0, postPagamentos());
        }

        @ParameterizedTest(name = "ER-04 HTTP {0}")
        @ValueSource(ints = {400, 422})
        @DisplayName("4xx inesperado: FALHA_INTEGRACAO e sem cobrar")
        void erro4xxInesperado(int statusHttp) throws Exception {
            estoque.stubFor(post(urlEqualTo("/reservas"))
                    .willReturn(respostaJson(statusHttp, "{\"codigo\":\"ERRO\"}")));

            Resposta r = criarPedido(PEDIDO_PIX);

            assertFalhaDeIntegracao(r, "estoque");
            estoque.verify(1, postReservas());
            pagamentos.verify(0, postPagamentos());
        }

        @ParameterizedTest(name = "ER-05 corpo: {0}")
        @ValueSource(strings = {"", "{}", "{\"id\":null,\"status\":\"RESERVADA\"}"})
        @DisplayName("201 sem id de reserva (fora do contrato): FALHA_INTEGRACAO e sem cobrar")
        void reservaSemId(String corpo) throws Exception {
            estoque.stubFor(post(urlEqualTo("/reservas")).willReturn(respostaJson(201, corpo)));

            Resposta r = criarPedido(PEDIDO_PIX);

            assertFalhaDeIntegracao(r, "estoque");
            assertThat(r.campo("reservaId")).isNull();
            pagamentos.verify(0, postPagamentos());
        }

        @ParameterizedTest(name = "ER-06 corpo do 409: {0}")
        @ValueSource(strings = {
                "",
                "<html>Conflict</html>",
                "{\"sku\":",
                "{\"sku\":\"SKU-1\",\"disponivel\":\"muitos\"}"})
        @DisplayName("409 com corpo ilegível continua sendo SEM_ESTOQUE")
        void conflitoComCorpoIlegivel(String corpo) throws Exception {
            estoque.stubFor(post(urlEqualTo("/reservas")).willReturn(respostaJson(409, corpo)));

            assertSemEstoqueSemCobranca(criarPedido(PEDIDO_PIX));
        }

        @Test
        @DisplayName("ER-06 409 com Content-Type text/html continua sendo SEM_ESTOQUE")
        void conflitoComHtml() throws Exception {
            estoque.stubFor(post(urlEqualTo("/reservas")).willReturn(aResponse()
                    .withStatus(409).withHeader("Content-Type", "text/html").withBody("<html>Conflict</html>")));

            assertSemEstoqueSemCobranca(criarPedido(PEDIDO_PIX));
        }

        @Test
        @DisplayName("ER-07 404 com corpo ilegível continua sendo PRODUTO_INEXISTENTE")
        void naoEncontradoComCorpoIlegivel() throws Exception {
            estoque.stubFor(post(urlEqualTo("/reservas")).willReturn(respostaJson(404, "<html>Not Found</html>")));

            Resposta r = criarPedido(PEDIDO_PIX);

            assertThat(r.http()).isEqualTo(422);
            assertThat(r.status()).isEqualTo("PRODUTO_INEXISTENTE");
            pagamentos.verify(0, postPagamentos());
        }

        private void assertSemEstoqueSemCobranca(Resposta r) {
            assertThat(r.http()).isEqualTo(409);
            assertThat(r.status()).isEqualTo("SEM_ESTOQUE");
            estoque.verify(0, deleteReserva());
            pagamentos.verify(0, postPagamentos());
        }
    }

    // ------------------------------------- Estoque: confirmação e liberação

    @Nested
    @DisplayName("Estoque: confirmação (POST /reservas/{id}/confirmacao) e liberação (DELETE /reservas/{id})")
    class ConfirmacaoELiberacao {

        @Test
        @DisplayName("EC-01 confirmação responde 500 depois do pagamento aprovado: pedido segue CONFIRMADO")
        void confirmacaoComErro() throws Exception {
            estoque.stubFor(post(urlEqualTo("/reservas/" + RESERVA_ID + "/confirmacao"))
                    .willReturn(aResponse().withStatus(500)));

            assertConfirmadoSemLiberarReserva(criarPedido(PEDIDO_PIX));
        }

        @Test
        @DisplayName("EC-02 confirmação em timeout depois do pagamento aprovado: pedido segue CONFIRMADO")
        void confirmacaoComTimeout() throws Exception {
            estoque.stubFor(post(urlEqualTo("/reservas/" + RESERVA_ID + "/confirmacao"))
                    .willReturn(aResponse().withStatus(200).withFixedDelay(ATRASO_MS)));

            assertConfirmadoSemLiberarReserva(criarPedido(PEDIDO_PIX));
        }

        @Test
        @DisplayName("EC-03 liberação responde 500 depois da recusa: continua PAGAMENTO_RECUSADO")
        void liberacaoComErroDepoisDeRecusa() throws Exception {
            pagamentos.stubFor(post(urlEqualTo("/pagamentos")).willReturn(respostaJson(201,
                    "{\"id\":\"" + PAGAMENTO_ID + "\",\"status\":\"RECUSADO\",\"motivo\":\"saldo insuficiente\"}")));
            estoque.stubFor(delete(urlEqualTo("/reservas/" + RESERVA_ID)).willReturn(aResponse().withStatus(500)));

            Resposta r = criarPedido(PEDIDO_PIX);

            assertThat(r.http()).isEqualTo(402);
            assertThat(r.status()).isEqualTo("PAGAMENTO_RECUSADO");
            assertThat(r.motivo()).isEqualTo("saldo insuficiente");
            estoque.verify(1, deleteReserva());
            estoque.verify(0, postConfirmacao());
        }

        @Test
        @DisplayName("EC-04 liberação em timeout depois de falha no Pagamentos: FALHA_INTEGRACAO do Pagamentos")
        void liberacaoComTimeoutDepoisDeFalhaNoPagamentos() throws Exception {
            pagamentos.stubFor(post(urlEqualTo("/pagamentos")).willReturn(aResponse().withStatus(503)));
            estoque.stubFor(delete(urlEqualTo("/reservas/" + RESERVA_ID))
                    .willReturn(aResponse().withStatus(204).withFixedDelay(ATRASO_MS)));

            Resposta r = criarPedido(PEDIDO_PIX);

            assertFalhaDeIntegracao(r, "pagamentos");
            assertThat(r.motivo()).isEqualTo("pagamentos: indisponível após " + MAX_TENTATIVAS + " tentativas");
            estoque.verify(1, deleteReserva());
        }

        private void assertConfirmadoSemLiberarReserva(Resposta r) {
            assertThat(r.http()).isEqualTo(201);
            assertThat(r.status()).isEqualTo("CONFIRMADO");
            assertThat(r.campo("pagamentoId")).isEqualTo(PAGAMENTO_ID);
            estoque.verify(1, postConfirmacao());   // sem retry
            estoque.verify(0, deleteReserva());     // o cliente já pagou: os itens não voltam ao estoque
        }
    }

    // ---------------------------------------- Pagamentos: falhas transitórias

    @Nested
    @DisplayName("Pagamentos: falhas transitórias (com retry)")
    class PagamentoComRetry {

        @ParameterizedTest(name = "PR-01 HTTP {0} em todas as tentativas")
        @ValueSource(ints = {500, 502, 503, 504})
        @DisplayName("esgota as tentativas, libera a reserva e responde FALHA_INTEGRACAO")
        void erro5xxEmTodasAsTentativas(int statusHttp) throws Exception {
            pagamentos.stubFor(post(urlEqualTo("/pagamentos"))
                    .willReturn(aResponse().withStatus(statusHttp).withBody("indisponivel")));

            assertTentativasEsgotadas(criarPedido(PEDIDO_PIX));
        }

        @Test
        @DisplayName("PR-02 timeout em todas as tentativas: esgota, libera a reserva e responde FALHA_INTEGRACAO")
        void timeoutEmTodasAsTentativas() throws Exception {
            pagamentos.stubFor(post(urlEqualTo("/pagamentos"))
                    .willReturn(respostaJson(201, pagamentoJson("APROVADO")).withFixedDelay(ATRASO_MS)));

            assertTentativasEsgotadas(criarPedido(PEDIDO_PIX));
        }

        @Test
        @DisplayName("PR-03 503, depois 502, depois aprovado: CONFIRMADO na 3ª tentativa")
        void sucessoNaTerceiraTentativa() throws Exception {
            pagamentos.stubFor(post(urlEqualTo("/pagamentos")).inScenario("cobranca")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(503)).willSetStateTo("segunda"));
            pagamentos.stubFor(post(urlEqualTo("/pagamentos")).inScenario("cobranca")
                    .whenScenarioStateIs("segunda")
                    .willReturn(aResponse().withStatus(502)).willSetStateTo("terceira"));
            pagamentos.stubFor(post(urlEqualTo("/pagamentos")).inScenario("cobranca")
                    .whenScenarioStateIs("terceira")
                    .willReturn(respostaJson(201, pagamentoJson("APROVADO"))));

            assertConfirmadoAposRetry(criarPedido(PEDIDO_PIX), 3);
        }

        @Test
        @DisplayName("PR-04 timeout e depois aprovado: CONFIRMADO na 2ª tentativa")
        void sucessoAposTimeout() throws Exception {
            primeiraTentativaFalhaDepoisAprova(
                    respostaJson(201, pagamentoJson("APROVADO")).withFixedDelay(ATRASO_MS));

            assertConfirmadoAposRetry(criarPedido(PEDIDO_PIX), 2);
        }

        @Test
        @DisplayName("PR-05 conexão resetada e depois aprovado: CONFIRMADO na 2ª tentativa")
        void sucessoAposConexaoResetada() throws Exception {
            primeiraTentativaFalhaDepoisAprova(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

            assertConfirmadoAposRetry(criarPedido(PEDIDO_PIX), 2);
        }

        @Test
        @DisplayName("PR-06 todas as tentativas levam a mesma Idempotency-Key e o mesmo corpo")
        void tentativasSaoIdempotentes() throws Exception {
            pagamentos.stubFor(post(urlEqualTo("/pagamentos")).willReturn(aResponse().withStatus(503)));

            Resposta r = criarPedido(PEDIDO_CARTAO);

            List<LoggedRequest> tentativas = pagamentos.findAll(postPagamentos());
            assertThat(tentativas).hasSize(MAX_TENTATIVAS);

            List<String> chaves = tentativas.stream().map(t -> t.getHeader("Idempotency-Key")).toList();
            assertThat(chaves).doesNotContainNull().containsOnly(chaves.get(0));
            assertThat(chaves.get(0)).isNotBlank().contains(r.id());

            List<String> corpos = tentativas.stream().map(LoggedRequest::getBodyAsString).toList();
            assertThat(corpos).containsOnly(corpos.get(0));
            JsonNode corpo = mapper.readTree(corpos.get(0));
            assertThat(corpo.path("pedidoId").asText()).isEqualTo(r.id());
            assertThat(corpo.path("valorCentavos").asLong()).isEqualTo(2 * PRECO_CENTAVOS);
            assertThat(corpo.path("metodo").asText()).isEqualTo("CARTAO");
            assertThat(corpo.path("tokenCartao").asText()).isEqualTo("tok_teste");
        }

        @Test
        @DisplayName("PR-07 pedidos distintos usam Idempotency-Key distintas")
        void pedidosDistintosNaoCompartilhamChave() throws Exception {
            pagamentos.stubFor(post(urlEqualTo("/pagamentos")).willReturn(aResponse().withStatus(503)));

            Resposta primeiro = criarPedido(PEDIDO_PIX);
            Resposta segundo = criarPedido(PEDIDO_PIX);

            assertThat(primeiro.id()).isNotEqualTo(segundo.id());
            List<String> chaves = pagamentos.findAll(postPagamentos()).stream()
                    .map(t -> t.getHeader("Idempotency-Key")).distinct().toList();
            assertThat(chaves).hasSize(2);
        }

        private void primeiraTentativaFalhaDepoisAprova(ResponseDefinitionBuilder primeira) {
            pagamentos.stubFor(post(urlEqualTo("/pagamentos")).inScenario("cobranca")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(primeira).willSetStateTo("segunda"));
            pagamentos.stubFor(post(urlEqualTo("/pagamentos")).inScenario("cobranca")
                    .whenScenarioStateIs("segunda")
                    .willReturn(respostaJson(201, pagamentoJson("APROVADO"))));
        }

        private void assertConfirmadoAposRetry(Resposta r, int tentativas) {
            assertThat(r.http()).isEqualTo(201);
            assertThat(r.status()).isEqualTo("CONFIRMADO");
            assertThat(r.campo("pagamentoId")).isEqualTo(PAGAMENTO_ID);
            pagamentos.verify(tentativas, postPagamentos());
            estoque.verify(1, postConfirmacao());
            estoque.verify(0, deleteReserva());
        }

        private void assertTentativasEsgotadas(Resposta r) throws Exception {
            assertFalhaDeIntegracao(r, "pagamentos");
            assertThat(r.motivo()).isEqualTo("pagamentos: indisponível após " + MAX_TENTATIVAS + " tentativas");
            assertThat(r.campo("pagamentoId")).isNull();
            pagamentos.verify(MAX_TENTATIVAS, postPagamentos());
            estoque.verify(1, getProduto(SKU));   // só a cobrança é repetida
            estoque.verify(1, postReservas());
            estoque.verify(1, deleteReserva());   // compensação: devolve os itens ao estoque
            estoque.verify(0, postConfirmacao());
        }
    }

    // ------------------------------------------- Pagamentos: falhas sem retry

    @Nested
    @DisplayName("Pagamentos: falhas que não se repetem")
    class PagamentoSemRetry {

        @ParameterizedTest(name = "PN-01 HTTP {0}")
        @ValueSource(ints = {400, 401, 403, 404, 409, 422})
        @DisplayName("4xx não é repetido: FALHA_INTEGRACAO e reserva liberada")
        void erro4xx(int statusHttp) throws Exception {
            pagamentos.stubFor(post(urlEqualTo("/pagamentos"))
                    .willReturn(respostaJson(statusHttp, "{\"codigo\":\"ERRO\"}")));

            Resposta r = criarPedido(PEDIDO_PIX);

            assertFalhaDeIntegracao(r, "pagamentos");
            assertThat(r.motivo()).doesNotContain("tentativas");
            assertTentativaUnicaComCompensacao();
        }

        @ParameterizedTest(name = "PN-02 corpo: {0}")
        @ValueSource(strings = {
                "",
                "{}",
                "{\"id\":\"PAG-1\"}",
                "{\"status\":\"APROVADO\"}",
                "{\"id\":\"PAG-1\",\"status\":\"PENDENTE\"}",
                "{\"id\":\"PAG-1\",\"status\":\"APROVADO\""})
        @DisplayName("200 fora do contrato: FALHA_INTEGRACAO, reserva liberada e nunca confirma")
        void respostaForaDoContrato(String corpo) throws Exception {
            pagamentos.stubFor(post(urlEqualTo("/pagamentos")).willReturn(respostaJson(200, corpo)));

            Resposta r = criarPedido(PEDIDO_PIX);

            assertFalhaDeIntegracao(r, "pagamentos");
            assertTentativaUnicaComCompensacao();
        }

        @Test
        @DisplayName("PN-03 200 com text/html (página de erro de proxy): FALHA_INTEGRACAO e reserva liberada")
        void respostaHtml() throws Exception {
            pagamentos.stubFor(post(urlEqualTo("/pagamentos")).willReturn(aResponse()
                    .withStatus(200).withHeader("Content-Type", "text/html").withBody("<html>Bad Gateway</html>")));

            Resposta r = criarPedido(PEDIDO_PIX);

            assertFalhaDeIntegracao(r, "pagamentos");
            assertTentativaUnicaComCompensacao();
        }

        private void assertTentativaUnicaComCompensacao() {
            pagamentos.verify(1, postPagamentos());   // não é falha transitória: repetir não ajudaria
            estoque.verify(1, deleteReserva());
            estoque.verify(0, postConfirmacao());
        }
    }

    // ------------------------------------------ Vazamento de detalhes internos

    @Nested
    @DisplayName("O erro do serviço externo não vaza para o cliente do checkout")
    class SemVazamento {

        private final String corpoSensivel =
                "java.lang.IllegalStateException: senha=SEGREDO-123 em jdbc:postgresql://db-interno/pagamentos";

        @Test
        @DisplayName("SG-01 corpo de erro do Estoque")
        void erroDoEstoque() throws Exception {
            estoque.stubFor(get(urlEqualTo("/produtos/" + SKU))
                    .willReturn(aResponse().withStatus(500).withBody(corpoSensivel)));

            Resposta r = criarPedido(PEDIDO_PIX);

            assertFalhaDeIntegracao(r, "estoque");
            assertSemDetalhesInternos(r, estoque.getPort());
        }

        @Test
        @DisplayName("SG-02 corpo de erro do Pagamentos")
        void erroDoPagamentos() throws Exception {
            pagamentos.stubFor(post(urlEqualTo("/pagamentos"))
                    .willReturn(aResponse().withStatus(500).withBody(corpoSensivel)));

            Resposta r = criarPedido(PEDIDO_PIX);

            assertFalhaDeIntegracao(r, "pagamentos");
            assertSemDetalhesInternos(r, pagamentos.getPort());
        }

        private void assertSemDetalhesInternos(Resposta r, int portaDoServico) {
            assertThat(r.bruto()).doesNotContain(
                    "SEGREDO-123", "db-interno", "IllegalStateException", "localhost:" + portaDoServico);
        }
    }
}
