> **Resposta da LLM, sem edição.** Prompt: [3-contrato-api/v1.md](../../prompts/3-contrato-api/v1.md) | Modelo: Claude Sonnet 5.5 | Data: 25/10/2026 | Código enviado: commit `4a9ccee`

---

## Testes de contrato: Pedidos x Estoque e Pedidos x Pagamentos

### Abordagem

São testes de contrato do lado do consumidor: o Pedidos consome as duas APIs e não temos o código dos provedores. Cada API é simulada por um WireMock que só responde o que o OpenAPI permite. Os clients reais (`EstoqueClient` e `PagamentosClient`) rodam dentro do contexto Spring real, com o mesmo `application.yml`, o mesmo `RestClient.Builder` e o mesmo Jackson. Os testes verificam três coisas:

1. **O que o Pedidos envia obedece ao contrato.** Isso cobre método e caminho, `Idempotency-Key` com 1 a 64 caracteres, `additionalProperties: false`, `minimum`, `minItems` e a regra do `tokenCartao`. Também garante que ele não chama operações que declara não usar (`GET /pagamentos/{id}`). A checagem roda em **todos** os testes, num `@AfterEach` (`ContratoRegras`).
2. **O Pedidos entende cada resposta prevista no contrato.** Entram todos os status de cada operação (200, 201, 204, 400, 404, 409, 422, 503), os exemplos do YAML, `motivo` nulo, 200 idempotente contra 201, campos extras ignorados e valores `int64`.
3. **A semântica escrita nas `description`.** A reserva é tudo ou nada. Confirmar e liberar são idempotentes. Reserva expirada responde 404. A `Idempotency-Key` é a mesma em todas as tentativas. Só se repete em 503 ou timeout, nunca em 4xx e nunca na reserva.

Dois testes (marcados com `@Tag("lacuna")`) foram escritos para **falhar com o código atual**, porque nesses casos o Pedidos viola o contrato de Pagamentos. Os detalhes estão no fim.

Não há dependência nova. Só usei JUnit 5, AssertJ, Jackson, Spring Boot Test e o WireMock que já estão no `pom.xml`.

### Arquivos

Todos ficam no pacote `com.estudo.checkout.contrato`, em `src/test/java` ou em `experimentos/java` (com `-Pexperimentos`).

#### `ContratoBase.java`

Sobe os dois WireMocks uma única vez por JVM, para o contexto Spring continuar em cache. Também aponta os clients para eles, limpa os stubs a cada teste e valida o tráfego enviado.

```java
package com.estudo.checkout.contrato;

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
```

#### `ContratoRegras.java`

As regras do OpenAPI (Estoque 1.4.0 e Pagamentos 2.1.0) viraram asserções sobre as requisições que chegaram aos provedores.

```java
package com.estudo.checkout.contrato;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regras de contratos/estoque-api.yaml (1.4.0) e contratos/pagamentos-api.yaml (2.1.0) transcritas para asserções
 * sobre o que o serviço de Pedidos ENVIA. Se o YAML mudar, esta classe precisa mudar junto.
 */
final class ContratoRegras {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ContratoRegras() {
    }

    static JsonNode lerJson(String texto) {
        try {
            return JSON.readTree(texto);
        } catch (IOException e) {
            throw new UncheckedIOException("corpo não é um JSON válido: " + texto, e);
        }
    }

    static Set<String> campos(JsonNode objeto) {
        Set<String> nomes = new TreeSet<>();
        objeto.fieldNames().forEachRemaining(nomes::add);
        return nomes;
    }

    static void validarTrafego(WireMockServer estoque, WireMockServer pagamentos) {
        for (ServeEvent evento : estoque.getAllServeEvents()) {
            validarRequisicaoAoEstoque(evento.getRequest());
        }
        for (ServeEvent evento : pagamentos.getAllServeEvents()) {
            validarRequisicaoAoPagamentos(evento.getRequest());
        }
    }

    private static void validarRequisicaoAoEstoque(LoggedRequest requisicao) {
        String operacao = operacao(requisicao);
        assertThat(operacao)
                .as("o Pedidos só pode chamar operações que existem no contrato de Estoque")
                .matches("GET /produtos/[^/]+|POST /reservas|POST /reservas/[^/]+/confirmacao|DELETE /reservas/[^/]+");
        if (operacao.equals("POST /reservas")) {
            validarNovaReserva(requisicao);
        }
    }

    /** NovaReserva e ItemReserva: additionalProperties false, pedidoId/sku minLength 1, itens minItems 1, quantidade minimum 1. */
    private static void validarNovaReserva(LoggedRequest requisicao) {
        assertThat(requisicao.getHeader("Content-Type")).as("Content-Type de POST /reservas").contains("application/json");
        JsonNode corpo = lerJson(requisicao.getBodyAsString());
        assertThat(campos(corpo)).as("campos de NovaReserva (additionalProperties: false)")
                .containsExactlyInAnyOrder("pedidoId", "itens");
        assertThat(textoNaoVazio(corpo.get("pedidoId"))).as("pedidoId: string com minLength 1").isTrue();
        JsonNode itens = corpo.get("itens");
        assertThat(itens.isArray() && itens.size() >= 1).as("itens: array com minItems 1").isTrue();
        for (JsonNode item : itens) {
            assertThat(campos(item)).as("campos de ItemReserva (additionalProperties: false)")
                    .containsExactlyInAnyOrder("sku", "quantidade");
            assertThat(textoNaoVazio(item.get("sku"))).as("sku: string com minLength 1").isTrue();
            assertThat(inteiroMinimo(item.get("quantidade"), 1)).as("quantidade: inteiro com minimum 1").isTrue();
        }
    }

    private static void validarRequisicaoAoPagamentos(LoggedRequest requisicao) {
        assertThat(operacao(requisicao))
                .as("o Pedidos só usa POST /pagamentos (GET /pagamentos/{id} não é usado por ele)")
                .isEqualTo("POST /pagamentos");
        assertThat(requisicao.getHeader("Content-Type")).as("Content-Type de POST /pagamentos").contains("application/json");

        String chave = requisicao.getHeader("Idempotency-Key");
        assertThat(chave).as("header Idempotency-Key é obrigatório").isNotNull();
        assertThat(chave.length()).as("Idempotency-Key: de 1 a 64 caracteres").isBetween(1, 64);

        JsonNode corpo = lerJson(requisicao.getBodyAsString());
        assertThat(campos(corpo)).as("campos de NovoPagamento (additionalProperties: false)")
                .contains("pedidoId", "valorCentavos", "metodo")
                .isSubsetOf("pedidoId", "valorCentavos", "metodo", "tokenCartao");
        assertThat(textoNaoVazio(corpo.get("pedidoId"))).as("pedidoId: string com minLength 1").isTrue();
        assertThat(inteiroMinimo(corpo.get("valorCentavos"), 1))
                .as("valorCentavos: inteiro int64 com minimum 1 (veio %s)", corpo.get("valorCentavos")).isTrue();

        String metodo = corpo.get("metodo").asText();
        assertThat(metodo).as("metodo: PIX ou CARTAO").isIn("PIX", "CARTAO");
        boolean temToken = corpo.has("tokenCartao");
        if (temToken) {
            assertThat(textoNaoVazio(corpo.get("tokenCartao")))
                    .as("tokenCartao: string com minLength 1 (null não é aceito)").isTrue();
        }
        if (metodo.equals("CARTAO")) {
            assertThat(temToken).as("tokenCartao é obrigatório quando metodo = CARTAO").isTrue();
        } else {
            assertThat(temToken).as("tokenCartao não pode ser enviado quando metodo = PIX").isFalse();
        }
    }

    private static String operacao(LoggedRequest requisicao) {
        String url = requisicao.getUrl();
        int query = url.indexOf('?');
        return requisicao.getMethod() + " " + (query >= 0 ? url.substring(0, query) : url);
    }

    private static boolean textoNaoVazio(JsonNode no) {
        return no != null && no.isTextual() && !no.asText().isEmpty();
    }

    private static boolean inteiroMinimo(JsonNode no, long minimo) {
        return no != null && no.isIntegralNumber() && no.asLong() >= minimo;
    }
}
```

