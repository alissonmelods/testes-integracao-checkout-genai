package com.estudo.checkout.pedido;

import org.springframework.http.HttpStatus;

/** Resultado do checkout. Cada status tem o código HTTP devolvido por POST /pedidos. */
public enum StatusPedido {

    /** Pagamento aprovado e reserva confirmada no Estoque (ou com a confirmação pendente). */
    CONFIRMADO(HttpStatus.CREATED),

    /** O Estoque não tinha a quantidade pedida de algum item. Nada foi cobrado. */
    SEM_ESTOQUE(HttpStatus.CONFLICT),

    /** Algum SKU não existe no Estoque. Nada foi reservado nem cobrado. */
    PRODUTO_INEXISTENTE(HttpStatus.UNPROCESSABLE_ENTITY),

    /** O Pagamentos recusou a cobrança. A reserva foi desfeita. */
    PAGAMENTO_RECUSADO(HttpStatus.PAYMENT_REQUIRED),

    /** Estoque ou Pagamentos indisponível, com erro inesperado ou resposta fora do contrato. A reserva, se havia, foi desfeita. */
    FALHA_INTEGRACAO(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus statusHttp;

    StatusPedido(HttpStatus statusHttp) {
        this.statusHttp = statusHttp;
    }

    public HttpStatus getStatusHttp() {
        return statusHttp;
    }
}
