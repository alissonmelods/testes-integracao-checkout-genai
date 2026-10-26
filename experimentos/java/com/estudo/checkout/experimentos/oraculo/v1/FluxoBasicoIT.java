package com.estudo.checkout.experimentos.oraculo.v1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FluxoBasicoIT extends IntegracaoBase {

    @Test
    @DisplayName("B-01: cartão aprovado confirma o pedido e cobra o total consultado no Estoque")
    void cartao_fluxoFeliz() throws Exception {
        prepararFluxoFeliz();

        ResultActions resposta = criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMADO"))
                .andExpect(jsonPath("$.totalCentavos").value(2000))
                .andExpect(jsonPath("$.reservaId").value(RESERVA_ID))
                .andExpect(jsonPath("$.pagamentoId").value("pg-1"));
        String id = idDoPedido(resposta);

        PAGAMENTOS.verify(1, postRequestedFor(urlEqualTo("/pagamentos"))
                .withHeader("Idempotency-Key", equalTo("pedido-" + id))
                .withRequestBody(matchingJsonPath("$.pedidoId", equalTo(id)))
                .withRequestBody(matchingJsonPath("$.valorCentavos", equalTo("2000")))
                .withRequestBody(matchingJsonPath("$.metodo", equalTo("CARTAO")))
                .withRequestBody(matchingJsonPath("$.tokenCartao", equalTo("tok_teste"))));
        ESTOQUE.verify(1, postConfirmacao());
        ESTOQUE.verify(0, deleteReserva());
    }

    @Test
    @DisplayName("B-02: pedido com falha de integração é gravado e consultável em GET /pedidos/{id}")
    void falhaDeIntegracaoEPersistidaEConsultavel() throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(get(urlEqualTo("/produtos/SKU-1")).willReturn(aResponse().withStatus(500)));

        String id = idDoPedido(esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "estoque"));

        mvc.perform(MockMvcRequestBuilders.get("/pedidos/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("FALHA_INTEGRACAO"));
    }

    @Test
    @DisplayName("B-03: pedido só com itens gratuitos não depende do Pagamentos")
    void pedidoGratuito_naoDependeDoPagamentos() throws Exception {
        prepararFluxoFeliz();
        stubProduto("SKU-1", 0);
        PAGAMENTOS.stop(); // fora do ar, e mesmo assim o pedido fecha

        criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMADO"))
                .andExpect(jsonPath("$.totalCentavos").value(0));

        ESTOQUE.verify(1, postConfirmacao());
    }
}
