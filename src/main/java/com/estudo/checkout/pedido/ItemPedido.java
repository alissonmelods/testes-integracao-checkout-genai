package com.estudo.checkout.pedido;

public class ItemPedido {

    private final String sku;
    private final int quantidade;
    private Long precoUnitarioCentavos;

    public ItemPedido(String sku, int quantidade) {
        this.sku = sku;
        this.quantidade = quantidade;
    }

    void definirPrecoUnitario(long precoUnitarioCentavos) {
        this.precoUnitarioCentavos = precoUnitarioCentavos;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantidade() {
        return quantidade;
    }

    public Long getPrecoUnitarioCentavos() {
        return precoUnitarioCentavos;
    }
}
