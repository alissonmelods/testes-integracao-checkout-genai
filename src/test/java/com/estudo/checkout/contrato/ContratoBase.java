package com.estudo.checkout.contrato;

import com.estudo.checkout.suporte.IntegracaoBase;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterEach;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

/**
 * Base dos testes de contrato (gerados pelo prompt 3, v1). Na curadoria, a infraestrutura própria da resposta
 * (WireMock estáticos, URLs e timeouts) foi trocada pela {@link IntegracaoBase}, comum a todas as suítes. A validação
 * do tráfego no {@code @AfterEach} é a da resposta original.
 */
abstract class ContratoBase extends IntegracaoBase {

    /** Maior que o timeout dos clients nos testes. */
    static final int ATRASO_ACIMA_DO_TIMEOUT_MS = TIMEOUT_MS * 2;

    /** Tudo o que o Pedidos enviou aos provedores, em qualquer teste, tem que estar de acordo com os contratos. */
    @AfterEach
    void todaRequisicaoEnviadaDeveObedecerAoContrato() {
        ContratoRegras.validarTrafego(ESTOQUE, PAGAMENTOS);
    }

    static ResponseDefinitionBuilder json(int status, String corpo) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(corpo);
    }

    static List<LoggedRequest> chamadas(WireMockServer servidor, RequestPatternBuilder padrao) {
        return servidor.findAll(padrao);
    }

    static int totalDeChamadas(WireMockServer servidor) {
        return servidor.getAllServeEvents().size();
    }
}
