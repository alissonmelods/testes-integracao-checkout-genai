package com.estudo.checkout.experimentos.cenarios.v1;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class IntegracaoBase {

    static final int MAX_TENTATIVAS = 3;
    static final int TIMEOUT_MS = 1000;
    /** Maior que o timeout: a resposta atrasada faz o client estourar o tempo. */
    static final int ATRASO_MS = 2500;

    static final String PEDIDO_PIX = """
            {"clienteId":"cli-1","metodoPagamento":"PIX","itens":[{"sku":"SKU-1","quantidade":2}]}""";

    // Um servidor por serviço externo, iniciado uma única vez por JVM (ver "Estratégia").
    static final WireMockServer estoque = new WireMockServer(options().dynamicPort());
    static final WireMockServer pagamentos = new WireMockServer(options().dynamicPort());

    static {
        estoque.start();
        pagamentos.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            estoque.stop();
            pagamentos.stop();
        }));
    }

    @DynamicPropertySource
    static void configurarServicosExternos(DynamicPropertyRegistry registry) {
        registry.add("servicos.estoque.url", estoque::baseUrl);
        registry.add("servicos.estoque.timeout-ms", () -> TIMEOUT_MS);
        registry.add("servicos.pagamentos.url", pagamentos::baseUrl);
        registry.add("servicos.pagamentos.timeout-ms", () -> TIMEOUT_MS);
        registry.add("servicos.pagamentos.max-tentativas", () -> MAX_TENTATIVAS);
    }

    @Autowired
    private TestRestTemplate http;

    @BeforeEach
    void zerarMocks() {
        estoque.resetAll();
        pagamentos.resetAll();
    }

    @AfterEach
    void nenhumaChamadaSemStub() {
        assertThat(estoque.findAllUnmatchedRequests()).as("chamadas ao Estoque sem stub").isEmpty();
        assertThat(pagamentos.findAllUnmatchedRequests()).as("chamadas ao Pagamentos sem stub").isEmpty();
    }

    // ------------------------------------------------------------------ chamadas ao serviço de Pedidos

    /** Status HTTP e corpo de uma resposta, com leitura de campos por JsonPath. */
    record Resposta(int status, String corpo) {

        String texto(String caminho) {
            Object valor = JsonPath.read(corpo, caminho);
            return valor == null ? null : valor.toString();
        }

        Long numero(String caminho) {
            Object valor = JsonPath.read(corpo, caminho);
            return valor == null ? null : ((Number) valor).longValue();
        }

        List<String> lista(String caminho) {
            List<?> valores = JsonPath.read(corpo, caminho);
            return valores.stream().map(Object::toString).toList();
        }
    }

    Resposta postarPedido(String corpoJson) {
        return enviarPedido(corpoJson, MediaType.APPLICATION_JSON);
    }

    Resposta enviarPedido(String corpo, MediaType tipoDoConteudo) {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(tipoDoConteudo);
        ResponseEntity<String> resposta =
                http.exchange("/pedidos", HttpMethod.POST, new HttpEntity<>(corpo, cabecalhos), String.class);
        return new Resposta(resposta.getStatusCode().value(), resposta.getBody());
    }

    Resposta buscarPedido(String id) {
        ResponseEntity<String> resposta = http.getForEntity("/pedidos/{id}", String.class, id);
        return new Resposta(resposta.getStatusCode().value(), resposta.getBody());
    }

    /** Pedido PIX com {@code quantidade} itens (SKU-1, SKU-2, ...), uma unidade de cada. */
    static String pedidoComItens(int quantidade) {
        String itens = IntStream.rangeClosed(1, quantidade)
                .mapToObj(i -> "{\"sku\":\"SKU-%d\",\"quantidade\":1}".formatted(i))
                .collect(Collectors.joining(","));
        return "{\"clienteId\":\"cli-1\",\"metodoPagamento\":\"PIX\",\"itens\":[" + itens + "]}";
    }

    // ------------------------------------------------------------------ stubs do Estoque

    static ResponseDefinitionBuilder jsonComStatus(int status, String corpo) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(corpo);
    }

    static void estoqueProduto(String sku, long precoCentavos) {
        estoqueProdutoComResposta(sku, jsonComStatus(200,
                "{\"sku\":\"%s\",\"precoCentavos\":%d}".formatted(sku, precoCentavos)));
    }

    static void estoqueProdutoComResposta(String sku, ResponseDefinitionBuilder resposta) {
        estoque.stubFor(get(urlEqualTo("/produtos/" + sku)).willReturn(resposta));
    }

    static void estoqueTodosOsProdutos(long precoCentavos) {
        estoque.stubFor(get(urlPathMatching("/produtos/.+"))
                .willReturn(jsonComStatus(200, "{\"precoCentavos\":%d}".formatted(precoCentavos))));
    }

    static void estoqueReserva(String reservaId) {
        estoqueReservaComResposta(jsonComStatus(201,
                "{\"id\":\"%s\",\"status\":\"RESERVADA\"}".formatted(reservaId)));
    }

    static void estoqueReservaComResposta(ResponseDefinitionBuilder resposta) {
        estoque.stubFor(post(urlEqualTo("/reservas")).willReturn(resposta));
    }

    static void estoqueConfirmacao(String reservaId, ResponseDefinitionBuilder resposta) {
        estoque.stubFor(post(urlEqualTo("/reservas/" + reservaId + "/confirmacao")).willReturn(resposta));
    }

    static void estoqueConfirmacaoOk(String reservaId) {
        estoqueConfirmacao(reservaId, aResponse().withStatus(204));
    }

    static void estoqueLiberacao(String reservaId, ResponseDefinitionBuilder resposta) {
        estoque.stubFor(delete(urlEqualTo("/reservas/" + reservaId)).willReturn(resposta));
    }

    static void estoqueLiberacaoOk(String reservaId) {
        estoqueLiberacao(reservaId, aResponse().withStatus(204));
    }

    // ------------------------------------------------------------------ stubs do Pagamentos

    static ResponseDefinitionBuilder respostaAprovada(String pagamentoId) {
        return jsonComStatus(201, "{\"id\":\"%s\",\"status\":\"APROVADO\"}".formatted(pagamentoId));
    }

    static ResponseDefinitionBuilder respostaRecusada(String pagamentoId, String motivo) {
        return jsonComStatus(201,
                "{\"id\":\"%s\",\"status\":\"RECUSADO\",\"motivo\":\"%s\"}".formatted(pagamentoId, motivo));
    }

    static void pagamentoResponde(ResponseDefinitionBuilder resposta) {
        pagamentos.stubFor(post(urlEqualTo("/pagamentos")).willReturn(resposta));
    }

    // ------------------------------------------------------------------ combinações usadas por vários testes

    /** SKU-1 custa 1000 centavos e a reserva res-1 é criada. */
    static void prepararEstoqueAteAReserva() {
        estoqueProduto("SKU-1", 1000);
        estoqueReserva("res-1");
    }

    /** Estoque até a reserva, pagamento aprovado (pag-1) e confirmação da reserva aceita. */
    static void prepararCaminhoFeliz() {
        prepararEstoqueAteAReserva();
        pagamentoResponde(respostaAprovada("pag-1"));
        estoqueConfirmacaoOk("res-1");
    }

    // ------------------------------------------------------------------ verificações

    static void verificarReservasCriadas(int vezes) {
        estoque.verify(vezes, postRequestedFor(urlEqualTo("/reservas")));
    }

    static void verificarConfirmacoes(String reservaId, int vezes) {
        estoque.verify(vezes, postRequestedFor(urlEqualTo("/reservas/" + reservaId + "/confirmacao")));
    }

    static void verificarLiberacoes(String reservaId, int vezes) {
        estoque.verify(vezes, deleteRequestedFor(urlEqualTo("/reservas/" + reservaId)));
    }

    static void verificarNenhumaLiberacao() {
        estoque.verify(0, deleteRequestedFor(anyUrl()));
    }

    static void verificarCobrancas(int vezes) {
        pagamentos.verify(vezes, postRequestedFor(urlEqualTo("/pagamentos")));
    }

    static void verificarNenhumaChamadaExterna() {
        assertThat(estoque.getAllServeEvents()).as("chamadas ao Estoque").isEmpty();
        assertThat(pagamentos.getAllServeEvents()).as("chamadas ao Pagamentos").isEmpty();
    }
}
