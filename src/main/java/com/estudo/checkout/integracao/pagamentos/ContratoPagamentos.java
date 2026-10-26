package com.estudo.checkout.integracao.pagamentos;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Mensagens da API de Pagamentos (contratos/pagamentos-api.yaml) do ponto de vista do serviço de Pedidos: só os
 * campos que ele envia ou lê. Campos a mais na resposta são ignorados.
 */
public final class ContratoPagamentos {

    private ContratoPagamentos() {
    }

    public enum StatusPagamento {
        APROVADO,
        RECUSADO
    }

    /** tokenCartao só vai no JSON quando existe: no contrato ele é opcional e não aceita null. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NovoPagamento(String pedidoId, long valorCentavos, String metodo, String tokenCartao) {
    }

    public record Pagamento(String id, StatusPagamento status, String motivo) {
    }
}