#### `EstoqueContratoTest.java`

Contrato do Estoque: `GET /produtos/{sku}`, `POST /reservas`, `POST /reservas/{id}/confirmacao` e `DELETE /reservas/{id}`, pelo `EstoqueClient`.

```java
package com.estudo.checkout.contrato;

import com.estudo.checkout.integracao.FalhaIntegracaoException;
import com.estudo.checkout.integracao.estoque.ContratoEstoque;
import com.estudo.checkout.integracao.estoque.EstoqueClient;
import com.estudo.checkout.integracao.estoque.EstoqueInsuficienteException;
import com.estudo.checkout.integracao.estoque.ProdutoInexistenteException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EstoqueContratoTest extends ContratoBase {

    private static final String PEDIDO_ID = "7b0c2f6e-1d4a-4c55-9a0e-3f1f0f6b2a10";
    private static final List<ContratoEstoque.ItemReserva> ITENS =
            List.of(new ContratoEstoque.ItemReserva("LIV-001", 2));

    // Corpos baseados nos exemplos do próprio contrato (estoque-api.yaml)
    private static final String PRODUTO = """
            {"sku":"LIV-001","nome":"Livro Testes de Software","precoCentavos":8990,"disponivel":10}""";
    private static final String RESERVA = """
            {"id":"res-123","pedidoId":"7b0c2f6e-1d4a-4c55-9a0e-3f1f0f6b2a10","status":"RESERVADA","itens":[{"sku":"LIV-001","quantidade":2}]}""";
    private static final String RESERVA_CONFIRMADA = RESERVA.replace("RESERVADA", "CONFIRMADA");
    private static final String CORPO_NOVA_RESERVA = """
            {"pedidoId":"7b0c2f6e-1d4a-4c55-9a0e-3f1f0f6b2a10","itens":[{"sku":"LIV-001","quantidade":2}]}""";
    private static final String ERRO_PRODUTO_INEXISTENTE = """
            {"codigo":"PRODUTO_INEXISTENTE","mensagem":"Produto XYZ-999 nao encontrado","sku":"XYZ-999"}""";
    private static final String ERRO_ESTOQUE_INSUFICIENTE = """
            {"codigo":"ESTOQUE_INSUFICIENTE","mensagem":"Quantidade indisponivel para MOU-003","sku":"MOU-003","disponivel":1}""";
    private static final String ERRO_GENERICO = """
            {"codigo":"RESERVA_INEXISTENTE","mensagem":"erro de exemplo"}""";

    @Autowired
    private EstoqueClient client;

    // ------------------------------------------------------------------ GET /produtos/:sku

    @Test
    @DisplayName("GET /produtos/{sku} 200: lê precoCentavos e ignora campos extras (nome, disponivel)")
    void produtoEncontrado() {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/LIV-001")).willReturn(json(200, PRODUTO)));

        ContratoEstoque.Produto produto = client.consultarProduto("LIV-001");

        assertThat(produto.sku()).isEqualTo("LIV-001");
        assertThat(produto.precoCentavos()).isEqualTo(8990L);
        ESTOQUE.verify(1, getRequestedFor(urlPathEqualTo("/produtos/LIV-001")));
    }

    @Test
    @DisplayName("GET /produtos/{sku} 200: preço 0 é válido no contrato (minimum 0)")
    void produtoComPrecoZero() {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/BRD-000")).willReturn(json(200,
                "{\"sku\":\"BRD-000\",\"nome\":\"Brinde\",\"precoCentavos\":0,\"disponivel\":3}")));

        assertThat(client.consultarProduto("BRD-000").precoCentavos()).isZero();
    }

    @Test
    @DisplayName("GET /produtos/{sku} 200: precoCentavos é int64 e não pode estourar um int")
    void produtoComPrecoInt64() {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/CAR-777")).willReturn(json(200,
                "{\"sku\":\"CAR-777\",\"nome\":\"Carro\",\"precoCentavos\":9000000000,\"disponivel\":1}")));

        assertThat(client.consultarProduto("CAR-777").precoCentavos()).isEqualTo(9_000_000_000L);
    }

    @Test
    @DisplayName("GET /produtos/{sku} 404: SKU não cadastrado vira ProdutoInexistenteException")
    void produtoNaoCadastrado() {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/XYZ-999")).willReturn(json(404, ERRO_PRODUTO_INEXISTENTE)));

        assertThatThrownBy(() -> client.consultarProduto("XYZ-999"))
                .isInstanceOf(ProdutoInexistenteException.class)
                .hasMessageContaining("XYZ-999");
    }

    @Test
    @DisplayName("GET /produtos/{sku} 404 sem corpo continua sendo produto inexistente")
    void produtoNaoCadastradoSemCorpo() {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/XYZ-999")).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> client.consultarProduto("XYZ-999"))
                .isInstanceOf(ProdutoInexistenteException.class);
    }

    // Status que o contrato não prevê para esta operação: falha de integração, sem nova tentativa.
    @ParameterizedTest(name = "GET /produtos respondendo {0}")
    @ValueSource(ints = {400, 500, 502, 503})
    void produtoComStatusForaDoContrato(int status) {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/LIV-001")).willReturn(json(status, ERRO_GENERICO)));

        assertThatThrownBy(() -> client.consultarProduto("LIV-001"))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageStartingWith("estoque:");
        ESTOQUE.verify(1, getRequestedFor(urlPathEqualTo("/produtos/LIV-001")));
    }

    // 89.9: o application.yml proíbe truncar centavos fracionados em silêncio (accept-float-as-int: false).
    @ParameterizedTest(name = "precoCentavos = {0}")
    @ValueSource(strings = {"-1", "89.9", "null"})
    void produtoComPrecoForaDoContrato(String preco) {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/LIV-001")).willReturn(json(200,
                "{\"sku\":\"LIV-001\",\"nome\":\"Livro\",\"precoCentavos\":%s,\"disponivel\":1}".formatted(preco))));

        assertThatThrownBy(() -> client.consultarProduto("LIV-001"))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageStartingWith("estoque:");
    }

    @ParameterizedTest(name = "corpo 200 = [{0}]")
    @ValueSource(strings = {"{\"sku\":\"LIV-001\",\"nome\":\"Livro\",\"disponivel\":1}", ""})
    void produtoSemPrecoOuSemCorpo(String corpo) {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/LIV-001")).willReturn(json(200, corpo)));

        assertThatThrownBy(() -> client.consultarProduto("LIV-001"))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageContaining("fora do contrato");
    }

    @Test
    @DisplayName("GET /produtos/{sku}: timeout vira FalhaIntegracaoException, sem nova tentativa")
    void produtoComTimeout() {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/LIV-001"))
                .willReturn(json(200, PRODUTO).withFixedDelay(ATRASO_ACIMA_DO_TIMEOUT_MS)));

        assertThatThrownBy(() -> client.consultarProduto("LIV-001"))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageStartingWith("estoque:");
        ESTOQUE.verify(1, getRequestedFor(urlPathEqualTo("/produtos/LIV-001")));
    }

    // ------------------------------------------------------------------ POST /reservas

    @Test
    @DisplayName("POST /reservas 201: envia exatamente NovaReserva e devolve o id da reserva")
    void reservaCriada() {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(201, RESERVA)));

        ContratoEstoque.Reserva reserva = client.reservar(PEDIDO_ID, ITENS);

        assertThat(reserva.id()).isEqualTo("res-123");
        assertThat(reserva.status()).isEqualTo("RESERVADA");
        ESTOQUE.verify(1, postRequestedFor(urlPathEqualTo("/reservas"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson(CORPO_NOVA_RESERVA, false, false)));
    }

    @Test
    @DisplayName("POST /reservas 409: sem estoque (tudo ou nada) vira EstoqueInsuficienteException com sku e disponivel")
    void reservaSemEstoque() {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(409, ERRO_ESTOQUE_INSUFICIENTE)));

        assertThatThrownBy(() -> client.reservar(PEDIDO_ID, ITENS))
                .isInstanceOf(EstoqueInsuficienteException.class)
                .hasMessageContaining("MOU-003")
                .hasMessageEndingWith(": 1)");
        ESTOQUE.verify(1, postRequestedFor(urlPathEqualTo("/reservas")));
    }

    @Test
    @DisplayName("POST /reservas 404: SKU não cadastrado vira ProdutoInexistenteException com o sku do corpo de erro")
    void reservaComProdutoInexistente() {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(404, ERRO_PRODUTO_INEXISTENTE)));

        assertThatThrownBy(() -> client.reservar(PEDIDO_ID, ITENS))
                .isInstanceOf(ProdutoInexistenteException.class)
                .hasMessageContaining("XYZ-999");
    }

    @Test
    @DisplayName("POST /reservas 404 e 409 com corpo de erro ilegível: o motivo do status não pode ser escondido")
    void reservaComCorpoDeErroIlegivel() {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(409, "isto nao e json")));
        assertThatThrownBy(() -> client.reservar(PEDIDO_ID, ITENS))
                .isInstanceOf(EstoqueInsuficienteException.class);

        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(404, "isto nao e json")));
        assertThatThrownBy(() -> client.reservar(PEDIDO_ID, ITENS))
                .isInstanceOf(ProdutoInexistenteException.class);
    }

    @Test
    @DisplayName("POST /reservas 422: requisição inválida vira FalhaIntegracaoException")
    void reservaRejeitadaComDadosInvalidos() {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(
                json(422, "{\"codigo\":\"DADOS_INVALIDOS\",\"mensagem\":\"lista de itens vazia\"}")));

        assertThatThrownBy(() -> client.reservar(PEDIDO_ID, ITENS))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageContaining("POST /reservas");
    }

    // A reserva não é idempotente no contrato: repetir poderia reservar duas vezes.
    @ParameterizedTest(name = "POST /reservas respondendo {0}")
    @ValueSource(ints = {500, 502, 503, 504})
    void reservaComErroDoServidorNaoERepetida(int status) {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(status, ERRO_GENERICO)));

        assertThatThrownBy(() -> client.reservar(PEDIDO_ID, ITENS))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageStartingWith("estoque:");
        ESTOQUE.verify(1, postRequestedFor(urlPathEqualTo("/reservas")));
    }

    @Test
    @DisplayName("POST /reservas: timeout vira FalhaIntegracaoException, sem nova tentativa")
    void reservaComTimeoutNaoERepetida() {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas"))
                .willReturn(json(201, RESERVA).withFixedDelay(ATRASO_ACIMA_DO_TIMEOUT_MS)));

        assertThatThrownBy(() -> client.reservar(PEDIDO_ID, ITENS))
                .isInstanceOf(FalhaIntegracaoException.class);
        ESTOQUE.verify(1, postRequestedFor(urlPathEqualTo("/reservas")));
    }

    @ParameterizedTest(name = "corpo 201 = [{0}]")
    @ValueSource(strings = {"{\"pedidoId\":\"x\",\"status\":\"RESERVADA\",\"itens\":[]}", ""})
    void reservaCriadaSemIdEstaForaDoContrato(String corpo) {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(201, corpo)));

        assertThatThrownBy(() -> client.reservar(PEDIDO_ID, ITENS))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageContaining("fora do contrato");
    }

    // ------------------------------------------------------------------ POST /reservas/:id/confirmacao

    @Test
    @DisplayName("POST /reservas/{id}/confirmacao 200: aceita a confirmação e a repetição idempotente")
    void confirmacaoEIdempotente() {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas/res-123/confirmacao"))
                .willReturn(json(200, RESERVA_CONFIRMADA)));

        assertThatCode(() -> {
            client.confirmar("res-123");
            client.confirmar("res-123");
        }).doesNotThrowAnyException();
        ESTOQUE.verify(2, postRequestedFor(urlPathEqualTo("/reservas/res-123/confirmacao")));
    }

    // 404: reserva inexistente ou expirada (15 minutos). 409: reserva já liberada.
    @ParameterizedTest(name = "confirmação respondendo {0}")
    @ValueSource(ints = {404, 409, 500, 503})
    void confirmacaoRejeitada(int status) {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas/res-123/confirmacao"))
                .willReturn(json(status, ERRO_GENERICO)));

        assertThatThrownBy(() -> client.confirmar("res-123"))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageStartingWith("estoque:")
                .hasMessageContaining("res-123");
        ESTOQUE.verify(1, postRequestedFor(urlPathEqualTo("/reservas/res-123/confirmacao")));
    }

    // ------------------------------------------------------------------ DELETE /reservas/:id

    @Test
    @DisplayName("DELETE /reservas/{id} 204: libera a reserva e aceita a repetição idempotente")
    void liberacaoEIdempotente() {
        ESTOQUE.stubFor(delete(urlPathEqualTo("/reservas/res-123")).willReturn(aResponse().withStatus(204)));

        assertThatCode(() -> {
            client.liberar("res-123");
            client.liberar("res-123");
        }).doesNotThrowAnyException();
        ESTOQUE.verify(2, deleteRequestedFor(urlPathEqualTo("/reservas/res-123")));
    }

    // 404: reserva inexistente ou expirada. 409: reserva já confirmada.
    @ParameterizedTest(name = "liberação respondendo {0}")
    @ValueSource(ints = {404, 409, 500, 503})
    void liberacaoRejeitada(int status) {
        ESTOQUE.stubFor(delete(urlPathEqualTo("/reservas/res-123")).willReturn(json(status, ERRO_GENERICO)));

        assertThatThrownBy(() -> client.liberar("res-123"))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageStartingWith("estoque:")
                .hasMessageContaining("res-123");
        ESTOQUE.verify(1, deleteRequestedFor(urlPathEqualTo("/reservas/res-123")));
    }
}
```

