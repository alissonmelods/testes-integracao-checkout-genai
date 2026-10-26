package com.estudo.checkout.integracao.pagamentos;

import com.estudo.checkout.integracao.FabricaRestClient;
import com.estudo.checkout.integracao.FalhaIntegracaoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

@Component
public class PagamentosClient {

    private static final Logger log = LoggerFactory.getLogger(PagamentosClient.class);
    private static final String SERVICO = "pagamentos";

    private final RestClient restClient;
    private final int maxTentativas;

    public PagamentosClient(RestClient.Builder builder,
                            @Value("${servicos.pagamentos.url}") String url,
                            @Value("${servicos.pagamentos.timeout-ms}") long timeoutMs,
                            @Value("${servicos.pagamentos.max-tentativas}") int maxTentativas) {
        this.restClient = FabricaRestClient.criar(builder, url, Duration.ofMillis(timeoutMs));
        this.maxTentativas = maxTentativas;
    }

    /**
     * Pede a autorização do pagamento de um pedido.
     * <p>
     * Se a chamada falhar (timeout, falha de conexão ou erro HTTP), ela é repetida até {@code maxTentativas} vezes,
     * sempre com a mesma Idempotency-Key. Assim, se uma tentativa chegou a ser processada e só a resposta se perdeu,
     * a API de Pagamentos devolve a cobrança original em vez de cobrar de novo.
     *
     * @throws FalhaIntegracaoException quando as tentativas acabam ou quando a resposta está fora do contrato
     */
    public ContratoPagamentos.Pagamento autorizar(String pedidoId, long valorCentavos, String metodo, String tokenCartao) {
        ContratoPagamentos.NovoPagamento novoPagamento =
                new ContratoPagamentos.NovoPagamento(pedidoId, valorCentavos, metodo, tokenCartao);
        RestClientException ultimaFalha = null;
        for (int tentativa = 1; tentativa <= maxTentativas; tentativa++) {
            try {
                ContratoPagamentos.Pagamento pagamento = restClient.post()
                        .uri("/pagamentos")
                        .header("Idempotency-Key", chaveIdempotencia(pedidoId))
                        .body(novoPagamento)
                        .retrieve()
                        .body(ContratoPagamentos.Pagamento.class);
                return validarContrato(pagamento);
            } catch (HttpStatusCodeException | ResourceAccessException e) {
                ultimaFalha = e;
                log.warn("Tentativa {}/{} de pagamento do pedido {} falhou: {}",
                        tentativa, maxTentativas, pedidoId, e.getMessage());
            } catch (RestClientException e) {
                throw new FalhaIntegracaoException(SERVICO, "erro não transitório em POST /pagamentos", e);
            }
        }
        throw new FalhaIntegracaoException(SERVICO, "indisponível após " + maxTentativas + " tentativas", ultimaFalha);
    }

    /** A chave depende só do pedido, para que todas as tentativas de cobrar o mesmo pedido contem como uma só. */
    private String chaveIdempotencia(String pedidoId) {
        return "pedido-" + pedidoId;
    }

    private ContratoPagamentos.Pagamento validarContrato(ContratoPagamentos.Pagamento pagamento) {
        if (pagamento == null || pagamento.id() == null || pagamento.status() == null) {
            throw new FalhaIntegracaoException(SERVICO, "resposta de POST /pagamentos fora do contrato");
        }
        return pagamento;
    }
}
