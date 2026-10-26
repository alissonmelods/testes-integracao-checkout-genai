package com.estudo.checkout.experimentos.contrato.v1;

import com.estudo.checkout.integracao.FalhaIntegracaoException;
import com.estudo.checkout.integracao.pagamentos.ContratoPagamentos;
import com.estudo.checkout.integracao.pagamentos.PagamentosClient;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PagamentosContratoTest extends ContratoBase {

    private static final String PEDIDO_ID = "7b0c2f6e-1d4a-4c55-9a0e-3f1f0f6b2a10";
    private static final String CHAVE = "pedido-" + PEDIDO_ID;

    // Corpos baseados nos exemplos do próprio contrato (pagamentos-api.yaml)
    private static final String APROVADO = """
            {"id":"pag-001","pedidoId":"7b0c2f6e-1d4a-4c55-9a0e-3f1f0f6b2a10","valorCentavos":17980,"metodo":"PIX","status":"APROVADO","motivo":null}""";
    private static final String RECUSADO = """
            {"id":"pag-002","pedidoId":"7b0c2f6e-1d4a-4c55-9a0e-3f1f0f6b2a10","valorCentavos":17980,"metodo":"CARTAO","status":"RECUSADO","motivo":"%s"}""";
    private static final String INDISPONIVEL = """
            {"codigo":"INDISPONIVEL","mensagem":"Servico temporariamente indisponivel"}""";
    private static final String CORPO_PIX = """
            {"pedidoId":"7b0c2f6e-1d4a-4c55-9a0e-3f1f0f6b2a10","valorCentavos":17980,"metodo":"PIX"}""";
    private static final String CORPO_CARTAO = """
            {"pedidoId":"7b0c2f6e-1d4a-4c55-9a0e-3f1f0f6b2a10","valorCentavos":17980,"metodo":"CARTAO","tokenCartao":"tok_visa_4242"}""";

    @Autowired
    private PagamentosClient client;

    @Value("${servicos.pagamentos.max-tentativas}")
    private int maxTentativas;

    // ------------------------------------------------------------------ requisição e resposta 201

    @Test
    @DisplayName("POST /pagamentos 201 (PIX): Idempotency-Key e NovoPagamento sem tokenCartao; lê id, status e motivo nulo")
    void pixAprovado() {
        stubPagamento(json(201, APROVADO));

        ContratoPagamentos.Pagamento pagamento = client.autorizar(PEDIDO_ID, 17980, "PIX", null);

        assertThat(pagamento.id()).isEqualTo("pag-001");
        assertThat(pagamento.status()).isEqualTo(ContratoPagamentos.StatusPagamento.APROVADO);
        assertThat(pagamento.motivo()).isNull();
        PAGAMENTOS.verify(1, postRequestedFor(urlPathEqualTo("/pagamentos"))
                .withHeader("Idempotency-Key", equalTo(CHAVE))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson(CORPO_PIX, false, false)));
    }

    @Test
    @DisplayName("POST /pagamentos (CARTAO): envia o tokenCartao e lê a recusa como resultado de negócio")
    void cartaoEnviaOToken() {
        stubPagamento(json(201, RECUSADO.formatted("SALDO_INSUFICIENTE")));

        ContratoPagamentos.Pagamento pagamento = client.autorizar(PEDIDO_ID, 17980, "CARTAO", "tok_visa_4242");

        assertThat(pagamento.id()).isEqualTo("pag-002");
        assertThat(pagamento.status()).isEqualTo(ContratoPagamentos.StatusPagamento.RECUSADO);
        PAGAMENTOS.verify(1, postRequestedFor(urlPathEqualTo("/pagamentos"))
                .withHeader("Idempotency-Key", equalTo(CHAVE))
                .withRequestBody(equalToJson(CORPO_CARTAO, false, false)));
    }

    // Recusa é resultado de negócio (HTTP 201): não lança exceção e não é repetida.
    @ParameterizedTest(name = "recusa por {0}")
    @ValueSource(strings = {"SALDO_INSUFICIENTE", "CARTAO_BLOQUEADO", "LIMITE_PIX_EXCEDIDO", "SUSPEITA_DE_FRAUDE"})
    void recusaNaoEErro(String motivo) {
        stubPagamento(json(201, RECUSADO.formatted(motivo)));

        ContratoPagamentos.Pagamento pagamento = client.autorizar(PEDIDO_ID, 17980, "CARTAO", "tok_visa_4242");

        assertThat(pagamento.status()).isEqualTo(ContratoPagamentos.StatusPagamento.RECUSADO);
        assertThat(pagamento.motivo()).isEqualTo(motivo);
        assertThat(tentativas()).hasSize(1);
    }

    @Test
    @DisplayName("valorCentavos é int64: valor acima de 2^31 vai como número inteiro")
    void valorCentavosInt64() {
        stubPagamento(json(201, APROVADO));

        client.autorizar(PEDIDO_ID, 5_000_000_000L, "PIX", null);

        PAGAMENTOS.verify(1, postRequestedFor(urlPathEqualTo("/pagamentos"))
                .withRequestBody(matchingJsonPath("$.valorCentavos", equalTo("5000000000"))));
    }

    // ------------------------------------------------------------------ idempotência

    @Test
    @DisplayName("POST /pagamentos 200: repetição idempotente devolve o pagamento original e conta como sucesso")
    void repeticaoIdempotenteResponde200() {
        stubPagamento(json(200, APROVADO));

        ContratoPagamentos.Pagamento pagamento = client.autorizar(PEDIDO_ID, 17980, "PIX", null);

        assertThat(pagamento.id()).isEqualTo("pag-001");
        assertThat(pagamento.status()).isEqualTo(ContratoPagamentos.StatusPagamento.APROVADO);
        assertThat(tentativas()).hasSize(1);
    }

    @Test
    @DisplayName("Duas chamadas do mesmo pedido (201 e depois 200) reenviam a mesma Idempotency-Key e o mesmo corpo")
    void mesmoPedidoReenviaMesmaChaveEMesmoCorpo() {
        stubSequencia(json(201, APROVADO), json(200, APROVADO));

        ContratoPagamentos.Pagamento primeiro = client.autorizar(PEDIDO_ID, 17980, "PIX", null);
        ContratoPagamentos.Pagamento segundo = client.autorizar(PEDIDO_ID, 17980, "PIX", null);

        assertThat(segundo).isEqualTo(primeiro);
        assertMesmaChaveEMesmoCorpo(tentativas(), 2);
    }

    @Test
    @DisplayName("Pedidos diferentes usam Idempotency-Key diferentes (senão o provedor responderia 409)")
    void pedidosDiferentesUsamChavesDiferentes() {
        stubPagamento(json(201, APROVADO));

        client.autorizar(PEDIDO_ID, 17980, "PIX", null);
        client.autorizar("a1b2c3d4-0000-4000-8000-000000000001", 17980, "PIX", null);

        List<String> chaves = tentativas().stream().map(r -> r.getHeader("Idempotency-Key")).toList();
        assertThat(chaves).hasSize(2).doesNotHaveDuplicates();
    }

    // ------------------------------------------------------------------ indisponibilidade e tentativas

    // O contrato só documenta 503, mas o client trata todo 5xx como transitório. É seguro porque a chave é a mesma.
    @ParameterizedTest(name = "{0} na 1a tentativa e sucesso na 2a")
    @ValueSource(ints = {500, 502, 503, 504})
    void falhaTransitoriaEDepoisSucessoRepeteComAMesmaChave(int status) {
        stubSequencia(json(status, INDISPONIVEL), json(201, APROVADO));

        ContratoPagamentos.Pagamento pagamento = client.autorizar(PEDIDO_ID, 17980, "PIX", null);

        assertThat(pagamento.status()).isEqualTo(ContratoPagamentos.StatusPagamento.APROVADO);
        assertMesmaChaveEMesmoCorpo(tentativas(), 2);
    }

    @Test
    @DisplayName("503 em todas as tentativas menos a última: a última ainda é feita e vale")
    void sucessoSomenteNaUltimaTentativa() {
        ResponseDefinitionBuilder[] respostas = new ResponseDefinitionBuilder[maxTentativas];
        for (int i = 0; i < maxTentativas - 1; i++) {
            respostas[i] = json(503, INDISPONIVEL);
        }
        respostas[maxTentativas - 1] = json(201, APROVADO);
        stubSequencia(respostas);

        ContratoPagamentos.Pagamento pagamento = client.autorizar(PEDIDO_ID, 17980, "PIX", null);

        assertThat(pagamento.status()).isEqualTo(ContratoPagamentos.StatusPagamento.APROVADO);
        assertMesmaChaveEMesmoCorpo(tentativas(), maxTentativas);
    }

    @Test
    @DisplayName("503 em todas as tentativas: desiste ao atingir max-tentativas, sempre com a mesma Idempotency-Key")
    void esgotaAsTentativas() {
        stubPagamento(json(503, INDISPONIVEL));

        assertThatThrownBy(() -> client.autorizar(PEDIDO_ID, 17980, "PIX", null))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageStartingWith("pagamentos:")
                .hasMessageContaining(maxTentativas + " tentativas");
        assertMesmaChaveEMesmoCorpo(tentativas(), maxTentativas);
    }

    @Test
    @DisplayName("Timeout na 1a tentativa: repete com a mesma chave e aceita o sucesso da 2a")
    void timeoutEDepoisSucesso() {
        stubSequencia(json(201, APROVADO).withFixedDelay(ATRASO_ACIMA_DO_TIMEOUT_MS), json(201, APROVADO));

        ContratoPagamentos.Pagamento pagamento = client.autorizar(PEDIDO_ID, 17980, "PIX", null);

        assertThat(pagamento.status()).isEqualTo(ContratoPagamentos.StatusPagamento.APROVADO);
        assertMesmaChaveEMesmoCorpo(tentativas(), 2);
    }

    @Test
    @DisplayName("Timeout em todas as tentativas: FalhaIntegracaoException depois de max-tentativas")
    void timeoutEmTodasAsTentativas() {
        stubPagamento(json(201, APROVADO).withFixedDelay(ATRASO_ACIMA_DO_TIMEOUT_MS));

        assertThatThrownBy(() -> client.autorizar(PEDIDO_ID, 17980, "PIX", null))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageContaining(maxTentativas + " tentativas");
        assertMesmaChaveEMesmoCorpo(tentativas(), maxTentativas);
    }

    // ------------------------------------------------------------------ erros que não se repetem

    // 4xx: a próxima tentativa receberia a mesma resposta, então não se repete.
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "400, IDEMPOTENCY_KEY_INVALIDA",
            "409, CHAVE_IDEMPOTENCIA_REUTILIZADA",
            "422, DADOS_INVALIDOS"})
    void erroDoClienteNaoERepetido(int status, String codigo) {
        stubPagamento(json(status, "{\"codigo\":\"" + codigo + "\",\"mensagem\":\"erro de exemplo\"}"));

        assertThatThrownBy(() -> client.autorizar(PEDIDO_ID, 17980, "PIX", null))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageStartingWith("pagamentos:")
                .hasCauseInstanceOf(HttpClientErrorException.class);
        assertThat(tentativas()).hasSize(1);
    }

    static Stream<String> respostasForaDoContrato() {
        return Stream.of(
                // sem id
                "{\"pedidoId\":\"x\",\"valorCentavos\":17980,\"metodo\":\"PIX\",\"status\":\"APROVADO\"}",
                // sem status
                "{\"id\":\"pag-001\",\"pedidoId\":\"x\",\"valorCentavos\":17980,\"metodo\":\"PIX\"}",
                // status fora do enum APROVADO/RECUSADO
                "{\"id\":\"pag-001\",\"pedidoId\":\"x\",\"valorCentavos\":17980,\"metodo\":\"PIX\",\"status\":\"PENDENTE\"}",
                // sem corpo
                "");
    }

    @ParameterizedTest(name = "resposta 201 fora do contrato: [{0}]")
    @MethodSource("respostasForaDoContrato")
    void respostaForaDoContratoNaoEAceitaNemRepetida(String corpo) {
        stubPagamento(json(201, corpo));

        assertThatThrownBy(() -> client.autorizar(PEDIDO_ID, 17980, "PIX", null))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageStartingWith("pagamentos:");
        assertThat(tentativas()).hasSize(1);
    }

    // ------------------------------------------------------------------ apoio

    private void stubPagamento(ResponseDefinitionBuilder resposta) {
        PAGAMENTOS.stubFor(post(urlPathEqualTo("/pagamentos")).willReturn(resposta));
    }

    /** Uma resposta por tentativa, na ordem (cenário do WireMock). */
    private void stubSequencia(ResponseDefinitionBuilder... respostas) {
        String estado = Scenario.STARTED;
        for (int i = 0; i < respostas.length; i++) {
            String proximo = "tentativa-" + (i + 2);
            PAGAMENTOS.stubFor(post(urlPathEqualTo("/pagamentos"))
                    .inScenario("tentativas")
                    .whenScenarioStateIs(estado)
                    .willSetStateTo(proximo)
                    .willReturn(respostas[i]));
            estado = proximo;
        }
    }

    private List<LoggedRequest> tentativas() {
        return chamadas(PAGAMENTOS, postRequestedFor(urlPathEqualTo("/pagamentos")));
    }

    /** É a mesma chave e o mesmo corpo em toda tentativa que faz o provedor contar a repetição como uma cobrança só. */
    private void assertMesmaChaveEMesmoCorpo(List<LoggedRequest> requisicoes, int quantidadeEsperada) {
        assertThat(requisicoes).hasSize(quantidadeEsperada);
        assertThat(requisicoes.stream().map(r -> r.getHeader("Idempotency-Key")).toList()).containsOnly(CHAVE);
        assertThat(requisicoes.stream().map(LoggedRequest::getBodyAsString).distinct().toList()).hasSize(1);
    }
}
