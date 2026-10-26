package com.estudo.checkout.pedido;

import java.util.List;
import java.util.UUID;

public class Pedido {

    private final String id = UUID.randomUUID().toString();
    private final String clienteId;
    private final List<ItemPedido> itens;
    private final MetodoPagamento metodoPagamento;
    private StatusPedido status;
    private String motivo;
    private String reservaId;
    private String pagamentoId;

    public Pedido(String clienteId, List<ItemPedido> itens, MetodoPagamento metodoPagamento) {
        this.clienteId = clienteId;
        this.itens = List.copyOf(itens);
        this.metodoPagamento = metodoPagamento;
    }

    /** Soma de preço unitário x quantidade, ou {@code null} enquanto algum item ainda não tem preço. */
    public Long getTotalCentavos() {
        long totalCentavos = 0;
        for (ItemPedido item : itens) {
            if (item.getPrecoUnitarioCentavos() == null) {
                return null;
            }
            totalCentavos += item.getPrecoUnitarioCentavos() * item.getQuantidade();
        }
        return totalCentavos;
    }

    void registrarReserva(String reservaId) {
        this.reservaId = reservaId;
    }

    void registrarPagamento(String pagamentoId) {
        this.pagamentoId = pagamentoId;
    }

    void finalizar(StatusPedido status, String motivo) {
        this.status = status;
        this.motivo = motivo;
    }

    public String getId() {
        return id;
    }

    public String getClienteId() {
        return clienteId;
    }

    public List<ItemPedido> getItens() {
        return itens;
    }

    public MetodoPagamento getMetodoPagamento() {
        return metodoPagamento;
    }

    public StatusPedido getStatus() {
        return status;
    }

    public String getMotivo() {
        return motivo;
    }

    public String getReservaId() {
        return reservaId;
    }

    public String getPagamentoId() {
        return pagamentoId;
    }
}
