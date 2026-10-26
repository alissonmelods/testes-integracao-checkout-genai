package com.estudo.checkout.integracao.estoque;

public class EstoqueInsuficienteException extends RuntimeException {

    public EstoqueInsuficienteException(String sku, Integer disponivel) {
        super("estoque insuficiente para o SKU " + sku + " (disponível: " + disponivel + ")");
    }
}
