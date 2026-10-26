package com.estudo.checkout.integracao.estoque;

import java.util.List;

/**
 * Mensagens da API de Estoque (contratos/estoque-api.yaml) do ponto de vista do serviço de Pedidos: só os campos que
 * ele envia ou lê. Campos a mais na resposta são ignorados.
 */
public final class ContratoEstoque {

    private ContratoEstoque() {
    }

    public record Produto(String sku, Long precoCentavos) {
    }

    public record NovaReserva(String pedidoId, List<ItemReserva> itens) {
    }

    public record ItemReserva(String sku, int quantidade) {
    }

    public record Reserva(String id, String status) {
    }

    public record Erro(String codigo, String mensagem, String sku, Integer disponivel) {
    }
}
