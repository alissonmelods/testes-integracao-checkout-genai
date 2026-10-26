package com.estudo.checkout.integracao;

/**
 * Falha na comunicação com um serviço externo: timeout, conexão recusada, status HTTP inesperado ou resposta fora do
 * contrato. A mensagem começa com o nome do serviço (ex.: "pagamentos: indisponível após 3 tentativas").
 */
public class FalhaIntegracaoException extends RuntimeException {

    private final String servico;

    public FalhaIntegracaoException(String servico, String detalhe) {
        this(servico, detalhe, null);
    }

    public FalhaIntegracaoException(String servico, String detalhe, Throwable causa) {
        super(servico + ": " + detalhe, causa);
        this.servico = servico;
    }

    public String getServico() {
        return servico;
    }
}
