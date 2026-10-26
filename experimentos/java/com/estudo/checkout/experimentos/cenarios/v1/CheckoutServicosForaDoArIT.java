package com.estudo.checkout.experimentos.cenarios.v1;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

/** Não usa a IntegracaoBase: aponta o serviço para portas onde ninguém está escutando. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Checkout: serviços externos fora do ar")
class CheckoutServicosForaDoArIT {

    @DynamicPropertySource
    static void apontarParaPortasSemServidor(DynamicPropertyRegistry registry) {
        registry.add("servicos.estoque.url", () -> "http://localhost:" + portaSemServidor());
        registry.add("servicos.estoque.timeout-ms", () -> 1000);
        registry.add("servicos.pagamentos.url", () -> "http://localhost:" + portaSemServidor());
        registry.add("servicos.pagamentos.timeout-ms", () -> 1000);
        registry.add("servicos.pagamentos.max-tentativas", () -> 3);
    }

    @Autowired
    private TestRestTemplate http;

    @Test
    @DisplayName("IN-01: Estoque com conexão recusada -> 503 FALHA_INTEGRACAO")
    void estoqueComConexaoRecusada() {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> requisicao = new HttpEntity<>("""
                {"clienteId":"cli-1","metodoPagamento":"PIX","itens":[{"sku":"SKU-1","quantidade":2}]}""",
                cabecalhos);

        ResponseEntity<String> resposta = http.exchange("/pedidos", HttpMethod.POST, requisicao, String.class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(503);
        assertThat(JsonPath.<String>read(resposta.getBody(), "$.status")).isEqualTo("FALHA_INTEGRACAO");
        assertThat(JsonPath.<String>read(resposta.getBody(), "$.motivo")).startsWith("estoque:");
    }

    private static int portaSemServidor() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
