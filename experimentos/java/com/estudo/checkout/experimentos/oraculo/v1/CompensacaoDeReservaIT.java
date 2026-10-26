package com.estudo.checkout.experimentos.oraculo.v1;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CompensacaoDeReservaIT extends IntegracaoBase {

    private static final String RECUSADO =
            "{\"id\":\"pg-9\",\"status\":\"RECUSADO\",\"motivo\":\"saldo insuficiente\"}";

    /** Falhas possíveis nas chamadas de confirmação e de liberação da reserva. */
    static Stream<Arguments> falhasNoEstoque() {
        return Stream.of(
                arguments("HTTP 500", aResponse().withStatus(500)),
                arguments("HTTP 404, reserva já expirou", aResponse().withStatus(404)),
                arguments("timeout", aResponse().withStatus(204).withFixedDelay(ATRASO_MS)),
                arguments("conexão resetada", aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    }

    @Test
    @DisplayName("C-01: pagamento recusado devolve a reserva ao estoque e não confirma")
    void pagamentoRecusado_liberaReserva() throws Exception {
        prepararFluxoFeliz();
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).willReturn(json(201, RECUSADO)));

        criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value("PAGAMENTO_RECUSADO"))
                .andExpect(jsonPath("$.motivo").value("saldo insuficiente"))
                .andExpect(jsonPath("$.pagamentoId").value("pg-9"));

        PAGAMENTOS.verify(1, postPagamentos()); // recusa é resultado de negócio: não se repete
        ESTOQUE.verify(1, deleteReserva());
        ESTOQUE.verify(0, postConfirmacao());
    }

    @ParameterizedTest(name = "C-02: pagamento recusado e a liberação da reserva falha ({0})")
    @MethodSource("falhasNoEstoque")
    void pagamentoRecusado_falhaNaLiberacao_continuaRecusado(String descricao, ResponseDefinitionBuilder falha)
            throws Exception {
        prepararFluxoFeliz();
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).willReturn(json(201, RECUSADO)));
        ESTOQUE.stubFor(delete(urlEqualTo("/reservas/" + RESERVA_ID)).willReturn(falha));

        criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value("PAGAMENTO_RECUSADO"));

        ESTOQUE.verify(1, deleteReserva()); // tentada uma vez; se falhar, a reserva expira sozinha
    }

    @ParameterizedTest(name = "C-03: pagamento aprovado e a confirmação da reserva falha ({0})")
    @MethodSource("falhasNoEstoque")
    void pagamentoAprovado_falhaNaConfirmacao_continuaConfirmado(String descricao, ResponseDefinitionBuilder falha)
            throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(post(urlEqualTo("/reservas/" + RESERVA_ID + "/confirmacao")).willReturn(falha));

        criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMADO"))
                .andExpect(jsonPath("$.pagamentoId").value("pg-1"));

        ESTOQUE.verify(1, postConfirmacao()); // uma tentativa; a confirmação fica pendente
        ESTOQUE.verify(0, deleteReserva());   // já foi pago: não pode liberar
        PAGAMENTOS.verify(1, postPagamentos());
    }

    // ---- Os testes abaixo FALHAM hoje por causa do bug descrito em 1.1 ----

    @ParameterizedTest(name = "C-04: Pagamentos indisponível ({0}); a reserva deve ser liberada")
    @MethodSource("falhasTransitorias")
    void pagamentoIndisponivel_liberaReserva(String descricao, ResponseDefinitionBuilder falha) throws Exception {
        prepararFluxoFeliz();
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).willReturn(falha));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "pagamentos");

        ESTOQUE.verify(1, deleteReserva()); // StatusPedido.FALHA_INTEGRACAO: "a reserva, se havia, foi desfeita"
        ESTOQUE.verify(0, postConfirmacao());
    }

    @Test
    @DisplayName("C-04: Pagamentos fora do ar (conexão recusada); a reserva deve ser liberada")
    void pagamentosForaDoAr_liberaReserva() throws Exception {
        prepararFluxoFeliz();
        PAGAMENTOS.stop(); // o @BeforeEach seguinte religa

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "pagamentos");

        ESTOQUE.verify(1, deleteReserva());
        ESTOQUE.verify(0, postConfirmacao());
    }

    @ParameterizedTest(name = "C-05: Pagamentos responde fora do contrato ({0}); a reserva deve ser liberada")
    @MethodSource("pagamentosForaDoContrato")
    void pagamentoForaDoContrato_liberaReserva(String descricao, String contentType, String corpo) throws Exception {
        prepararFluxoFeliz();
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).willReturn(
                aResponse().withStatus(201).withHeader("Content-Type", contentType).withBody(corpo)));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "pagamentos");

        ESTOQUE.verify(1, deleteReserva());
        ESTOQUE.verify(0, postConfirmacao());
    }
}