#### `PagamentosContratoTest.java`

Contrato de Pagamentos: `POST /pagamentos`, pelo `PagamentosClient`. O foco é a idempotência, as tentativas e a diferença entre 200 e 201.

```java
package com.estudo.checkout.contrato;

import com.estudo.checkout.integracao.FalhaIntegracaoException;
import com.estudo.checkout.integracao.pagamentos.ContratoPagamentos;
import com.estudo.checkout.integracao.pagamentos.PagamentosClient;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PagamentosContratoTest extends ContratoBase {

    private static final String PEDIDO_ID = "7b0c2f6e-1d4a-4c55-9a0e-3f1f0f6b2a10";
    private static final String CHAVE = "pedido-" + PEDIDO_ID;

    // Corpos baseados nos exemplos do próprio contrato (pagamentos-api.yaml)
    private static final String APROVADO = """
            {"id":"pag-001","pedidoId":"7b0c2f6e-1d4a-4c55-9a0e-3f1f0f6b2a10","valorCentavos":17980,"metodo":"PIX","status":"APROVADO","motivo":null}""";
    private static final String RECUSADO = """
            {"id":"pag-002","pedidoId":"7b0c2f6e-1d4a-4c55-9a0e-3f1f0f6b2a10","valorCentavos":17980,"metodo":"CARTAO","status":"RECUSADO","motivo":"%s"}""";
    private static final String INDISPONIVEL = """
            {"codigo":"INDISPONIVEL","mensagem":"Servico temporariamente indisponivel"}""";
    private static final String CORPO_PIX = """
            {"pedidoId":"7b0c2f6e-1d4a-4c55-9a0e-3f1f0f6b2a10","valorCentavos":17980,"metodo":"PIX"}""";
    private static final String CORPO_CARTAO = """
            {"pedidoId":"7b0c2f6e-1d4a-4c55-9a0e-3f1f0f6b2a10","valorCentavos":17980,"metodo":"CARTAO","tokenCartao":"tok_visa_4242"}""";

    @Autowired
    private PagamentosClient client;

    @Value("${servicos.pagamentos.max-tentativas}")
    private int maxTentativas;

    // ------------------------------------------------------------------ requisição e resposta 201

    @Test
    @DisplayName("POST /pagamentos 201 (PIX): Idempotency-Key e NovoPagamento sem tokenCartao; lê id, status e motivo nulo")
    void pixAprovado() {
        stubPagamento(json(201, APROVADO));

        ContratoPagamentos.Pagamento pagamento = client.autorizar(PEDIDO_ID, 17980, "PIX", null);

        assertThat(pagamento.id()).isEqualTo("pag-001");
        assertThat(pagamento.status()).isEqualTo(ContratoPagamentos.StatusPagamento.APROVADO);
        assertThat(pagamento.motivo()).isNull();
        PAGAMENTOS.verify(1, postRequestedFor(urlPathEqualTo("/pagamentos"))
                .withHeader("Idempotency-Key", equalTo(CHAVE))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson(CORPO_PIX, false, false)));
    }

    @Test
    @DisplayName("POST /pagamentos (CARTAO): envia o tokenCartao e lê a recusa como resultado de negócio")
    void cartaoEnviaOToken() {
        stubPagamento(json(201, RECUSADO.formatted("SALDO_INSUFICIENTE")));

        ContratoPagamentos.Pagamento pagamento = client.autorizar(PEDIDO_ID, 17980, "CARTAO", "tok_visa_4242");

        assertThat(pagamento.id()).isEqualTo("pag-002");
        assertThat(pagamento.status()).isEqualTo(ContratoPagamentos.StatusPagamento.RECUSADO);
        PAGAMENTOS.verify(1, postRequestedFor(urlPathEqualTo("/pagamentos"))
                .withHeader("Idempotency-Key", equalTo(CHAVE))
                .withRequestBody(equalToJson(CORPO_CARTAO, false, false)));
    }

    // Recusa é resultado de negócio (HTTP 201): não lança exceção e não é repetida.
    @ParameterizedTest(name = "recusa por {0}")
    @ValueSource(strings = {"SALDO_INSUFICIENTE", "CARTAO_BLOQUEADO", "LIMITE_PIX_EXCEDIDO", "SUSPEITA_DE_FRAUDE"})
    void recusaNaoEErro(String motivo) {
        stubPagamento(json(201, RECUSADO.formatted(motivo)));

        ContratoPagamentos.Pagamento pagamento = client.autorizar(PEDIDO_ID, 17980, "CARTAO", "tok_visa_4242");

        assertThat(pagamento.status()).isEqualTo(ContratoPagamentos.StatusPagamento.RECUSADO);
        assertThat(pagamento.motivo()).isEqualTo(motivo);
        assertThat(tentativas()).hasSize(1);
    }

    @Test
    @DisplayName("valorCentavos é int64: valor acima de 2^31 vai como número inteiro")
    void valorCentavosInt64() {
        stubPagamento(json(201, APROVADO));

        client.autorizar(PEDIDO_ID, 5_000_000_000L, "PIX", null);

        PAGAMENTOS.verify(1, postRequestedFor(urlPathEqualTo("/pagamentos"))
                .withRequestBody(matchingJsonPath("$.valorCentavos", equalTo("5000000000"))));
    }

    // ------------------------------------------------------------------ idempotência

    @Test
    @DisplayName("POST /pagamentos 200: repetição idempotente devolve o pagamento original e conta como sucesso")
    void repeticaoIdempotenteResponde200() {
        stubPagamento(json(200, APROVADO));

        ContratoPagamentos.Pagamento pagamento = client.autorizar(PEDIDO_ID, 17980, "PIX", null);

        assertThat(pagamento.id()).isEqualTo("pag-001");
        assertThat(pagamento.status()).isEqualTo(ContratoPagamentos.StatusPagamento.APROVADO);
        assertThat(tentativas()).hasSize(1);
    }

    @Test
    @DisplayName("Duas chamadas do mesmo pedido (201 e depois 200) reenviam a mesma Idempotency-Key e o mesmo corpo")
    void mesmoPedidoReenviaMesmaChaveEMesmoCorpo() {
        stubSequencia(json(201, APROVADO), json(200, APROVADO));

        ContratoPagamentos.Pagamento primeiro = client.autorizar(PEDIDO_ID, 17980, "PIX", null);
        ContratoPagamentos.Pagamento segundo = client.autorizar(PEDIDO_ID, 17980, "PIX", null);

        assertThat(segundo).isEqualTo(primeiro);
        assertMesmaChaveEMesmoCorpo(tentativas(), 2);
    }

    @Test
    @DisplayName("Pedidos diferentes usam Idempotency-Key diferentes (senão o provedor responderia 409)")
    void pedidosDiferentesUsamChavesDiferentes() {
        stubPagamento(json(201, APROVADO));

        client.autorizar(PEDIDO_ID, 17980, "PIX", null);
        client.autorizar("a1b2c3d4-0000-4000-8000-000000000001", 17980, "PIX", null);

        List<String> chaves = tentativas().stream().map(r -> r.getHeader("Idempotency-Key")).toList();
        assertThat(chaves).hasSize(2).doesNotHaveDuplicates();
    }

    // ------------------------------------------------------------------ indisponibilidade e tentativas

    // O contrato só documenta 503, mas o client trata todo 5xx como transitório. É seguro porque a chave é a mesma.
    @ParameterizedTest(name = "{0} na 1a tentativa e sucesso na 2a")
    @ValueSource(ints = {500, 502, 503, 504})
    void falhaTransitoriaEDepoisSucessoRepeteComAMesmaChave(int status) {
        stubSequencia(json(status, INDISPONIVEL), json(201, APROVADO));

        ContratoPagamentos.Pagamento pagamento = client.autorizar(PEDIDO_ID, 17980, "PIX", null);

        assertThat(pagamento.status()).isEqualTo(ContratoPagamentos.StatusPagamento.APROVADO);
        assertMesmaChaveEMesmoCorpo(tentativas(), 2);
    }

    @Test
    @DisplayName("503 em todas as tentativas menos a última: a última ainda é feita e vale")
    void sucessoSomenteNaUltimaTentativa() {
        ResponseDefinitionBuilder[] respostas = new ResponseDefinitionBuilder[maxTentativas];
        for (int i = 0; i < maxTentativas - 1; i++) {
            respostas[i] = json(503, INDISPONIVEL);
        }
        respostas[maxTentativas - 1] = json(201, APROVADO);
        stubSequencia(respostas);

        ContratoPagamentos.Pagamento pagamento = client.autorizar(PEDIDO_ID, 17980, "PIX", null);

        assertThat(pagamento.status()).isEqualTo(ContratoPagamentos.StatusPagamento.APROVADO);
        assertMesmaChaveEMesmoCorpo(tentativas(), maxTentativas);
    }

    @Test
    @DisplayName("503 em todas as tentativas: desiste ao atingir max-tentativas, sempre com a mesma Idempotency-Key")
    void esgotaAsTentativas() {
        stubPagamento(json(503, INDISPONIVEL));

        assertThatThrownBy(() -> client.autorizar(PEDIDO_ID, 17980, "PIX", null))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageStartingWith("pagamentos:")
                .hasMessageContaining(maxTentativas + " tentativas");
        assertMesmaChaveEMesmoCorpo(tentativas(), maxTentativas);
    }

    @Test
    @DisplayName("Timeout na 1a tentativa: repete com a mesma chave e aceita o sucesso da 2a")
    void timeoutEDepoisSucesso() {
        stubSequencia(json(201, APROVADO).withFixedDelay(ATRASO_ACIMA_DO_TIMEOUT_MS), json(201, APROVADO));

        ContratoPagamentos.Pagamento pagamento = client.autorizar(PEDIDO_ID, 17980, "PIX", null);

        assertThat(pagamento.status()).isEqualTo(ContratoPagamentos.StatusPagamento.APROVADO);
        assertMesmaChaveEMesmoCorpo(tentativas(), 2);
    }

    @Test
    @DisplayName("Timeout em todas as tentativas: FalhaIntegracaoException depois de max-tentativas")
    void timeoutEmTodasAsTentativas() {
        stubPagamento(json(201, APROVADO).withFixedDelay(ATRASO_ACIMA_DO_TIMEOUT_MS));

        assertThatThrownBy(() -> client.autorizar(PEDIDO_ID, 17980, "PIX", null))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageContaining(maxTentativas + " tentativas");
        assertMesmaChaveEMesmoCorpo(tentativas(), maxTentativas);
    }

    // ------------------------------------------------------------------ erros que não se repetem

    // 4xx: a próxima tentativa receberia a mesma resposta, então não se repete.
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "400, IDEMPOTENCY_KEY_INVALIDA",
            "409, CHAVE_IDEMPOTENCIA_REUTILIZADA",
            "422, DADOS_INVALIDOS"})
    void erroDoClienteNaoERepetido(int status, String codigo) {
        stubPagamento(json(status, "{\"codigo\":\"" + codigo + "\",\"mensagem\":\"erro de exemplo\"}"));

        assertThatThrownBy(() -> client.autorizar(PEDIDO_ID, 17980, "PIX", null))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageStartingWith("pagamentos:")
                .hasCauseInstanceOf(HttpClientErrorException.class);
        assertThat(tentativas()).hasSize(1);
    }

    static Stream<String> respostasForaDoContrato() {
        return Stream.of(
                // sem id
                "{\"pedidoId\":\"x\",\"valorCentavos\":17980,\"metodo\":\"PIX\",\"status\":\"APROVADO\"}",
                // sem status
                "{\"id\":\"pag-001\",\"pedidoId\":\"x\",\"valorCentavos\":17980,\"metodo\":\"PIX\"}",
                // status fora do enum APROVADO/RECUSADO
                "{\"id\":\"pag-001\",\"pedidoId\":\"x\",\"valorCentavos\":17980,\"metodo\":\"PIX\",\"status\":\"PENDENTE\"}",
                // sem corpo
                "");
    }

    @ParameterizedTest(name = "resposta 201 fora do contrato: [{0}]")
    @MethodSource("respostasForaDoContrato")
    void respostaForaDoContratoNaoEAceitaNemRepetida(String corpo) {
        stubPagamento(json(201, corpo));

        assertThatThrownBy(() -> client.autorizar(PEDIDO_ID, 17980, "PIX", null))
                .isInstanceOf(FalhaIntegracaoException.class)
                .hasMessageStartingWith("pagamentos:");
        assertThat(tentativas()).hasSize(1);
    }

    // ------------------------------------------------------------------ apoio

    private void stubPagamento(ResponseDefinitionBuilder resposta) {
        PAGAMENTOS.stubFor(post(urlPathEqualTo("/pagamentos")).willReturn(resposta));
    }

    /** Uma resposta por tentativa, na ordem (cenário do WireMock). */
    private void stubSequencia(ResponseDefinitionBuilder... respostas) {
        String estado = Scenario.STARTED;
        for (int i = 0; i < respostas.length; i++) {
            String proximo = "tentativa-" + (i + 2);
            PAGAMENTOS.stubFor(post(urlPathEqualTo("/pagamentos"))
                    .inScenario("tentativas")
                    .whenScenarioStateIs(estado)
                    .willSetStateTo(proximo)
                    .willReturn(respostas[i]));
            estado = proximo;
        }
    }

    private List<LoggedRequest> tentativas() {
        return chamadas(PAGAMENTOS, postRequestedFor(urlPathEqualTo("/pagamentos")));
    }

    /** É a mesma chave e o mesmo corpo em toda tentativa que faz o provedor contar a repetição como uma cobrança só. */
    private void assertMesmaChaveEMesmoCorpo(List<LoggedRequest> requisicoes, int quantidadeEsperada) {
        assertThat(requisicoes).hasSize(quantidadeEsperada);
        assertThat(requisicoes.stream().map(r -> r.getHeader("Idempotency-Key")).toList()).containsOnly(CHAVE);
        assertThat(requisicoes.stream().map(LoggedRequest::getBodyAsString).distinct().toList()).hasSize(1);
    }
}
```

