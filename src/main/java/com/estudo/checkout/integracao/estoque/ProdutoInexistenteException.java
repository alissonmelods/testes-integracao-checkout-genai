package com.estudo.checkout.integracao.estoque;

public class ProdutoInexistenteException extends RuntimeException {

    public ProdutoInexistenteException(String sku) {
        super("produto " + sku + " não existe no estoque");
    }
}
