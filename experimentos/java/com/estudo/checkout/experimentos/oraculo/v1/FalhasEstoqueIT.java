package com.estudo.checkout.experimentos.oraculo.v1;

import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FalhasEstoqueIT extends IntegracaoBase {

    private static final String PRODUTO_SKU_1 = "/produtos/SKU-1";

    private static void nadaReservadoNemCobrado() {
        ESTOQUE.verify(0, postReservas());
        PAGAMENTOS.verify(0, postPagamentos());
    }

    // ===================== GET /produtos/{sku} =====================

    @Test
    @DisplayName("E-01: 404 na consulta de preço vira PRODUTO_INEXISTENTE; nada é reservado nem cobrado")
    void produtoInexistenteNaConsultaDePreco() throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(get(urlEqualTo(PRODUTO_SKU_1)).willReturn(aResponse().withStatus(404)));

        criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("PRODUTO_INEXISTENTE"))
                .andExpect(jsonPath("$.motivo", containsString("SKU-1")));

        nadaReservadoNemCobrado();
    }

    @ParameterizedTest(name = "E-02: GET /produtos responde HTTP {0}")
    @ValueSource(ints = {500, 502, 503, 504})
    void erroHttpNaConsultaDePreco(int httpStatus) throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(get(urlEqualTo(PRODUTO_SKU_1)).willReturn(aResponse().withStatus(httpStatus)));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "estoque");

        ESTOQUE.verify(1, getRequestedFor(urlEqualTo(PRODUTO_SKU_1))); // o Estoque não tem retry
        nadaReservadoNemCobrado();
    }

    @Test
    @DisplayName("E-03: timeout na consulta de preço")
    void timeoutNaConsultaDePreco() throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(get(urlEqualTo(PRODUTO_SKU_1)).willReturn(
                json(200, "{\"sku\":\"SKU-1\",\"precoCentavos\":1000}").withFixedDelay(ATRASO_MS)));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "estoque");

        nadaReservadoNemCobrado();
    }

    @ParameterizedTest(name = "E-04: GET /produtos com falha de rede {0}")
    @EnumSource(Fault.class)
    void falhaDeRedeNaConsultaDePreco(Fault falha) throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(get(urlEqualTo(PRODUTO_SKU_1)).willReturn(aResponse().withFault(falha)));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "estoque");

        nadaReservadoNemCobrado();
    }

    static Stream<Arguments> produtosForaDoContrato() {
        return Stream.of(
                arguments("sem precoCentavos", "application/json", "{\"sku\":\"SKU-1\"}"),
                arguments("precoCentavos nulo", "application/json", "{\"sku\":\"SKU-1\",\"precoCentavos\":null}"),
                arguments("preço negativo", "application/json", "{\"sku\":\"SKU-1\",\"precoCentavos\":-100}"),
                // depende de spring.jackson.deserialization.accept-float-as-int=false (application.yml)
                arguments("preço com casa decimal", "application/json", "{\"sku\":\"SKU-1\",\"precoCentavos\":89.9}"),
                arguments("preço não numérico", "application/json", "{\"sku\":\"SKU-1\",\"precoCentavos\":\"abc\"}"),
                arguments("JSON truncado", "application/json", "{\"sku\":\"SKU-1\",\"precoCent"),
                arguments("corpo vazio", "application/json", ""),
                arguments("HTML no lugar do JSON", "text/html", "<html>Bad Gateway</html>"));
    }

    @ParameterizedTest(name = "E-05: GET /produtos com 200 fora do contrato ({0})")
    @MethodSource("produtosForaDoContrato")
    void consultaDePrecoForaDoContrato(String descricao, String contentType, String corpo) throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(get(urlEqualTo(PRODUTO_SKU_1)).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", contentType).withBody(corpo)));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "estoque");

        nadaReservadoNemCobrado();
    }

    @Test
    @DisplayName("E-06: falha no 2º item impede a reserva e a cobrança")
    void falhaNoSegundoItem() throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(get(urlEqualTo("/produtos/SKU-2")).willReturn(aResponse().withStatus(500)));
        String pedido = """
                {"clienteId":"cli-1","itens":[{"sku":"SKU-1","quantidade":1},{"sku":"SKU-2","quantidade":1}],\
                "metodoPagamento":"PIX"}""";

        esperarFalhaDeIntegracao(criarPedido(pedido), "estoque");

        ESTOQUE.verify(1, getRequestedFor(urlEqualTo(PRODUTO_SKU_1)));
        nadaReservadoNemCobrado();
    }

    // ===================== POST /reservas =====================

    @Test
    @DisplayName("E-07: 409 com corpo legível vira SEM_ESTOQUE")
    void estoqueInsuficiente() throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(post(urlEqualTo("/reservas")).willReturn(json(409,
                "{\"codigo\":\"ESTOQUE_INSUFICIENTE\",\"mensagem\":\"sem saldo\",\"sku\":\"SKU-1\",\"disponivel\":1}")));

        criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("SEM_ESTOQUE"))
                .andExpect(jsonPath("$.motivo", containsString("estoque insuficiente para o SKU SKU-1")));

        PAGAMENTOS.verify(0, postPagamentos());
        ESTOQUE.verify(0, deleteRequestedFor(urlPathMatching("/reservas/.*")));
    }

    @ParameterizedTest(name = "E-07: 409 com corpo ilegível [{0}] continua SEM_ESTOQUE")
    @ValueSource(strings = {"", "nao e json", "{\"codigo\":"})
    void estoqueInsuficienteComCorpoIlegivel(String corpo) throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(post(urlEqualTo("/reservas")).willReturn(json(409, corpo)));

        criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("SEM_ESTOQUE"));

        PAGAMENTOS.verify(0, postPagamentos());
    }

    @ParameterizedTest(name = "E-08: 404 em POST /reservas com corpo [{0}] vira PRODUTO_INEXISTENTE")
    @ValueSource(strings = {"{\"codigo\":\"PRODUTO_INEXISTENTE\",\"sku\":\"SKU-1\"}", ""})
    void produtoRemovidoEntrePrecoEReserva(String corpo) throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(post(urlEqualTo("/reservas")).willReturn(json(404, corpo)));

        criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("PRODUTO_INEXISTENTE"));

        PAGAMENTOS.verify(0, postPagamentos());
    }

    @ParameterizedTest(name = "E-09: POST /reservas responde HTTP {0}")
    @ValueSource(ints = {400, 422, 500, 502, 503, 504})
    void erroHttpNaReserva_naoRepete(int httpStatus) throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(post(urlEqualTo("/reservas")).willReturn(aResponse().withStatus(httpStatus)));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "estoque");

        ESTOQUE.verify(1, postReservas()); // reserva não é idempotente: sem retry
        PAGAMENTOS.verify(0, postPagamentos());
        ESTOQUE.verify(0, deleteRequestedFor(urlPathMatching("/reservas/.*"))); // não há o que liberar
    }

    @Test
    @DisplayName("E-10: timeout na reserva não é repetido e não cobra")
    void timeoutNaReserva() throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(post(urlEqualTo("/reservas")).willReturn(
                json(201, "{\"id\":\"" + RESERVA_ID + "\"}").withFixedDelay(ATRASO_MS)));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "estoque");

        ESTOQUE.verify(1, postReservas());
        PAGAMENTOS.verify(0, postPagamentos());
    }

    @ParameterizedTest(name = "E-10: POST /reservas com falha de rede {0}")
    @EnumSource(Fault.class)
    void falhaDeRedeNaReserva(Fault falha) throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(post(urlEqualTo("/reservas")).willReturn(aResponse().withFault(falha)));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "estoque");

        ESTOQUE.verify(1, postReservas());
        PAGAMENTOS.verify(0, postPagamentos());
    }

    static Stream<Arguments> reservasForaDoContrato() {
        return Stream.of(
                arguments("sem id", "application/json", "{\"status\":\"RESERVADA\"}"),
                arguments("id nulo", "application/json", "{\"id\":null}"),
                arguments("corpo vazio", "application/json", ""),
                arguments("HTML no lugar do JSON", "text/html", "<html>Bad Gateway</html>"));
    }

    @ParameterizedTest(name = "E-11: POST /reservas com 201 fora do contrato ({0})")
    @MethodSource("reservasForaDoContrato")
    void reservaForaDoContrato(String descricao, String contentType, String corpo) throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(post(urlEqualTo("/reservas")).willReturn(
                aResponse().withStatus(201).withHeader("Content-Type", contentType).withBody(corpo)));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "estoque");

        PAGAMENTOS.verify(0, postPagamentos());
    }

    // ===================== indisponibilidade total e total do pedido =====================

    @Test
    @DisplayName("E-12: Estoque fora do ar (conexão recusada)")
    void estoqueForaDoAr() throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stop(); // o @BeforeEach seguinte religa

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "estoque");

        PAGAMENTOS.verify(0, postPagamentos());
    }

    @Test
    @DisplayName("X-01: preço absurdo não pode estourar o total e confirmar o pedido sem cobrança (falha hoje)")
    void precoAbsurdoNaoPodeGerarPedidoSemCobranca() throws Exception {
        prepararFluxoFeliz();
        stubProduto("SKU-1", Long.MAX_VALUE); // quantidade 2 => 2 * Long.MAX_VALUE estoura para -2

        criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("FALHA_INTEGRACAO"));

        ESTOQUE.verify(0, postConfirmacao());
    }
}
