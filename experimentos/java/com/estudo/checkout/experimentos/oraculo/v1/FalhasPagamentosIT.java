package com.estudo.checkout.experimentos.oraculo.v1;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FalhasPagamentosIT extends IntegracaoBase {

    @ParameterizedTest(name = "P-01: 1ª tentativa falha ({0}) e a 2ª funciona")
    @MethodSource("falhasTransitorias")
    void falhaTransitoria_repeteComAMesmaChaveDeIdempotencia(String descricao, ResponseDefinitionBuilder falha)
            throws Exception {
        prepararFluxoFeliz();
        pagamentoFalhaUmaVezDepoisAprova(falha);

        ResultActions resposta = criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMADO"))
                .andExpect(jsonPath("$.pagamentoId").value("pg-1"));

        List<LoggedRequest> tentativas = PAGAMENTOS.findAll(postPagamentos());
        assertThat(tentativas).hasSize(2);
        // a mesma chave nas duas tentativas é o que impede cobrança em duplicidade se a 1ª chegou a ser processada
        assertThat(tentativas).extracting(t -> t.getHeader("Idempotency-Key"))
                .containsOnly("pedido-" + idDoPedido(resposta));
        assertThat(tentativas.get(1).getBodyAsString()).isEqualTo(tentativas.get(0).getBodyAsString());
        ESTOQUE.verify(1, postConfirmacao());
        ESTOQUE.verify(0, deleteReserva());
    }

    @ParameterizedTest(name = "P-02: as 3 tentativas falham ({0})")
    @MethodSource("falhasTransitorias")
    void todasAsTentativasFalham_devolve503DepoisDeTresTentativas(String descricao, ResponseDefinitionBuilder falha)
            throws Exception {
        prepararFluxoFeliz();
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).willReturn(falha));

        ResultActions resposta = esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "pagamentos")
                .andExpect(jsonPath("$.motivo", containsString("3 tentativas")));

        List<LoggedRequest> tentativas = PAGAMENTOS.findAll(postPagamentos());
        assertThat(tentativas).hasSize(3);
        assertThat(tentativas).extracting(t -> t.getHeader("Idempotency-Key"))
                .containsOnly("pedido-" + idDoPedido(resposta));
        ESTOQUE.verify(0, postConfirmacao());
    }

    @Test
    @DisplayName("P-03: Pagamentos fora do ar (conexão recusada) esgota as tentativas")
    void pagamentosForaDoAr() throws Exception {
        prepararFluxoFeliz();
        PAGAMENTOS.stop(); // o @BeforeEach seguinte religa

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "pagamentos")
                .andExpect(jsonPath("$.motivo", containsString("3 tentativas")));

        ESTOQUE.verify(0, postConfirmacao());
    }

    /** Falha hoje: o client trata todo HttpStatusCodeException como transitório e repete 4xx (ver 1.2). */
    @ParameterizedTest(name = "P-04: POST /pagamentos responde HTTP {0}; erro de cliente não deve ser repetido")
    @ValueSource(ints = {400, 422})
    void erroDeCliente_naoDeveSerRepetido(int httpStatus) throws Exception {
        prepararFluxoFeliz();
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).willReturn(
                json(httpStatus, "{\"codigo\":\"REQUISICAO_INVALIDA\"}")));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "pagamentos");

        PAGAMENTOS.verify(1, postPagamentos());
        ESTOQUE.verify(0, postConfirmacao());
    }

    @ParameterizedTest(name = "P-05: POST /pagamentos com 201 fora do contrato ({0}) não é repetido")
    @MethodSource("pagamentosForaDoContrato")
    void respostaForaDoContrato_naoRepete(String descricao, String contentType, String corpo) throws Exception {
        prepararFluxoFeliz();
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).willReturn(
                aResponse().withStatus(201).withHeader("Content-Type", contentType).withBody(corpo)));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "pagamentos");

        PAGAMENTOS.verify(1, postPagamentos());
        ESTOQUE.verify(0, postConfirmacao());
    }

    @Test
    @DisplayName("P-06: PIX nunca envia tokenCartao, nem nas tentativas seguintes")
    void pix_naoEnviaTokenEmNenhumaTentativa() throws Exception {
        prepararFluxoFeliz();
        pagamentoFalhaUmaVezDepoisAprova(aResponse().withStatus(503));

        criarPedido(PEDIDO_PIX).andExpect(status().isCreated());

        PAGAMENTOS.verify(2, postPagamentos());
        PAGAMENTOS.verify(0, postRequestedFor(urlEqualTo("/pagamentos")).withRequestBody(containing("tokenCartao")));
        PAGAMENTOS.verify(2, postRequestedFor(urlEqualTo("/pagamentos"))
                .withRequestBody(matchingJsonPath("$.metodo", equalTo("PIX")))
                .withRequestBody(matchingJsonPath("$.valorCentavos", equalTo("2000"))));
    }
}