#### `CheckoutContratoTest.java`

Os dois contratos juntos, pelo `PedidoService`: a ordem e os dados que passam de uma API para a outra, e a compensação (liberar a reserva) quando a cobrança não acontece. Os corpos de resposta são os exemplos dos contratos. O Pedidos só lê `id`, `status` e `motivo`, então o `pedidoId` dentro deles é fixo.

```java
package com.estudo.checkout.contrato;

import com.estudo.checkout.pedido.MetodoPagamento;
import com.estudo.checkout.pedido.NovoPedidoRequest;
import com.estudo.checkout.pedido.Pedido;
import com.estudo.checkout.pedido.PedidoService;
import com.estudo.checkout.pedido.StatusPedido;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

class CheckoutContratoTest extends ContratoBase {

    private static final String RESERVA = """
            {"id":"res-123","pedidoId":"pedido-do-teste","status":"RESERVADA","itens":[{"sku":"LIV-001","quantidade":2}]}""";
    private static final String RESERVA_CONFIRMADA = RESERVA.replace("RESERVADA", "CONFIRMADA");
    private static final String APROVADO = """
            {"id":"pag-001","pedidoId":"pedido-do-teste","valorCentavos":17980,"metodo":"PIX","status":"APROVADO","motivo":null}""";
    private static final String RECUSADO = """
            {"id":"pag-002","pedidoId":"pedido-do-teste","valorCentavos":17980,"metodo":"PIX","status":"RECUSADO","motivo":"SALDO_INSUFICIENTE"}""";
    private static final String INDISPONIVEL = """
            {"codigo":"INDISPONIVEL","mensagem":"Servico temporariamente indisponivel"}""";
    private static final String ERRO_ESTOQUE = """
            {"codigo":"RESERVA_INEXISTENTE","mensagem":"erro de exemplo"}""";
    private static final String ERRO_ESTOQUE_INSUFICIENTE = """
            {"codigo":"ESTOQUE_INSUFICIENTE","mensagem":"Quantidade indisponivel para MOU-003","sku":"MOU-003","disponivel":1}""";
    private static final String ERRO_PRODUTO_INEXISTENTE = """
            {"codigo":"PRODUTO_INEXISTENTE","mensagem":"Produto XYZ-999 nao encontrado","sku":"XYZ-999"}""";

    private static final NovoPedidoRequest.Item LIVRO = new NovoPedidoRequest.Item("LIV-001", 2);
    private static final NovoPedidoRequest.Item MOUSE = new NovoPedidoRequest.Item("MOU-003", 1);

    @Autowired
    private PedidoService pedidoService;

    @Value("${servicos.pagamentos.max-tentativas}")
    private int maxTentativas;

    // ------------------------------------------------------------------ caminho feliz

    @Test
    @DisplayName("Checkout aprovado: consulta preços, reserva, cobra o total e confirma, tudo com o mesmo pedidoId")
    void checkoutAprovado() {
        stubProduto("LIV-001", 8990);
        stubProduto("MOU-003", 4500);
        stubReserva();
        stubPagamento(json(201, APROVADO));
        stubConfirmacao(200);

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, LIVRO, MOUSE));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.CONFIRMADO);
        assertThat(pedido.getTotalCentavos()).isEqualTo(22480L); // 2 x 8990 + 1 x 4500
        assertThat(pedido.getReservaId()).isEqualTo("res-123");
        assertThat(pedido.getPagamentoId()).isEqualTo("pag-001");

        ESTOQUE.verify(1, getRequestedFor(urlPathEqualTo("/produtos/LIV-001")));
        ESTOQUE.verify(1, getRequestedFor(urlPathEqualTo("/produtos/MOU-003")));

        JsonNode reserva = corpoDaUnicaChamada(ESTOQUE, reservas());
        assertThat(reserva.get("pedidoId").asText()).isEqualTo(pedido.getId());
        assertThat(reserva.get("itens").size()).isEqualTo(2);
        assertThat(reserva.get("itens").get(0).get("sku").asText()).isEqualTo("LIV-001");
        assertThat(reserva.get("itens").get(0).get("quantidade").asInt()).isEqualTo(2);
        assertThat(reserva.get("itens").get(1).get("sku").asText()).isEqualTo("MOU-003");
        assertThat(reserva.get("itens").get(1).get("quantidade").asInt()).isEqualTo(1);

        JsonNode cobranca = corpoDaUnicaChamada(PAGAMENTOS, cobrancas());
        assertThat(cobranca.get("pedidoId").asText()).isEqualTo(pedido.getId());
        assertThat(cobranca.get("valorCentavos").asLong()).isEqualTo(22480L);
        assertThat(cobranca.get("metodo").asText()).isEqualTo("PIX");
        PAGAMENTOS.verify(1, cobrancas().withHeader("Idempotency-Key", equalTo("pedido-" + pedido.getId())));

        ESTOQUE.verify(1, postRequestedFor(urlPathEqualTo("/reservas/res-123/confirmacao")));
        ESTOQUE.verify(0, liberacoes());
    }

    @Test
    @DisplayName("Checkout com CARTAO: o tokenCartao do pedido vai para o Pagamentos (obrigatório no contrato)")
    void checkoutComCartaoEnviaOToken() {
        stubProduto("LIV-001", 8990);
        stubReserva();
        stubPagamento(json(201, APROVADO));
        stubConfirmacao(200);

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.CARTAO, "tok_visa_4242", LIVRO));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.CONFIRMADO);
        JsonNode cobranca = corpoDaUnicaChamada(PAGAMENTOS, cobrancas());
        assertThat(cobranca.get("metodo").asText()).isEqualTo("CARTAO");
        assertThat(cobranca.get("tokenCartao").asText()).isEqualTo("tok_visa_4242");
        assertThat(cobranca.get("valorCentavos").asLong()).isEqualTo(17980L);
    }

    // ------------------------------------------------------------------ compensação e falhas

    @Test
    @DisplayName("Pagamento recusado (201 + RECUSADO): devolve a reserva ao estoque e não confirma")
    void pagamentoRecusadoLiberaAReserva() {
        stubProduto("LIV-001", 8990);
        stubReserva();
        stubPagamento(json(201, RECUSADO));
        stubLiberacao(204);

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, LIVRO));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.PAGAMENTO_RECUSADO);
        assertThat(pedido.getMotivo()).isEqualTo("SALDO_INSUFICIENTE");
        ESTOQUE.verify(1, deleteRequestedFor(urlPathEqualTo("/reservas/res-123")));
        ESTOQUE.verify(0, confirmacoes());
        assertThat(chamadas(PAGAMENTOS, cobrancas())).hasSize(1);
    }

    @Test
    @DisplayName("Liberação da reserva falha (500) depois da recusa: o resultado do pedido não muda e não há nova tentativa")
    void falhaNaLiberacaoNaoMudaOResultado() {
        stubProduto("LIV-001", 8990);
        stubReserva();
        stubPagamento(json(201, RECUSADO));
        ESTOQUE.stubFor(delete(urlPathEqualTo("/reservas/res-123")).willReturn(json(500, ERRO_ESTOQUE)));

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, LIVRO));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.PAGAMENTO_RECUSADO);
        ESTOQUE.verify(1, liberacoes());
    }

    @Test
    @DisplayName("Pagamentos 503 em todas as tentativas: FALHA_INTEGRACAO, mesma Idempotency-Key e reserva liberada")
    void pagamentosIndisponivelLiberaAReserva() {
        stubProduto("LIV-001", 8990);
        stubReserva();
        stubPagamento(json(503, INDISPONIVEL));
        stubLiberacao(204);

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, LIVRO));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.FALHA_INTEGRACAO);
        List<LoggedRequest> tentativas = chamadas(PAGAMENTOS, cobrancas());
        assertThat(tentativas).hasSize(maxTentativas);
        assertThat(tentativas.stream().map(r -> r.getHeader("Idempotency-Key")).distinct().toList())
                .containsExactly("pedido-" + pedido.getId());
        ESTOQUE.verify(1, deleteRequestedFor(urlPathEqualTo("/reservas/res-123")));
        ESTOQUE.verify(0, confirmacoes());
    }

    @Test
    @DisplayName("Pagamentos 503 e depois 201: o pedido é confirmado e a reserva não é liberada")
    void pagamentosSeRecuperaDeFalhaTransitoria() {
        stubProduto("LIV-001", 8990);
        stubReserva();
        PAGAMENTOS.stubFor(post(urlPathEqualTo("/pagamentos"))
                .inScenario("recuperacao")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("recuperado")
                .willReturn(json(503, INDISPONIVEL)));
        PAGAMENTOS.stubFor(post(urlPathEqualTo("/pagamentos"))
                .inScenario("recuperacao")
                .whenScenarioStateIs("recuperado")
                .willReturn(json(201, APROVADO)));
        stubConfirmacao(200);

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, LIVRO));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.CONFIRMADO);
        List<LoggedRequest> tentativas = chamadas(PAGAMENTOS, cobrancas());
        assertThat(tentativas).hasSize(2);
        assertThat(tentativas.stream().map(r -> r.getHeader("Idempotency-Key")).distinct().toList()).hasSize(1);
        ESTOQUE.verify(1, confirmacoes());
        ESTOQUE.verify(0, liberacoes());
    }

    // O pagamento já foi aprovado: 404 (reserva expirada), 409 (já liberada) ou 503 na confirmação
    // não cancelam o pedido nem liberam a reserva.
    @ParameterizedTest(name = "confirmação respondendo {0}")
    @ValueSource(ints = {404, 409, 503})
    void falhaNaConfirmacaoNaoCancelaPedidoPago(int status) {
        stubProduto("LIV-001", 8990);
        stubReserva();
        stubPagamento(json(201, APROVADO));
        stubConfirmacao(status);

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, LIVRO));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.CONFIRMADO);
        ESTOQUE.verify(1, confirmacoes());
        ESTOQUE.verify(0, liberacoes());
    }

    @Test
    @DisplayName("Reserva 409 (tudo ou nada): nada é cobrado, confirmado nem liberado")
    void semEstoqueNaoCobra() {
        stubProduto("MOU-003", 4500);
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(409, ERRO_ESTOQUE_INSUFICIENTE)));

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, MOUSE));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.SEM_ESTOQUE);
        assertThat(pedido.getReservaId()).isNull();
        assertThat(totalDeChamadas(PAGAMENTOS)).isZero();
        ESTOQUE.verify(0, confirmacoes());
        ESTOQUE.verify(0, liberacoes());
    }

    @Test
    @DisplayName("Produto 404 na consulta de preço: não reserva nem cobra")
    void produtoInexistenteNaConsultaNaoReservaNemCobra() {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/LIV-001")).willReturn(json(404, ERRO_PRODUTO_INEXISTENTE)));

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, LIVRO));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.PRODUTO_INEXISTENTE);
        ESTOQUE.verify(0, reservas());
        assertThat(totalDeChamadas(PAGAMENTOS)).isZero();
    }

    @Test
    @DisplayName("Reserva 404 (algum SKU não cadastrado): não cobra e não há reserva para liberar")
    void produtoInexistenteNaReservaNaoCobraNemLibera() {
        stubProduto("LIV-001", 8990);
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(404, ERRO_PRODUTO_INEXISTENTE)));

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, LIVRO));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.PRODUTO_INEXISTENTE);
        assertThat(totalDeChamadas(PAGAMENTOS)).isZero();
        ESTOQUE.verify(0, liberacoes());
    }

    @Test
    @DisplayName("Estoque 503 na consulta de preço: FALHA_INTEGRACAO, sem reserva, sem cobrança, sem liberação")
    void estoqueIndisponivelNaConsultaNaoReservaNemCobra() {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/LIV-001")).willReturn(json(503, ERRO_ESTOQUE)));

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, LIVRO));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.FALHA_INTEGRACAO);
        ESTOQUE.verify(0, reservas());
        ESTOQUE.verify(0, liberacoes());
        assertThat(totalDeChamadas(PAGAMENTOS)).isZero();
    }

    @Test
    @DisplayName("Estoque 503 na reserva: FALHA_INTEGRACAO, uma única tentativa, sem cobrança e sem liberação")
    void estoqueIndisponivelNaReservaNaoCobraNemLibera() {
        stubProduto("LIV-001", 8990);
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(503, ERRO_ESTOQUE)));

        Pedido pedido = pedidoService.criar(pedido(MetodoPagamento.PIX, null, LIVRO));

        assertThat(pedido.getStatus()).isEqualTo(StatusPedido.FALHA_INTEGRACAO);
        ESTOQUE.verify(1, reservas());
        ESTOQUE.verify(0, liberacoes());
        assertThat(totalDeChamadas(PAGAMENTOS)).isZero();
    }

    // ------------------------------------------------------------------ lacunas (devem falhar com o código atual)

    @Test
    @Tag("lacuna")
    @DisplayName("LACUNA: PIX com tokenCartao no pedido não pode repassar tokenCartao ao Pagamentos (contrato: não enviar com PIX)")
    void pixComTokenNaoEnviaTokenCartaoAoPagamentos() {
        stubProduto("LIV-001", 8990);
        stubReserva();
        stubPagamento(json(201, APROVADO));
        stubConfirmacao(200);

        pedidoService.criar(pedido(MetodoPagamento.PIX, "tok_visa_4242", LIVRO));

        for (LoggedRequest cobranca : chamadas(PAGAMENTOS, cobrancas())) {
            assertThat(ContratoRegras.lerJson(cobranca.getBodyAsString()).has("tokenCartao"))
                    .as("tokenCartao não pode ser enviado quando metodo = PIX").isFalse();
        }
    }

    @Test
    @Tag("lacuna")
    @DisplayName("LACUNA: preço 0 é válido no Estoque, mas o Pagamentos exige valorCentavos >= 1")
    void totalZeroNaoPodeViolarOMinimoDoContratoDePagamentos() {
        stubProduto("BRD-000", 0);
        stubReserva();
        // o provedor real responde 422 para valorCentavos menor que 1
        stubPagamento(json(422, "{\"codigo\":\"DADOS_INVALIDOS\",\"mensagem\":\"valor menor que 1\"}"));
        stubLiberacao(204);

        pedidoService.criar(pedido(MetodoPagamento.PIX, null, new NovoPedidoRequest.Item("BRD-000", 1)));

        for (LoggedRequest cobranca : chamadas(PAGAMENTOS, cobrancas())) {
            assertThat(ContratoRegras.lerJson(cobranca.getBodyAsString()).get("valorCentavos").asLong())
                    .as("valorCentavos enviado ao Pagamentos").isGreaterThanOrEqualTo(1L);
        }
    }

    // ------------------------------------------------------------------ apoio

    private static NovoPedidoRequest pedido(MetodoPagamento metodo, String tokenCartao, NovoPedidoRequest.Item... itens) {
        return new NovoPedidoRequest("cliente-1", List.of(itens), metodo, tokenCartao);
    }

    private static void stubProduto(String sku, long precoCentavos) {
        ESTOQUE.stubFor(get(urlPathEqualTo("/produtos/" + sku)).willReturn(json(200,
                "{\"sku\":\"%s\",\"nome\":\"Produto %s\",\"precoCentavos\":%d,\"disponivel\":10}"
                        .formatted(sku, sku, precoCentavos))));
    }

    private static void stubReserva() {
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas")).willReturn(json(201, RESERVA)));
    }

    private static void stubConfirmacao(int status) {
        ResponseDefinitionBuilder resposta = status == 200 ? json(200, RESERVA_CONFIRMADA) : json(status, ERRO_ESTOQUE);
        ESTOQUE.stubFor(post(urlPathEqualTo("/reservas/res-123/confirmacao")).willReturn(resposta));
    }

    private static void stubLiberacao(int status) {
        ESTOQUE.stubFor(delete(urlPathEqualTo("/reservas/res-123")).willReturn(aResponse().withStatus(status)));
    }

    private static void stubPagamento(ResponseDefinitionBuilder resposta) {
        PAGAMENTOS.stubFor(post(urlPathEqualTo("/pagamentos")).willReturn(resposta));
    }

    private static RequestPatternBuilder reservas() {
        return postRequestedFor(urlPathEqualTo("/reservas"));
    }

    private static RequestPatternBuilder confirmacoes() {
        return postRequestedFor(urlPathMatching("/reservas/[^/]+/confirmacao"));
    }

    private static RequestPatternBuilder liberacoes() {
        return deleteRequestedFor(urlPathMatching("/reservas/[^/]+"));
    }

    private static RequestPatternBuilder cobrancas() {
        return postRequestedFor(urlPathEqualTo("/pagamentos"));
    }

    private static JsonNode corpoDaUnicaChamada(WireMockServer servidor, RequestPatternBuilder padrao) {
        List<LoggedRequest> requisicoes = chamadas(servidor, padrao);
        assertThat(requisicoes).hasSize(1);
        return ContratoRegras.lerJson(requisicoes.get(0).getBodyAsString());
    }
}
```

