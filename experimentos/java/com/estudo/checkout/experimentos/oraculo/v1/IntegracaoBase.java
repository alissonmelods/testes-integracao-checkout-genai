package com.estudo.checkout.experimentos.oraculo.v1;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "servicos.estoque.timeout-ms=500",
        "servicos.pagamentos.timeout-ms=500",
        "servicos.pagamentos.max-tentativas=3"
})
@AutoConfigureMockMvc
@Timeout(20) // rede de segurança: se algum timeout do client não funcionar, o teste não fica pendurado
abstract class IntegracaoBase {

    static final String RESERVA_ID = "res-1";
    /** Maior que o timeout de 500 ms configurado acima. */
    static final int ATRASO_MS = 1500;

    static final String PEDIDO_CARTAO = """
            {"clienteId":"cli-1","itens":[{"sku":"SKU-1","quantidade":2}],\
            "metodoPagamento":"CARTAO","tokenCartao":"tok_teste"}""";
    static final String PEDIDO_PIX = """
            {"clienteId":"cli-1","itens":[{"sku":"SKU-1","quantidade":2}],\
            "metodoPagamento":"PIX","tokenCartao":"tok_ignorado"}""";

    // Sobem uma vez por JVM: o contexto Spring é reaproveitado entre as classes de teste e guarda essas URLs.
    static final WireMockServer ESTOQUE = iniciarWireMock();
    static final WireMockServer PAGAMENTOS = iniciarWireMock();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (ESTOQUE.isRunning()) ESTOQUE.stop();
            if (PAGAMENTOS.isRunning()) PAGAMENTOS.stop();
        }));
    }

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void urlsDosServicos(DynamicPropertyRegistry registry) {
        registry.add("servicos.estoque.url", ESTOQUE::baseUrl);
        registry.add("servicos.pagamentos.url", PAGAMENTOS::baseUrl);
    }

    @BeforeEach
    void religarEZerarServidores() {
        // alguns testes dão stop() para simular "conexão recusada"
        if (!ESTOQUE.isRunning()) ESTOQUE.start();
        if (!PAGAMENTOS.isRunning()) PAGAMENTOS.start();
        ESTOQUE.resetAll();
        PAGAMENTOS.resetAll();
    }

    // ---------- chamadas e asserções comuns ----------

    ResultActions criarPedido(String corpo) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/pedidos")
                .contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    static ResultActions esperarFalhaDeIntegracao(ResultActions resposta, String servico) throws Exception {
        return resposta
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("FALHA_INTEGRACAO"))
                .andExpect(jsonPath("$.motivo", startsWith(servico + ":")));
    }

    static String idDoPedido(ResultActions resposta) throws Exception {
        return JsonPath.read(resposta.andReturn().getResponse().getContentAsString(), "$.id");
    }

    // ---------- stubs ----------

    /** Estoque e Pagamentos respondendo conforme o contrato. Cada teste sobrescreve só o que quer quebrar. */
    static void prepararFluxoFeliz() {
        stubProduto("SKU-1", 1000);
        ESTOQUE.stubFor(post(urlEqualTo("/reservas"))
                .willReturn(json(201, "{\"id\":\"" + RESERVA_ID + "\",\"status\":\"RESERVADA\"}")));
        ESTOQUE.stubFor(post(urlEqualTo("/reservas/" + RESERVA_ID + "/confirmacao"))
                .willReturn(aResponse().withStatus(204)));
        ESTOQUE.stubFor(delete(urlEqualTo("/reservas/" + RESERVA_ID))
                .willReturn(aResponse().withStatus(204)));
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).willReturn(pagamentoAprovado()));
    }

    static void stubProduto(String sku, long precoCentavos) {
        ESTOQUE.stubFor(get(urlEqualTo("/produtos/" + sku)).willReturn(
                json(200, "{\"sku\":\"" + sku + "\",\"precoCentavos\":" + precoCentavos + "}")));
    }

    /** Pagamentos: a 1ª chamada recebe {@code falha}; da 2ª em diante, aprova. */
    static void pagamentoFalhaUmaVezDepoisAprova(ResponseDefinitionBuilder falha) {
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED).willReturn(falha).willSetStateTo("recuperado"));
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).inScenario("retry")
                .whenScenarioStateIs("recuperado").willReturn(pagamentoAprovado()));
    }

    static ResponseDefinitionBuilder json(int status, String corpo) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(corpo);
    }

    static ResponseDefinitionBuilder pagamentoAprovado() {
        return json(201, "{\"id\":\"pg-1\",\"status\":\"APROVADO\"}");
    }

    // ---------- padrões de requisição para verify/findAll ----------

    static RequestPatternBuilder postReservas() {
        return postRequestedFor(urlEqualTo("/reservas"));
    }

    static RequestPatternBuilder postPagamentos() {
        return postRequestedFor(urlEqualTo("/pagamentos"));
    }

    static RequestPatternBuilder postConfirmacao() {
        return postRequestedFor(urlEqualTo("/reservas/" + RESERVA_ID + "/confirmacao"));
    }

    static RequestPatternBuilder deleteReserva() {
        return deleteRequestedFor(urlEqualTo("/reservas/" + RESERVA_ID));
    }

    // ---------- massas de dados compartilhadas ----------

    /** Falhas que o PagamentosClient trata como transitórias (e repete). */
    static Stream<Arguments> falhasTransitorias() {
        return Stream.of(
                arguments("HTTP 500", aResponse().withStatus(500)),
                arguments("HTTP 502", aResponse().withStatus(502)),
                arguments("HTTP 503", aResponse().withStatus(503)),
                arguments("HTTP 504", aResponse().withStatus(504)),
                arguments("timeout", aResponse().withStatus(201).withFixedDelay(ATRASO_MS)),
                arguments("conexao resetada", aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
                arguments("resposta vazia", aResponse().withFault(Fault.EMPTY_RESPONSE)));
    }

    /** Respostas 201 do Pagamentos que não respeitam o contrato: descrição, Content-Type, corpo. */
    static Stream<Arguments> pagamentosForaDoContrato() {
        return Stream.of(
                arguments("objeto vazio", "application/json", "{}"),
                arguments("sem status", "application/json", "{\"id\":\"pg-1\"}"),
                arguments("sem id", "application/json", "{\"status\":\"APROVADO\"}"),
                arguments("status desconhecido", "application/json", "{\"id\":\"pg-1\",\"status\":\"PENDENTE\"}"),
                arguments("corpo vazio", "application/json", ""),
                arguments("HTML no lugar do JSON", "text/html", "<html>Bad Gateway</html>"));
    }

    // ---------- infraestrutura ----------

    private static WireMockServer iniciarWireMock() {
        WireMockServer servidor = new WireMockServer(options().port(portaLivre()).containerThreads(30));
        servidor.start();
        return servidor;
    }

    private static int portaLivre() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
