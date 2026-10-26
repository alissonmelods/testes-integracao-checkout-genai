package com.estudo.checkout.experimentos.falhas.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "servicos.estoque.timeout-ms=1000",
        "servicos.pagamentos.timeout-ms=1000",
        "servicos.pagamentos.max-tentativas=3"
})
@AutoConfigureMockMvc
@DisplayName("POST /pedidos: Pagamentos fora do ar")
class CheckoutPagamentosForaDoArTest {

    @RegisterExtension
    static WireMockExtension estoque = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    /** Porta que acabou de ser liberada: ninguém escuta nela, então a conexão é recusada. */
    static final int PORTA_SEM_SERVIDOR = portaLivre();

    @DynamicPropertySource
    static void urlsDosServicos(DynamicPropertyRegistry registry) {
        registry.add("servicos.estoque.url", () -> "http://localhost:" + estoque.getPort());
        registry.add("servicos.pagamentos.url", () -> "http://localhost:" + PORTA_SEM_SERVIDOR);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @Test
    @DisplayName("FA-01 conexão recusada: esgota as tentativas, libera a reserva e responde FALHA_INTEGRACAO")
    void conexaoRecusada() throws Exception {
        estoque.stubFor(get(urlEqualTo("/produtos/SKU-1")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"sku\":\"SKU-1\",\"precoCentavos\":2500}")));
        estoque.stubFor(post(urlEqualTo("/reservas")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"RES-1\",\"status\":\"RESERVADA\"}")));
        estoque.stubFor(delete(urlEqualTo("/reservas/RES-1")).willReturn(aResponse().withStatus(204)));

        var resposta = mockMvc.perform(MockMvcRequestBuilders.post("/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clienteId":"cli-1","itens":[{"sku":"SKU-1","quantidade":2}],"metodoPagamento":"PIX"}"""))
                .andReturn().getResponse();
        JsonNode corpo = mapper.readTree(resposta.getContentAsString(StandardCharsets.UTF_8));

        assertThat(resposta.getStatus()).isEqualTo(503);
        assertThat(corpo.path("status").asText()).isEqualTo("FALHA_INTEGRACAO");
        assertThat(corpo.path("motivo").asText()).isEqualTo("pagamentos: indisponível após 3 tentativas");
        estoque.verify(1, postRequestedFor(urlEqualTo("/reservas")));
        estoque.verify(1, deleteRequestedFor(urlEqualTo("/reservas/RES-1")));
        estoque.verify(0, postRequestedFor(urlEqualTo("/reservas/RES-1/confirmacao")));
    }

    private static int portaLivre() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
