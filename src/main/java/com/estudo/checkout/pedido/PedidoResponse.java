package com.estudo.checkout.pedido;

import java.util.List;

public record PedidoResponse(
        String id,
        String clienteId,
        StatusPedido status,
        String motivo,
        MetodoPagamento metodoPagamento,
        Long totalCentavos,
        List<Item> itens,
        String reservaId,
        String pagamentoId) {

    public record Item(String sku, int quantidade, Long precoUnitarioCentavos) {
    }

    static PedidoResponse de(Pedido pedido) {
        List<Item> itens = pedido.getItens().stream()
                .map(item -> new Item(item.getSku(), item.getQuantidade(), item.getPrecoUnitarioCentavos()))
                .toList();
        return new PedidoResponse(pedido.getId(), pedido.getClienteId(), pedido.getStatus(), pedido.getMotivo(),
                pedido.getMetodoPagamento(), pedido.getTotalCentavos(), itens, pedido.getReservaId(),
                pedido.getPagamentoId());
    }
}