### Como rodar

```bash
./mvnw test -Pexperimentos                              # tudo, incluindo as duas lacunas
./mvnw test -Pexperimentos -DexcludedGroups=lacuna      # só o que deve passar com o código atual
```

No Windows, use `mvnw.cmd`. Os cinco arquivos compartilham um único contexto Spring e dois WireMocks, então a suíte é rápida. Os testes de timeout são os mais lentos, uns 10 segundos no total.

### O que cada regra do contrato verifica e onde

| Regra do contrato | Onde |
|---|---|
| Estoque `GET /produtos/{sku}`: 200 (preço int64, preço 0, campos extras), 404, status fora do contrato, preço negativo, nulo ou decimal, timeout | `EstoqueContratoTest.produto*` |
| Estoque `POST /reservas`: corpo exato de `NovaReserva`, 201, 404, 409 com `sku` e `disponivel`, 422, 5xx e timeout sem nova tentativa, 201 sem `id` | `EstoqueContratoTest.reserva*` |
| Estoque confirmar e liberar: 200 e 204 idempotentes, 404 (inexistente ou expirada), 409 (estado oposto), 5xx | `EstoqueContratoTest.confirmacao*` e `liberacao*` |
| Reserva "tudo ou nada": 409 ou 404 sem cobrança e sem liberação | `CheckoutContratoTest.semEstoqueNaoCobra`, `produtoInexistente*` |
| Pagamentos 201 aprovado (`motivo` nulo) e recusado (os 4 motivos) como resultado de negócio | `PagamentosContratoTest.pixAprovado`, `recusaNaoEErro` |
| Pagamentos 200 (repetição idempotente) tratado como sucesso | `repeticaoIdempotenteResponde200`, `mesmoPedidoReenviaMesmaChaveEMesmoCorpo` |
| `Idempotency-Key` obrigatória, de 1 a 64 caracteres, igual em todas as tentativas e diferente entre pedidos | `ContratoRegras` (todo teste), `esgotaAsTentativas`, `pedidosDiferentesUsamChavesDiferentes` |
| 503 e timeout são repetidos até `max-tentativas`; 400, 409 e 422 não | `PagamentosContratoTest` (blocos "indisponibilidade" e "erros que não se repetem") |
| Resposta fora do contrato (sem `id`, sem `status`, status inventado, corpo vazio) | `respostaForaDoContratoNaoEAceitaNemRepetida` |
| Corpo enviado: `additionalProperties: false`, `minimum`, `minItems`, `tokenCartao` só com CARTAO | `ContratoRegras` (todo teste) |
| Operações não usadas nunca são chamadas (`GET /pagamentos/{id}`) | `ContratoRegras` (todo teste) |
| Fluxo entre as duas APIs: mesmo `pedidoId`, total igual à soma preço x quantidade, compensação por liberação, pedido pago não é cancelado por falha na confirmação | `CheckoutContratoTest` |

