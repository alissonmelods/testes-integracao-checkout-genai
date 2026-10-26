package com.estudo.checkout.experimentos.cenarios.v1;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Checkout: cenários do Pagamentos")
class CheckoutPagamentosIT extends IntegracaoBase {

    /** Falhas que o PagamentosClient trata como transitórias e, por isso, repete. */
    enum FalhaTransitoria {
        HTTP_500, HTTP_502, HTTP_503, HTTP_504, CONEXAO_RESETADA, RESPOSTA_VAZIA, TIMEOUT;

        ResponseDefinitionBuilder resposta() {
            return switch (this) {
                case HTTP_500 -> aResponse().withStatus(500);
                case HTTP_502 -> aResponse().withStatus(502);
                case HTTP_503 -> aResponse().withStatus(503);
                case HTTP_504 -> aResponse().withStatus(504);
                case CONEXAO_RESETADA -> aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER);
                case RESPOSTA_VAZIA -> aResponse().withFault(Fault.EMPTY_RESPONSE);
                case TIMEOUT -> jsonComStatus(201, "{\"id\":\"pag-lento\",\"status\":\"APROVADO\"}")
                        .withFixedDelay(ATRASO_MS);
            };
        }
    }

    @Test
    @DisplayName("PG-01: pagamento recusado -> 402, reserva liberada e sem confirmação")
    void pagamentoRecusado() {
        prepararEstoqueAteAReserva();
        pagamentoResponde(respostaRecusada("pag-1", "saldo insuficiente"));
        estoqueLiberacaoOk("res-1");

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(402);
        assertThat(resposta.texto("$.status")).isEqualTo("PAGAMENTO_RECUSADO");
        assertThat(resposta.texto("$.motivo")).isEqualTo("saldo insuficiente");
        assertThat(resposta.texto("$.reservaId")).isEqualTo("res-1");
        assertThat(resposta.texto("$.pagamentoId")).isEqualTo("pag-1");
        verificarCobrancas(1); // recusa não é repetida
        verificarLiberacoes("res-1", 1);
        verificarConfirmacoes("res-1", 0);
    }

    @ParameterizedTest(name = "liberação responde HTTP {0}")
    @ValueSource(ints = {404, 500, 503})
    @DisplayName("PG-02: recusa com falha na liberação da reserva continua sendo 402")
    void recusaComFalhaNaLiberacao(int statusHttp) {
        prepararEstoqueAteAReserva();
        pagamentoResponde(respostaRecusada("pag-1", "saldo insuficiente"));
        estoqueLiberacao("res-1", aResponse().withStatus(statusHttp));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(402);
        assertThat(resposta.texto("$.status")).isEqualTo("PAGAMENTO_RECUSADO");
        verificarLiberacoes("res-1", 1); // a liberação não é repetida
    }

    @Test
    @DisplayName("PG-03: recusa com timeout na liberação da reserva continua sendo 402")
    void recusaComTimeoutNaLiberacao() {
        prepararEstoqueAteAReserva();
        pagamentoResponde(respostaRecusada("pag-1", "saldo insuficiente"));
        estoqueLiberacao("res-1", aResponse().withStatus(204).withFixedDelay(ATRASO_MS));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(402);
        assertThat(resposta.texto("$.status")).isEqualTo("PAGAMENTO_RECUSADO");
        verificarLiberacoes("res-1", 1);
    }

    @ParameterizedTest(name = "1ª tentativa: {0}")
    @EnumSource(FalhaTransitoria.class)
    @DisplayName("PG-04: falha transitória na 1ª tentativa -> repete com a mesma Idempotency-Key e confirma")
    void falhaTransitoriaSeguidaDeSucesso(FalhaTransitoria falha) {
        prepararEstoqueAteAReserva();
        estoqueConfirmacaoOk("res-1");
        pagamentos.stubFor(post(urlEqualTo("/pagamentos")).inScenario("tentativas")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(falha.resposta())
                .willSetStateTo("segunda-tentativa"));
        pagamentos.stubFor(post(urlEqualTo("/pagamentos")).inScenario("tentativas")
                .whenScenarioStateIs("segunda-tentativa")
                .willReturn(respostaAprovada("pag-1")));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.texto("$.status")).isEqualTo("CONFIRMADO");
        assertThat(resposta.texto("$.pagamentoId")).isEqualTo("pag-1");

        List<LoggedRequest> tentativas = pagamentos.findAll(postRequestedFor(urlEqualTo("/pagamentos")));
        assertThat(tentativas).hasSize(2);
        assertThat(tentativas).extracting(t -> t.getHeader("Idempotency-Key"))
                .containsOnly("pedido-" + resposta.texto("$.id"));
        assertThat(tentativas).extracting(LoggedRequest::getBodyAsString)
                .containsOnly(tentativas.get(0).getBodyAsString());
        verificarReservasCriadas(1);
        verificarConfirmacoes("res-1", 1);
        verificarNenhumaLiberacao();
    }

    @ParameterizedTest(name = "todas as tentativas: {0}")
    @EnumSource(FalhaTransitoria.class)
    @DisplayName("PG-05: todas as tentativas falham -> 503, reserva liberada e sem confirmação")
    void todasAsTentativasFalham(FalhaTransitoria falha) {
        prepararEstoqueAteAReserva();
        estoqueLiberacaoOk("res-1");
        pagamentoResponde(falha.resposta());

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(503);
        assertThat(resposta.texto("$.status")).isEqualTo("FALHA_INTEGRACAO");
        assertThat(resposta.texto("$.motivo")).isEqualTo("pagamentos: indisponível após 3 tentativas");
        assertThat(resposta.texto("$.reservaId")).isEqualTo("res-1");
        assertThat(resposta.texto("$.pagamentoId")).isNull();

        List<LoggedRequest> tentativas = pagamentos.findAll(postRequestedFor(urlEqualTo("/pagamentos")));
        assertThat(tentativas).hasSize(MAX_TENTATIVAS); // nem a mais (stack HTTP), nem a menos
        assertThat(tentativas).extracting(t -> t.getHeader("Idempotency-Key"))
                .containsOnly("pedido-" + resposta.texto("$.id"));
        verificarLiberacoes("res-1", 1);
        verificarConfirmacoes("res-1", 0);
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {400, 401, 404, 409, 422, 429})
    @DisplayName("PG-06: erro 4xx do Pagamentos não é repetido -> 503 e reserva liberada")
    void erro4xxNaoEhRepetido(int statusHttp) {
        prepararEstoqueAteAReserva();
        estoqueLiberacaoOk("res-1");
        pagamentoResponde(aResponse().withStatus(statusHttp));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(503);
        assertThat(resposta.texto("$.status")).isEqualTo("FALHA_INTEGRACAO");
        assertThat(resposta.texto("$.motivo")).isEqualTo("pagamentos: erro não transitório em POST /pagamentos");
        verificarCobrancas(1);
        verificarLiberacoes("res-1", 1);
        verificarConfirmacoes("res-1", 0);
    }

    static Stream<Arguments> pagamentosForaDoContrato() {
        return Stream.of(
                arguments("sem id", "{\"status\":\"APROVADO\"}"),
                arguments("sem status", "{\"id\":\"pag-1\"}"),
                arguments("status desconhecido", "{\"id\":\"pag-1\",\"status\":\"PENDENTE\"}"),
                arguments("corpo vazio", ""));
    }

    @ParameterizedTest(name = "201 {0}")
    @MethodSource("pagamentosForaDoContrato")
    @DisplayName("PG-07: resposta fora do contrato não é repetida -> 503 e reserva liberada")
    void respostaForaDoContrato(String caso, String corpo) {
        prepararEstoqueAteAReserva();
        estoqueLiberacaoOk("res-1");
        pagamentoResponde(jsonComStatus(201, corpo));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(503);
        assertThat(resposta.texto("$.status")).isEqualTo("FALHA_INTEGRACAO");
        assertThat(resposta.texto("$.pagamentoId")).isNull();
        verificarCobrancas(1);
        verificarLiberacoes("res-1", 1);
        verificarConfirmacoes("res-1", 0);
    }

    @Test
    @DisplayName("PG-08: falha no pagamento e na liberação -> 503 com o motivo do pagamento")
    void falhaNoPagamentoEFalhaNaLiberacao() {
        prepararEstoqueAteAReserva();
        pagamentoResponde(aResponse().withStatus(500));
        estoqueLiberacao("res-1", aResponse().withStatus(500));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(503);
        assertThat(resposta.texto("$.status")).isEqualTo("FALHA_INTEGRACAO");
        assertThat(resposta.texto("$.motivo")).startsWith("pagamentos:");
        verificarCobrancas(MAX_TENTATIVAS);
        verificarLiberacoes("res-1", 1);
    }

    @Test
    @DisplayName("PG-09: cada pedido usa a sua própria Idempotency-Key")
    void cadaPedidoUsaSuaPropriaChaveDeIdempotencia() {
        prepararCaminhoFeliz();

        Resposta primeiro = postarPedido(PEDIDO_PIX);
        Resposta segundo = postarPedido(PEDIDO_PIX);

        String idPrimeiro = primeiro.texto("$.id");
        String idSegundo = segundo.texto("$.id");
        assertThat(idPrimeiro).isNotEqualTo(idSegundo);

        List<LoggedRequest> cobrancas = pagamentos.findAll(postRequestedFor(urlEqualTo("/pagamentos")));
        assertThat(cobrancas).extracting(c -> c.getHeader("Idempotency-Key"))
                .containsExactlyInAnyOrder("pedido-" + idPrimeiro, "pedido-" + idSegundo);
    }
}
