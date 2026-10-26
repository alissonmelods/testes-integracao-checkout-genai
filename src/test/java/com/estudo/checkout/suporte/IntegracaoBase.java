package com.estudo.checkout.suporte;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Base dos testes de integração: sobe o serviço de Pedidos de verdade (Spring Boot em porta aleatória, chamado por
 * HTTP) e simula as APIs de Estoque e Pagamentos com WireMock, também em portas reais. Só as APIs externas são
 * simuladas: controller, service e clients HTTP rodam como em produção.
 * <p>
 * Os dois WireMock sobem uma vez e ficam de pé até o fim da execução, para que todas as classes de teste
 * compartilhem o mesmo contexto do Spring (as URLs não mudam). Antes de cada teste, stubs e histórico são zerados.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegracaoBase {

    /** Timeout dos clients nos testes: curto, para os cenários de lentidão não demorarem. */
    protected static final int TIMEOUT_MS = 400;

    protected static final WireMockServer ESTOQUE = new WireMockServer(options().dynamicPort());
    protected static final WireMockServer PAGAMENTOS = new WireMockServer(options().dynamicPort());

    static {
        ESTOQUE.start();
        PAGAMENTOS.start();
    }

    @DynamicPropertySource
    static void apontarParaOsSimuladores(DynamicPropertyRegistry registry) {
        registry.add("servicos.estoque.url", ESTOQUE::baseUrl);
        registry.add("servicos.pagamentos.url", PAGAMENTOS::baseUrl);
        registry.add("servicos.estoque.timeout-ms", () -> TIMEOUT_MS);
        registry.add("servicos.pagamentos.timeout-ms", () -> TIMEOUT_MS);
        registry.add("servicos.pagamentos.max-tentativas", () -> 3);
    }

    @Autowired
    protected TestRestTemplate api;

    @BeforeEach
    void zerarSimuladores() {
        ESTOQUE.resetAll();
        PAGAMENTOS.resetAll();
    }

    /** POST /pedidos com o corpo JSON informado. Respostas 4xx e 5xx não lançam exceção. */
    protected ResponseEntity<JsonNode> postarPedido(String corpoJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return api.postForEntity("/pedidos", new HttpEntity<>(corpoJson, headers), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> consultarPedido(String id) {
        return api.getForEntity("/pedidos/{id}", JsonNode.class, id);
    }
}