### Divergências que os testes `@Tag("lacuna")` devem expor

Os dois testes abaixo falham com o código atual. O `ContratoRegras` também falha neles, no `@AfterEach`. São achados sobre o serviço, não erros dos testes.

1. **PIX com `tokenCartao` vaza o token para o Pagamentos.** O `NovoPedidoRequest` só exige o token quando o método é `CARTAO`. Com `PIX` e um token informado, o `PedidoService` repassa o token ao `PagamentosClient`. O contrato diz "Não enviar quando metodo = PIX". Correção possível: descartar o token quando o método for PIX, ou rejeitar o pedido com 400. Teste: `pixComTokenNaoEnviaTokenCartaoAoPagamentos`.
2. **Total 0 viola o `minimum: 1` de `valorCentavos`.** O Estoque aceita `precoCentavos: 0` (`minimum: 0`), mas o Pagamentos exige `valorCentavos >= 1`. Um pedido só com itens de preço 0 envia `valorCentavos: 0`, o provedor responde 422 e o cliente recebe `FALHA_INTEGRACAO`. O contrato não diz qual deveria ser o comportamento. As opções são não cobrar e confirmar a reserva direto, ou rejeitar o pedido, e isso precisa de decisão de produto. Teste: `totalZeroNaoPodeViolarOMinimoDoContratoDePagamentos`.

