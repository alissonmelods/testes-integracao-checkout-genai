package com.estudo.checkout.experimentos.cenarios.v1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Consulta de pedidos: GET /pedidos/{id}")
class ConsultaPedidoIT extends IntegracaoBase {

    @Test
    @DisplayName("CP-01: pedido confirmado volta igual ao do POST, sem novas chamadas aos serviços")
    void pedidoConfirmadoPodeSerConsultado() {
        prepararCaminhoFeliz();
        Resposta criado = postarPedido(PEDIDO_PIX);
        int chamadasAntes = estoque.getAllServeEvents().size() + pagamentos.getAllServeEvents().size();

        Resposta consultado = buscarPedido(criado.texto("$.id"));

        assertThat(consultado.status()).isEqualTo(200);
        assertThat(consultado.corpo()).isEqualTo(criado.corpo());
        assertThat(consultado.texto("$.status")).isEqualTo("CONFIRMADO");
        int chamadasDepois = estoque.getAllServeEvents().size() + pagamentos.getAllServeEvents().size();
        assertThat(chamadasDepois).isEqualTo(chamadasAntes);
    }

    @Test
    @DisplayName("CP-02: pedido com falha também fica gravado e consultável")
    void pedidoRecusadoTambemPodeSerConsultado() {
        prepararEstoqueAteAReserva();
        pagamentoResponde(respostaRecusada("pag-1", "saldo insuficiente"));
        estoqueLiberacaoOk("res-1");
        Resposta criado = postarPedido(PEDIDO_PIX);
        assertThat(criado.status()).isEqualTo(402);

        Resposta consultado = buscarPedido(criado.texto("$.id"));

        assertThat(consultado.status()).isEqualTo(200);
        assertThat(consultado.texto("$.status")).isEqualTo("PAGAMENTO_RECUSADO");
        assertThat(consultado.corpo()).isEqualTo(criado.corpo());
    }

    @Test
    @DisplayName("CP-03: pedido inexistente -> 404")
    void pedidoInexistenteRetorna404() {
        Resposta resposta = buscarPedido("nao-existe");

        assertThat(resposta.status()).isEqualTo(404);
        verificarNenhumaChamadaExterna();
    }
}
