package com.estudo.checkout.experimentos.contrato.v1;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

@SpringBootTest(properties = {
        // timeouts menores só para os testes de timeout não demorarem; o resto do application.yml é o real
        "servicos.estoque.timeout-ms=1500",
        "servicos.pagamentos.timeout-ms=1500"
})
abstract class ContratoBase {

    /** Maior que o timeout configurado acima. */
    static final int ATRASO_ACIMA_DO_TIMEOUT_MS = 3000;

    static final WireMockServer ESTOQUE = new WireMockServer(options().dynamicPort());
    static final WireMockServer PAGAMENTOS = new WireMockServer(options().dynamicPort());

    static {
        ESTOQUE.start();
        PAGAMENTOS.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ESTOQUE.stop();
            PAGAMENTOS.stop();
        }));
    }

    @DynamicPropertySource
    static void apontarClientsParaOsProvedores(DynamicPropertyRegistry registry) {
        registry.add("servicos.estoque.url", ESTOQUE::baseUrl);
        registry.add("servicos.pagamentos.url", PAGAMENTOS::baseUrl);
    }

    @BeforeEach
    void limparProvedores() {
        ESTOQUE.resetAll();
        PAGAMENTOS.resetAll();
    }

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
