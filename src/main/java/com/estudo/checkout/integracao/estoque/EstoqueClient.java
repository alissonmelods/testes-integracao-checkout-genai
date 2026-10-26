package com.estudo.checkout.integracao.estoque;

import com.estudo.checkout.integracao.FabricaRestClient;
import com.estudo.checkout.integracao.FalhaIntegracaoException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;

/** Client da API de Estoque. As chamadas não são repetidas: a reserva não é idempotente. */
@Component
public class EstoqueClient {

    private static final String SERVICO = "estoque";

    private final RestClient restClient;

    public EstoqueClient(RestClient.Builder builder,
                         @Value("${servicos.estoque.url}") String url,
                         @Value("${servicos.estoque.timeout-ms}") long timeoutMs) {
        this.restClient = FabricaRestClient.criar(builder, url, Duration.ofMillis(timeoutMs));
    }

    public ContratoEstoque.Produto consultarProduto(String sku) {
        ContratoEstoque.Produto produto;
        try {
            produto = restClient.get()
                    .uri("/produtos/{sku}", sku)
                    .retrieve()
                    .body(ContratoEstoque.Produto.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ProdutoInexistenteException(sku);
        } catch (RestClientException e) {
            throw new FalhaIntegracaoException(SERVICO, "erro em GET /produtos/" + sku, e);
        }
        if (produto == null || produto.precoCentavos() == null || produto.precoCentavos() < 0) {
            throw new FalhaIntegracaoException(SERVICO, "resposta de GET /produtos/" + sku + " fora do contrato");
        }
        return produto;
    }

    public ContratoEstoque.Reserva reservar(String pedidoId, List<ContratoEstoque.ItemReserva> itens) {
        ContratoEstoque.Reserva reserva;
        try {
            reserva = restClient.post()
                    .uri("/reservas")
                    .body(new ContratoEstoque.NovaReserva(pedidoId, itens))
                    .retrieve()
                    .body(ContratoEstoque.Reserva.class);
        } catch (HttpClientErrorException.Conflict e) {
            ContratoEstoque.Erro erro = lerErro(e);
            throw new EstoqueInsuficienteException(erro.sku(), erro.disponivel());
        } catch (HttpClientErrorException.NotFound e) {
            throw new ProdutoInexistenteException(lerErro(e).sku());
        } catch (RestClientException e) {
            throw new FalhaIntegracaoException(SERVICO, "erro em POST /reservas", e);
        }
        if (reserva == null || reserva.id() == null) {
            throw new FalhaIntegracaoException(SERVICO, "resposta de POST /reservas fora do contrato");
        }
        return reserva;
    }

    public void confirmar(String reservaId) {
        try {
            restClient.post()
                    .uri("/reservas/{id}/confirmacao", reservaId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new FalhaIntegracaoException(SERVICO, "erro em POST /reservas/" + reservaId + "/confirmacao", e);
        }
    }

    public void liberar(String reservaId) {
        try {
            restClient.delete()
                    .uri("/reservas/{id}", reservaId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new FalhaIntegracaoException(SERVICO, "erro em DELETE /reservas/" + reservaId, e);
        }
    }

    /** Um corpo de erro ilegível não pode esconder o motivo do 404/409, por isso cai num erro sem detalhes. */
    private ContratoEstoque.Erro lerErro(HttpClientErrorException e) {
        try {
            ContratoEstoque.Erro erro = e.getResponseBodyAs(ContratoEstoque.Erro.class);
            return erro != null ? erro : new ContratoEstoque.Erro(null, null, null, null);
        } catch (RuntimeException corpoIlegivel) {
            return new ContratoEstoque.Erro(null, null, null, null);
        }
    }
}