### Premissas e limites

- **Não executei os testes.** Escrevi contra as APIs do WireMock 3.13, do `RestClient` do Spring Boot 3.5 e do JUnit 5. Se algo não compilar ou falhar além das duas lacunas, vale olhar primeiro as asserções de contagem de requisições (tentativas) e os imports estáticos do WireMock.
- **O contrato está transcrito à mão** em `ContratoRegras` e nos corpos dos testes, porque o `pom.xml` não tem validador OpenAPI. Se o YAML mudar, os testes precisam mudar junto. Para o YAML ser a fonte da verdade, dá para trocar essa classe por `swagger-request-validator-wiremock` (dependência de teste extra) apontando para `contratos/*.yaml`.
- **Só o lado do consumidor está coberto.** Para fechar o contrato, Estoque e Pagamentos precisam provar nos pipelines deles que as respostas reais obedecem aos mesmos YAMLs e exemplos (Pact, Spring Cloud Contract ou validação OpenAPI das respostas).
- **Os WireMocks são compartilhados por JVM**, então os testes não podem rodar em paralelo. Os timeouts dos clients são reduzidos para 1500 ms só nos testes (`@SpringBootTest(properties = ...)` em `ContratoBase`).
- **O que o contrato não define não foi testado:** SKU repetido no mesmo pedido, caracteres especiais no SKU do caminho, a expiração real de 15 minutos e a idempotência real da reserva. Esses comportamentos são do provedor.
- **Os stubs devolvem os exemplos do contrato.** O `pedidoId` dentro das respostas de reserva e de pagamento é fixo, porque o Pedidos só lê `id`, `status` e `motivo`.

