package com.estudo.checkout.experimentos.cenarios.v1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Checkout: confirmação da reserva depois do pagamento aprovado")
class CheckoutConfirmacaoIT extends IntegracaoBase {

    @ParameterizedTest(name = "confirmação responde HTTP {0}")
    @ValueSource(ints = {404, 409, 500, 503})
    @DisplayName("CO-01: falha na confirmação não cancela o pedido já pago")
    void falhaNaConfirmacaoNaoCancelaPedidoPago(int statusHttp) {
        prepararEstoqueAteAReserva();
        pagamentoResponde(respostaAprovada("pag-1"));
        estoqueConfirmacao("res-1", aResponse().withStatus(statusHttp));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.texto("$.status")).isEqualTo("CONFIRMADO");
        assertThat(resposta.texto("$.pagamentoId")).isEqualTo("pag-1");
        verificarCobrancas(1);
        verificarConfirmacoes("res-1", 1); // sem retentativa
        verificarNenhumaLiberacao();       // pedido pago: a reserva não pode voltar para o estoque
    }

    @Test
    @DisplayName("CO-02: timeout na confirmação não cancela o pedido já pago")
    void timeoutNaConfirmacaoNaoCancelaPedidoPago() {
        prepararEstoqueAteAReserva();
        pagamentoResponde(respostaAprovada("pag-1"));
        estoqueConfirmacao("res-1", aResponse().withStatus(204).withFixedDelay(ATRASO_MS));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.texto("$.status")).isEqualTo("CONFIRMADO");
        verificarConfirmacoes("res-1", 1);
        verificarNenhumaLiberacao();
    }
}
