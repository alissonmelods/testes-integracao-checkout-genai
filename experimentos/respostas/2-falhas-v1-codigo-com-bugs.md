> **Resposta da LLM, sem edição.** Experimento do [oráculo](../oraculo/README.md): prompt [2-falhas-comunicacao/v1.md](../../prompts/2-falhas-comunicacao/v1.md) com o **código com bugs** anexado | Modelo: Claude Sonnet 5.5 | Data: 26/10/2026

---

# Testes de integração para falhas na comunicação Pedidos → Estoque / Pagamentos

Li o código inteiro antes de montar os testes. Além dos cenários de erro que você pediu, a leitura revelou **um bug confirmado e dois pontos suspeitos**. Eles vêm primeiro porque definem quais testes devem falhar hoje. Não consegui executar nada aqui: os testes foram escritos a partir da leitura do código, então rode a suíte e me conte o que divergir.

## 1. O que a leitura do código mostrou

### 1.1 Bug: a reserva fica presa quando o Pagamentos falha

`PedidoService.criar` só chama `desfazerReserva` dentro de `cobrar`, e apenas quando o pagamento volta `RECUSADO`. Se o `PagamentosClient` esgota as tentativas (ou recebe uma resposta fora do contrato), ele lança `FalhaIntegracaoException`. Essa exceção cai no `catch` de `criar`, que só finaliza o pedido:

```java
} catch (FalhaIntegracaoException e) {
    pedido.finalizar(StatusPedido.FALHA_INTEGRACAO, e.getMessage());   // a reserva continua ativa
}
```

O Javadoc de `StatusPedido.FALHA_INTEGRACAO` diz o contrário: "A reserva, se havia, foi desfeita." O `if (pedido.getReservaId() == null) return;` dentro de `desfazerReserva` também só faz sentido se o método for chamado nesse `catch`, onde a reserva pode não existir. Efeito prático: cada checkout com o Pagamentos instável deixa os itens bloqueados no Estoque por até 15 minutos.

Correção sugerida:

```java
} catch (FalhaIntegracaoException e) {
    desfazerReserva(pedido);   // não faz nada se ainda não há reserva
    pedido.finalizar(StatusPedido.FALHA_INTEGRACAO, e.getMessage());
}
```

Ressalva de negócio: depois de um timeout, a cobrança pode ter sido efetivada no Pagamentos (só a resposta se perdeu). Liberar a reserva nesse caso pede reconciliação (consultar o pagamento pela `Idempotency-Key`). Os testes abaixo seguem o que o Javadoc promete; se a decisão for outra, mude o Javadoc e os testes `CompensacaoDeReservaIT`.

### 1.2 Suspeita: erros 4xx do Pagamentos são repetidos

`catch (HttpStatusCodeException | ResourceAccessException e)` captura 5xx **e** 4xx. Um 400/422 é determinístico: repetir três vezes só atrasa a resposta ao cliente. O Javadoc fala em "erro HTTP", então pode ser intencional. O teste `erroDeCliente_naoDeveSerRepetido` registra a expectativa de **uma** chamada. Se o contrato (`contratos/pagamentos-api.yaml`, que não recebi) mandar repetir algum 4xx (409, 429), ajuste o teste.

### 1.3 Risco: overflow no total do pedido

`Pedido.getTotalCentavos()` soma `long * int` sem checagem. Um preço próximo de `Long.MAX_VALUE` vindo do Estoque passa pela validação do client (`>= 0`), o total estoura para negativo, e `cobrar` só chama o Pagamentos se `total > 0`. Resultado: pedido **confirmado sem nenhuma cobrança**. O teste `precoAbsurdoNaoPodeGerarPedidoSemCobranca` cobre isso (prioridade baixa, é endurecimento).

### 1.4 O que ficou de fora

- Corpo de resposta que chega em conta-gotas depois dos cabeçalhos (`withChunkedDribbleDelay`). Pelo código, o read timeout do `JdkClientHttpRequestFactory` cobre a espera pela resposta, não a leitura lenta do corpo. Vale um teste exploratório.
- Concorrência (dois checkouts ao mesmo tempo) e latência acumulada: com a configuração padrão, o pior caso do Pagamentos é 3 tentativas x 3 s = 9 s dentro de uma requisição HTTP.

## 2. Estratégia

- `@SpringBootTest` + `MockMvc`: sobe o contexto real (controller, service, clients, `RestClient` com o `HttpClient` do JDK). Só Estoque e Pagamentos são simulados, com dois `WireMockServer` reais acessados por HTTP. Isso é necessário para reproduzir timeout, reset de conexão, resposta vazia e conexão recusada.
- Os servidores sobem **uma vez por JVM**, em portas livres escolhidas na inicialização, porque o contexto Spring é reaproveitado entre as classes. Para "conexão recusada", o teste chama `stop()` no servidor e o `@BeforeEach` seguinte o religa.
- Timeouts reduzidos para 500 ms via `@SpringBootTest(properties = ...)`. O atraso injetado é de 1,5 s.
- Cada teste parte do fluxo feliz (`prepararFluxoFeliz()`) e sobrescreve só o stub que quer quebrar. No WireMock, o stub registrado por último tem prioridade.
- Cada teste confere os dois lados: a resposta de `POST /pedidos` e as chamadas que realmente chegaram aos servidores (contagem, `Idempotency-Key`, `DELETE` de compensação, ausência de cobrança).
- Para POST e DELETE as contagens são exatas (o `HttpClient` do JDK não repete esses métodos sozinho). Para GET sob falha de rede evito contar, porque o JDK pode repetir GET em conexão reaproveitada.
- As asserções de texto evitam acentos, para não depender da decodificação de charset do MockMvc.
- Local dos arquivos: `src/test/java/com/estudo/checkout/integracao/` (ou `experimentos/java/...` com `-Pexperimentos`). Todos os nomes terminam em `IT`, padrão que o Surefire do seu `pom.xml` já inclui. Não habilite execução paralela: os servidores WireMock são compartilhados.

## 3. Matriz de cenários

| ID | Etapa | Falha injetada | Resultado esperado |
|---|---|---|---|
| E-01 | Estoque `GET /produtos/{sku}` | 404 | 422 `PRODUTO_INEXISTENTE`; nada reservado nem cobrado |
| E-02 | idem | 500, 502, 503, 504 | 503 `FALHA_INTEGRACAO` ("estoque: ..."); uma única chamada (sem retry) |
| E-03 | idem | timeout | 503; nada reservado nem cobrado |
| E-04 | idem | reset de conexão, resposta vazia, chunk malformado, lixo binário | 503 |
| E-05 | idem | 200 fora do contrato: sem preço, preço nulo, negativo, decimal (89.9), texto, JSON truncado, corpo vazio, HTML de proxy | 503 |
| E-06 | idem | falha só no 2º item do pedido | 503; nenhuma reserva criada |
| E-07 | Estoque `POST /reservas` | 409 com corpo legível e ilegível | 409 `SEM_ESTOQUE`; sem cobrança; sem `DELETE` |
| E-08 | idem | 404 com e sem corpo | 422 `PRODUTO_INEXISTENTE` |
| E-09 | idem | 400, 422, 500, 502, 503, 504 | 503; **sem retry** (reserva não é idempotente); sem cobrança; sem `DELETE` |
| E-10 | idem | timeout e falhas de rede | 503; exatamente 1 `POST /reservas` |
| E-11 | idem | 201 sem `id`, `id` nulo, corpo vazio, HTML | 503; sem cobrança |
| E-12 | Estoque fora do ar | conexão recusada | 503; Pagamentos não é chamado |
| P-01 | Pagamentos `POST /pagamentos` | 1ª tentativa falha (5xx, timeout, reset, resposta vazia) e a 2ª funciona | 201 `CONFIRMADO`; 2 chamadas com a **mesma** `Idempotency-Key` (`pedido-{id}`) e o mesmo corpo |
| P-02 | idem | as 3 tentativas falham | 503 com "3 tentativas"; exatamente 3 chamadas; mesma chave; sem confirmação |
| P-03 | Pagamentos fora do ar | conexão recusada | 503 com "3 tentativas" |
| P-04 | idem | 400 e 422 | 503 **sem repetir** (1 chamada), ver 1.2 |
| P-05 | idem | 201 fora do contrato: `{}`, sem `status`, sem `id`, status desconhecido, vazio, HTML | 503 sem repetir (1 chamada) |
| P-06 | idem | PIX com 1ª tentativa falhando | `tokenCartao` não vai em nenhuma tentativa |
| C-01 | Compensação | pagamento `RECUSADO` | 402 `PAGAMENTO_RECUSADO`; 1 `DELETE` da reserva; sem confirmação; sem retry |
| C-02 | idem | `RECUSADO` e `DELETE` falha (500, 404, timeout, reset) | continua 402; erro de liberação não vaza |
| C-03 | idem | `APROVADO` e confirmação da reserva falha | continua 201 `CONFIRMADO`; 1 tentativa de confirmar; sem `DELETE` |
| C-04 | idem | Pagamentos indisponível (5xx, timeout, reset, conexão recusada) | 503 **e reserva liberada** (falha hoje, ver 1.1) |
| C-05 | idem | Pagamentos fora do contrato | 503 **e reserva liberada** (falha hoje, ver 1.1) |
| X-01 | Total do pedido | preço `Long.MAX_VALUE` | 503; nunca `CONFIRMADO` sem cobrança (falha hoje, ver 1.3) |
| B-01 a B-03 | Linha de base | sem erro; falha persistida; pedido gratuito com Pagamentos fora do ar | prova que os stubs estão certos e que a falha é consultável em `GET /pedidos/{id}` |

## 4. Código

Nenhuma dependência nova: `spring-boot-starter-test` (JUnit 5, AssertJ, Hamcrest, JsonPath, MockMvc) e `wiremock-standalone` já estão no seu `pom.xml`.

### 4.1 `IntegracaoBase.java` (infraestrutura compartilhada)

```java
package com.estudo.checkout.integracao;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "servicos.estoque.timeout-ms=500",
        "servicos.pagamentos.timeout-ms=500",
        "servicos.pagamentos.max-tentativas=3"
})
@AutoConfigureMockMvc
@Timeout(20) // rede de segurança: se algum timeout do client não funcionar, o teste não fica pendurado
abstract class IntegracaoBase {

    static final String RESERVA_ID = "res-1";
    /** Maior que o timeout de 500 ms configurado acima. */
    static final int ATRASO_MS = 1500;

    static final String PEDIDO_CARTAO = """
            {"clienteId":"cli-1","itens":[{"sku":"SKU-1","quantidade":2}],\
            "metodoPagamento":"CARTAO","tokenCartao":"tok_teste"}""";
    static final String PEDIDO_PIX = """
            {"clienteId":"cli-1","itens":[{"sku":"SKU-1","quantidade":2}],\
            "metodoPagamento":"PIX","tokenCartao":"tok_ignorado"}""";

    // Sobem uma vez por JVM: o contexto Spring é reaproveitado entre as classes de teste e guarda essas URLs.
    static final WireMockServer ESTOQUE = iniciarWireMock();
    static final WireMockServer PAGAMENTOS = iniciarWireMock();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (ESTOQUE.isRunning()) ESTOQUE.stop();
            if (PAGAMENTOS.isRunning()) PAGAMENTOS.stop();
        }));
    }

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void urlsDosServicos(DynamicPropertyRegistry registry) {
        registry.add("servicos.estoque.url", ESTOQUE::baseUrl);
        registry.add("servicos.pagamentos.url", PAGAMENTOS::baseUrl);
    }

    @BeforeEach
    void religarEZerarServidores() {
        // alguns testes dão stop() para simular "conexão recusada"
        if (!ESTOQUE.isRunning()) ESTOQUE.start();
        if (!PAGAMENTOS.isRunning()) PAGAMENTOS.start();
        ESTOQUE.resetAll();
        PAGAMENTOS.resetAll();
    }

    // ---------- chamadas e asserções comuns ----------

    ResultActions criarPedido(String corpo) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/pedidos")
                .contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    static ResultActions esperarFalhaDeIntegracao(ResultActions resposta, String servico) throws Exception {
        return resposta
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("FALHA_INTEGRACAO"))
                .andExpect(jsonPath("$.motivo", startsWith(servico + ":")));
    }

    static String idDoPedido(ResultActions resposta) throws Exception {
        return JsonPath.read(resposta.andReturn().getResponse().getContentAsString(), "$.id");
    }

    // ---------- stubs ----------

    /** Estoque e Pagamentos respondendo conforme o contrato. Cada teste sobrescreve só o que quer quebrar. */
    static void prepararFluxoFeliz() {
        stubProduto("SKU-1", 1000);
        ESTOQUE.stubFor(post(urlEqualTo("/reservas"))
                .willReturn(json(201, "{\"id\":\"" + RESERVA_ID + "\",\"status\":\"RESERVADA\"}")));
        ESTOQUE.stubFor(post(urlEqualTo("/reservas/" + RESERVA_ID + "/confirmacao"))
                .willReturn(aResponse().withStatus(204)));
        ESTOQUE.stubFor(delete(urlEqualTo("/reservas/" + RESERVA_ID))
                .willReturn(aResponse().withStatus(204)));
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).willReturn(pagamentoAprovado()));
    }

    static void stubProduto(String sku, long precoCentavos) {
        ESTOQUE.stubFor(get(urlEqualTo("/produtos/" + sku)).willReturn(
                json(200, "{\"sku\":\"" + sku + "\",\"precoCentavos\":" + precoCentavos + "}")));
    }

    /** Pagamentos: a 1ª chamada recebe {@code falha}; da 2ª em diante, aprova. */
    static void pagamentoFalhaUmaVezDepoisAprova(ResponseDefinitionBuilder falha) {
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED).willReturn(falha).willSetStateTo("recuperado"));
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).inScenario("retry")
                .whenScenarioStateIs("recuperado").willReturn(pagamentoAprovado()));
    }

    static ResponseDefinitionBuilder json(int status, String corpo) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(corpo);
    }

    static ResponseDefinitionBuilder pagamentoAprovado() {
        return json(201, "{\"id\":\"pg-1\",\"status\":\"APROVADO\"}");
    }

    // ---------- padrões de requisição para verify/findAll ----------

    static RequestPatternBuilder postReservas() {
        return postRequestedFor(urlEqualTo("/reservas"));
    }

    static RequestPatternBuilder postPagamentos() {
        return postRequestedFor(urlEqualTo("/pagamentos"));
    }

    static RequestPatternBuilder postConfirmacao() {
        return postRequestedFor(urlEqualTo("/reservas/" + RESERVA_ID + "/confirmacao"));
    }

    static RequestPatternBuilder deleteReserva() {
        return deleteRequestedFor(urlEqualTo("/reservas/" + RESERVA_ID));
    }

    // ---------- massas de dados compartilhadas ----------

    /** Falhas que o PagamentosClient trata como transitórias (e repete). */
    static Stream<Arguments> falhasTransitorias() {
        return Stream.of(
                arguments("HTTP 500", aResponse().withStatus(500)),
                arguments("HTTP 502", aResponse().withStatus(502)),
                arguments("HTTP 503", aResponse().withStatus(503)),
                arguments("HTTP 504", aResponse().withStatus(504)),
                arguments("timeout", aResponse().withStatus(201).withFixedDelay(ATRASO_MS)),
                arguments("conexao resetada", aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
                arguments("resposta vazia", aResponse().withFault(Fault.EMPTY_RESPONSE)));
    }

    /** Respostas 201 do Pagamentos que não respeitam o contrato: descrição, Content-Type, corpo. */
    static Stream<Arguments> pagamentosForaDoContrato() {
        return Stream.of(
                arguments("objeto vazio", "application/json", "{}"),
                arguments("sem status", "application/json", "{\"id\":\"pg-1\"}"),
                arguments("sem id", "application/json", "{\"status\":\"APROVADO\"}"),
                arguments("status desconhecido", "application/json", "{\"id\":\"pg-1\",\"status\":\"PENDENTE\"}"),
                arguments("corpo vazio", "application/json", ""),
                arguments("HTML no lugar do JSON", "text/html", "<html>Bad Gateway</html>"));
    }

    // ---------- infraestrutura ----------

    private static WireMockServer iniciarWireMock() {
        WireMockServer servidor = new WireMockServer(options().port(portaLivre()).containerThreads(30));
        servidor.start();
        return servidor;
    }

    private static int portaLivre() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

### 4.2 `FalhasEstoqueIT.java` (E-01 a E-12 e X-01)

```java
package com.estudo.checkout.integracao;

import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FalhasEstoqueIT extends IntegracaoBase {

    private static final String PRODUTO_SKU_1 = "/produtos/SKU-1";

    private static void nadaReservadoNemCobrado() {
        ESTOQUE.verify(0, postReservas());
        PAGAMENTOS.verify(0, postPagamentos());
    }

    // ===================== GET /produtos/{sku} =====================

    @Test
    @DisplayName("E-01: 404 na consulta de preço vira PRODUTO_INEXISTENTE; nada é reservado nem cobrado")
    void produtoInexistenteNaConsultaDePreco() throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(get(urlEqualTo(PRODUTO_SKU_1)).willReturn(aResponse().withStatus(404)));

        criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("PRODUTO_INEXISTENTE"))
                .andExpect(jsonPath("$.motivo", containsString("SKU-1")));

        nadaReservadoNemCobrado();
    }

    @ParameterizedTest(name = "E-02: GET /produtos responde HTTP {0}")
    @ValueSource(ints = {500, 502, 503, 504})
    void erroHttpNaConsultaDePreco(int httpStatus) throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(get(urlEqualTo(PRODUTO_SKU_1)).willReturn(aResponse().withStatus(httpStatus)));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "estoque");

        ESTOQUE.verify(1, getRequestedFor(urlEqualTo(PRODUTO_SKU_1))); // o Estoque não tem retry
        nadaReservadoNemCobrado();
    }

    @Test
    @DisplayName("E-03: timeout na consulta de preço")
    void timeoutNaConsultaDePreco() throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(get(urlEqualTo(PRODUTO_SKU_1)).willReturn(
                json(200, "{\"sku\":\"SKU-1\",\"precoCentavos\":1000}").withFixedDelay(ATRASO_MS)));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "estoque");

        nadaReservadoNemCobrado();
    }

    @ParameterizedTest(name = "E-04: GET /produtos com falha de rede {0}")
    @EnumSource(Fault.class)
    void falhaDeRedeNaConsultaDePreco(Fault falha) throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(get(urlEqualTo(PRODUTO_SKU_1)).willReturn(aResponse().withFault(falha)));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "estoque");

        nadaReservadoNemCobrado();
    }

    static Stream<Arguments> produtosForaDoContrato() {
        return Stream.of(
                arguments("sem precoCentavos", "application/json", "{\"sku\":\"SKU-1\"}"),
                arguments("precoCentavos nulo", "application/json", "{\"sku\":\"SKU-1\",\"precoCentavos\":null}"),
                arguments("preço negativo", "application/json", "{\"sku\":\"SKU-1\",\"precoCentavos\":-100}"),
                // depende de spring.jackson.deserialization.accept-float-as-int=false (application.yml)
                arguments("preço com casa decimal", "application/json", "{\"sku\":\"SKU-1\",\"precoCentavos\":89.9}"),
                arguments("preço não numérico", "application/json", "{\"sku\":\"SKU-1\",\"precoCentavos\":\"abc\"}"),
                arguments("JSON truncado", "application/json", "{\"sku\":\"SKU-1\",\"precoCent"),
                arguments("corpo vazio", "application/json", ""),
                arguments("HTML no lugar do JSON", "text/html", "<html>Bad Gateway</html>"));
    }

    @ParameterizedTest(name = "E-05: GET /produtos com 200 fora do contrato ({0})")
    @MethodSource("produtosForaDoContrato")
    void consultaDePrecoForaDoContrato(String descricao, String contentType, String corpo) throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(get(urlEqualTo(PRODUTO_SKU_1)).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", contentType).withBody(corpo)));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "estoque");

        nadaReservadoNemCobrado();
    }

    @Test
    @DisplayName("E-06: falha no 2º item impede a reserva e a cobrança")
    void falhaNoSegundoItem() throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(get(urlEqualTo("/produtos/SKU-2")).willReturn(aResponse().withStatus(500)));
        String pedido = """
                {"clienteId":"cli-1","itens":[{"sku":"SKU-1","quantidade":1},{"sku":"SKU-2","quantidade":1}],\
                "metodoPagamento":"PIX"}""";

        esperarFalhaDeIntegracao(criarPedido(pedido), "estoque");

        ESTOQUE.verify(1, getRequestedFor(urlEqualTo(PRODUTO_SKU_1)));
        nadaReservadoNemCobrado();
    }

    // ===================== POST /reservas =====================

    @Test
    @DisplayName("E-07: 409 com corpo legível vira SEM_ESTOQUE")
    void estoqueInsuficiente() throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(post(urlEqualTo("/reservas")).willReturn(json(409,
                "{\"codigo\":\"ESTOQUE_INSUFICIENTE\",\"mensagem\":\"sem saldo\",\"sku\":\"SKU-1\",\"disponivel\":1}")));

        criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("SEM_ESTOQUE"))
                .andExpect(jsonPath("$.motivo", containsString("estoque insuficiente para o SKU SKU-1")));

        PAGAMENTOS.verify(0, postPagamentos());
        ESTOQUE.verify(0, deleteRequestedFor(urlPathMatching("/reservas/.*")));
    }

    @ParameterizedTest(name = "E-07: 409 com corpo ilegível [{0}] continua SEM_ESTOQUE")
    @ValueSource(strings = {"", "nao e json", "{\"codigo\":"})
    void estoqueInsuficienteComCorpoIlegivel(String corpo) throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(post(urlEqualTo("/reservas")).willReturn(json(409, corpo)));

        criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("SEM_ESTOQUE"));

        PAGAMENTOS.verify(0, postPagamentos());
    }

    @ParameterizedTest(name = "E-08: 404 em POST /reservas com corpo [{0}] vira PRODUTO_INEXISTENTE")
    @ValueSource(strings = {"{\"codigo\":\"PRODUTO_INEXISTENTE\",\"sku\":\"SKU-1\"}", ""})
    void produtoRemovidoEntrePrecoEReserva(String corpo) throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(post(urlEqualTo("/reservas")).willReturn(json(404, corpo)));

        criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("PRODUTO_INEXISTENTE"));

        PAGAMENTOS.verify(0, postPagamentos());
    }

    @ParameterizedTest(name = "E-09: POST /reservas responde HTTP {0}")
    @ValueSource(ints = {400, 422, 500, 502, 503, 504})
    void erroHttpNaReserva_naoRepete(int httpStatus) throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(post(urlEqualTo("/reservas")).willReturn(aResponse().withStatus(httpStatus)));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "estoque");

        ESTOQUE.verify(1, postReservas()); // reserva não é idempotente: sem retry
        PAGAMENTOS.verify(0, postPagamentos());
        ESTOQUE.verify(0, deleteRequestedFor(urlPathMatching("/reservas/.*"))); // não há o que liberar
    }

    @Test
    @DisplayName("E-10: timeout na reserva não é repetido e não cobra")
    void timeoutNaReserva() throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(post(urlEqualTo("/reservas")).willReturn(
                json(201, "{\"id\":\"" + RESERVA_ID + "\"}").withFixedDelay(ATRASO_MS)));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "estoque");

        ESTOQUE.verify(1, postReservas());
        PAGAMENTOS.verify(0, postPagamentos());
    }

    @ParameterizedTest(name = "E-10: POST /reservas com falha de rede {0}")
    @EnumSource(Fault.class)
    void falhaDeRedeNaReserva(Fault falha) throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(post(urlEqualTo("/reservas")).willReturn(aResponse().withFault(falha)));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "estoque");

        ESTOQUE.verify(1, postReservas());
        PAGAMENTOS.verify(0, postPagamentos());
    }

    static Stream<Arguments> reservasForaDoContrato() {
        return Stream.of(
                arguments("sem id", "application/json", "{\"status\":\"RESERVADA\"}"),
                arguments("id nulo", "application/json", "{\"id\":null}"),
                arguments("corpo vazio", "application/json", ""),
                arguments("HTML no lugar do JSON", "text/html", "<html>Bad Gateway</html>"));
    }

    @ParameterizedTest(name = "E-11: POST /reservas com 201 fora do contrato ({0})")
    @MethodSource("reservasForaDoContrato")
    void reservaForaDoContrato(String descricao, String contentType, String corpo) throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(post(urlEqualTo("/reservas")).willReturn(
                aResponse().withStatus(201).withHeader("Content-Type", contentType).withBody(corpo)));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "estoque");

        PAGAMENTOS.verify(0, postPagamentos());
    }

    // ===================== indisponibilidade total e total do pedido =====================

    @Test
    @DisplayName("E-12: Estoque fora do ar (conexão recusada)")
    void estoqueForaDoAr() throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stop(); // o @BeforeEach seguinte religa

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "estoque");

        PAGAMENTOS.verify(0, postPagamentos());
    }

    @Test
    @DisplayName("X-01: preço absurdo não pode estourar o total e confirmar o pedido sem cobrança (falha hoje)")
    void precoAbsurdoNaoPodeGerarPedidoSemCobranca() throws Exception {
        prepararFluxoFeliz();
        stubProduto("SKU-1", Long.MAX_VALUE); // quantidade 2 => 2 * Long.MAX_VALUE estoura para -2

        criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("FALHA_INTEGRACAO"));

        ESTOQUE.verify(0, postConfirmacao());
    }
}
```

### 4.3 `FalhasPagamentosIT.java` (P-01 a P-06: retry e idempotência)

```java
package com.estudo.checkout.integracao;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FalhasPagamentosIT extends IntegracaoBase {

    @ParameterizedTest(name = "P-01: 1ª tentativa falha ({0}) e a 2ª funciona")
    @MethodSource("falhasTransitorias")
    void falhaTransitoria_repeteComAMesmaChaveDeIdempotencia(String descricao, ResponseDefinitionBuilder falha)
            throws Exception {
        prepararFluxoFeliz();
        pagamentoFalhaUmaVezDepoisAprova(falha);

        ResultActions resposta = criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMADO"))
                .andExpect(jsonPath("$.pagamentoId").value("pg-1"));

        List<LoggedRequest> tentativas = PAGAMENTOS.findAll(postPagamentos());
        assertThat(tentativas).hasSize(2);
        // a mesma chave nas duas tentativas é o que impede cobrança em duplicidade se a 1ª chegou a ser processada
        assertThat(tentativas).extracting(t -> t.getHeader("Idempotency-Key"))
                .containsOnly("pedido-" + idDoPedido(resposta));
        assertThat(tentativas.get(1).getBodyAsString()).isEqualTo(tentativas.get(0).getBodyAsString());
        ESTOQUE.verify(1, postConfirmacao());
        ESTOQUE.verify(0, deleteReserva());
    }

    @ParameterizedTest(name = "P-02: as 3 tentativas falham ({0})")
    @MethodSource("falhasTransitorias")
    void todasAsTentativasFalham_devolve503DepoisDeTresTentativas(String descricao, ResponseDefinitionBuilder falha)
            throws Exception {
        prepararFluxoFeliz();
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).willReturn(falha));

        ResultActions resposta = esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "pagamentos")
                .andExpect(jsonPath("$.motivo", containsString("3 tentativas")));

        List<LoggedRequest> tentativas = PAGAMENTOS.findAll(postPagamentos());
        assertThat(tentativas).hasSize(3);
        assertThat(tentativas).extracting(t -> t.getHeader("Idempotency-Key"))
                .containsOnly("pedido-" + idDoPedido(resposta));
        ESTOQUE.verify(0, postConfirmacao());
    }

    @Test
    @DisplayName("P-03: Pagamentos fora do ar (conexão recusada) esgota as tentativas")
    void pagamentosForaDoAr() throws Exception {
        prepararFluxoFeliz();
        PAGAMENTOS.stop(); // o @BeforeEach seguinte religa

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "pagamentos")
                .andExpect(jsonPath("$.motivo", containsString("3 tentativas")));

        ESTOQUE.verify(0, postConfirmacao());
    }

    /** Falha hoje: o client trata todo HttpStatusCodeException como transitório e repete 4xx (ver 1.2). */
    @ParameterizedTest(name = "P-04: POST /pagamentos responde HTTP {0}; erro de cliente não deve ser repetido")
    @ValueSource(ints = {400, 422})
    void erroDeCliente_naoDeveSerRepetido(int httpStatus) throws Exception {
        prepararFluxoFeliz();
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).willReturn(
                json(httpStatus, "{\"codigo\":\"REQUISICAO_INVALIDA\"}")));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "pagamentos");

        PAGAMENTOS.verify(1, postPagamentos());
        ESTOQUE.verify(0, postConfirmacao());
    }

    @ParameterizedTest(name = "P-05: POST /pagamentos com 201 fora do contrato ({0}) não é repetido")
    @MethodSource("pagamentosForaDoContrato")
    void respostaForaDoContrato_naoRepete(String descricao, String contentType, String corpo) throws Exception {
        prepararFluxoFeliz();
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).willReturn(
                aResponse().withStatus(201).withHeader("Content-Type", contentType).withBody(corpo)));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "pagamentos");

        PAGAMENTOS.verify(1, postPagamentos());
        ESTOQUE.verify(0, postConfirmacao());
    }

    @Test
    @DisplayName("P-06: PIX nunca envia tokenCartao, nem nas tentativas seguintes")
    void pix_naoEnviaTokenEmNenhumaTentativa() throws Exception {
        prepararFluxoFeliz();
        pagamentoFalhaUmaVezDepoisAprova(aResponse().withStatus(503));

        criarPedido(PEDIDO_PIX).andExpect(status().isCreated());

        PAGAMENTOS.verify(2, postPagamentos());
        PAGAMENTOS.verify(0, postRequestedFor(urlEqualTo("/pagamentos")).withRequestBody(containing("tokenCartao")));
        PAGAMENTOS.verify(2, postRequestedFor(urlEqualTo("/pagamentos"))
                .withRequestBody(matchingJsonPath("$.metodo", equalTo("PIX")))
                .withRequestBody(matchingJsonPath("$.valorCentavos", equalTo("2000"))));
    }
}
```

### 4.4 `CompensacaoDeReservaIT.java` (C-01 a C-05: o que acontece com a reserva)

```java
package com.estudo.checkout.integracao;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CompensacaoDeReservaIT extends IntegracaoBase {

    private static final String RECUSADO =
            "{\"id\":\"pg-9\",\"status\":\"RECUSADO\",\"motivo\":\"saldo insuficiente\"}";

    /** Falhas possíveis nas chamadas de confirmação e de liberação da reserva. */
    static Stream<Arguments> falhasNoEstoque() {
        return Stream.of(
                arguments("HTTP 500", aResponse().withStatus(500)),
                arguments("HTTP 404, reserva já expirou", aResponse().withStatus(404)),
                arguments("timeout", aResponse().withStatus(204).withFixedDelay(ATRASO_MS)),
                arguments("conexão resetada", aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    }

    @Test
    @DisplayName("C-01: pagamento recusado devolve a reserva ao estoque e não confirma")
    void pagamentoRecusado_liberaReserva() throws Exception {
        prepararFluxoFeliz();
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).willReturn(json(201, RECUSADO)));

        criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value("PAGAMENTO_RECUSADO"))
                .andExpect(jsonPath("$.motivo").value("saldo insuficiente"))
                .andExpect(jsonPath("$.pagamentoId").value("pg-9"));

        PAGAMENTOS.verify(1, postPagamentos()); // recusa é resultado de negócio: não se repete
        ESTOQUE.verify(1, deleteReserva());
        ESTOQUE.verify(0, postConfirmacao());
    }

    @ParameterizedTest(name = "C-02: pagamento recusado e a liberação da reserva falha ({0})")
    @MethodSource("falhasNoEstoque")
    void pagamentoRecusado_falhaNaLiberacao_continuaRecusado(String descricao, ResponseDefinitionBuilder falha)
            throws Exception {
        prepararFluxoFeliz();
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).willReturn(json(201, RECUSADO)));
        ESTOQUE.stubFor(delete(urlEqualTo("/reservas/" + RESERVA_ID)).willReturn(falha));

        criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value("PAGAMENTO_RECUSADO"));

        ESTOQUE.verify(1, deleteReserva()); // tentada uma vez; se falhar, a reserva expira sozinha
    }

    @ParameterizedTest(name = "C-03: pagamento aprovado e a confirmação da reserva falha ({0})")
    @MethodSource("falhasNoEstoque")
    void pagamentoAprovado_falhaNaConfirmacao_continuaConfirmado(String descricao, ResponseDefinitionBuilder falha)
            throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(post(urlEqualTo("/reservas/" + RESERVA_ID + "/confirmacao")).willReturn(falha));

        criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMADO"))
                .andExpect(jsonPath("$.pagamentoId").value("pg-1"));

        ESTOQUE.verify(1, postConfirmacao()); // uma tentativa; a confirmação fica pendente
        ESTOQUE.verify(0, deleteReserva());   // já foi pago: não pode liberar
        PAGAMENTOS.verify(1, postPagamentos());
    }

    // ---- Os testes abaixo FALHAM hoje por causa do bug descrito em 1.1 ----

    @ParameterizedTest(name = "C-04: Pagamentos indisponível ({0}); a reserva deve ser liberada")
    @MethodSource("falhasTransitorias")
    void pagamentoIndisponivel_liberaReserva(String descricao, ResponseDefinitionBuilder falha) throws Exception {
        prepararFluxoFeliz();
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).willReturn(falha));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "pagamentos");

        ESTOQUE.verify(1, deleteReserva()); // StatusPedido.FALHA_INTEGRACAO: "a reserva, se havia, foi desfeita"
        ESTOQUE.verify(0, postConfirmacao());
    }

    @Test
    @DisplayName("C-04: Pagamentos fora do ar (conexão recusada); a reserva deve ser liberada")
    void pagamentosForaDoAr_liberaReserva() throws Exception {
        prepararFluxoFeliz();
        PAGAMENTOS.stop(); // o @BeforeEach seguinte religa

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "pagamentos");

        ESTOQUE.verify(1, deleteReserva());
        ESTOQUE.verify(0, postConfirmacao());
    }

    @ParameterizedTest(name = "C-05: Pagamentos responde fora do contrato ({0}); a reserva deve ser liberada")
    @MethodSource("pagamentosForaDoContrato")
    void pagamentoForaDoContrato_liberaReserva(String descricao, String contentType, String corpo) throws Exception {
        prepararFluxoFeliz();
        PAGAMENTOS.stubFor(post(urlEqualTo("/pagamentos")).willReturn(
                aResponse().withStatus(201).withHeader("Content-Type", contentType).withBody(corpo)));

        esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "pagamentos");

        ESTOQUE.verify(1, deleteReserva());
        ESTOQUE.verify(0, postConfirmacao());
    }
}
```

### 4.5 `FluxoBasicoIT.java` (linha de base)

Serve para provar que os stubs e a configuração estão certos: se este teste falhar, o problema é do ambiente de teste, não do serviço.

```java
package com.estudo.checkout.integracao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FluxoBasicoIT extends IntegracaoBase {

    @Test
    @DisplayName("B-01: cartão aprovado confirma o pedido e cobra o total consultado no Estoque")
    void cartao_fluxoFeliz() throws Exception {
        prepararFluxoFeliz();

        ResultActions resposta = criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMADO"))
                .andExpect(jsonPath("$.totalCentavos").value(2000))
                .andExpect(jsonPath("$.reservaId").value(RESERVA_ID))
                .andExpect(jsonPath("$.pagamentoId").value("pg-1"));
        String id = idDoPedido(resposta);

        PAGAMENTOS.verify(1, postRequestedFor(urlEqualTo("/pagamentos"))
                .withHeader("Idempotency-Key", equalTo("pedido-" + id))
                .withRequestBody(matchingJsonPath("$.pedidoId", equalTo(id)))
                .withRequestBody(matchingJsonPath("$.valorCentavos", equalTo("2000")))
                .withRequestBody(matchingJsonPath("$.metodo", equalTo("CARTAO")))
                .withRequestBody(matchingJsonPath("$.tokenCartao", equalTo("tok_teste"))));
        ESTOQUE.verify(1, postConfirmacao());
        ESTOQUE.verify(0, deleteReserva());
    }

    @Test
    @DisplayName("B-02: pedido com falha de integração é gravado e consultável em GET /pedidos/{id}")
    void falhaDeIntegracaoEPersistidaEConsultavel() throws Exception {
        prepararFluxoFeliz();
        ESTOQUE.stubFor(get(urlEqualTo("/produtos/SKU-1")).willReturn(aResponse().withStatus(500)));

        String id = idDoPedido(esperarFalhaDeIntegracao(criarPedido(PEDIDO_CARTAO), "estoque"));

        mvc.perform(MockMvcRequestBuilders.get("/pedidos/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("FALHA_INTEGRACAO"));
    }

    @Test
    @DisplayName("B-03: pedido só com itens gratuitos não depende do Pagamentos")
    void pedidoGratuito_naoDependeDoPagamentos() throws Exception {
        prepararFluxoFeliz();
        stubProduto("SKU-1", 0);
        PAGAMENTOS.stop(); // fora do ar, e mesmo assim o pedido fecha

        criarPedido(PEDIDO_CARTAO)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMADO"))
                .andExpect(jsonPath("$.totalCentavos").value(0));

        ESTOQUE.verify(1, postConfirmacao());
    }
}
```

## 5. Como rodar e o que esperar

```bash
./mvnw test                        # arquivos em src/test/java
./mvnw test -Pexperimentos         # arquivos em experimentos/java
./mvnw test -Dtest='Compensacao*IT'
```

São cerca de 90 casos de execução (os `@ParameterizedTest` se multiplicam). O tempo é dominado pelos timeouts simulados e deve ficar em algumas dezenas de segundos.

Resultado esperado contra o código atual, **pela minha leitura** (não executei):

| Testes | Hoje | Motivo |
|---|---|---|
| `CompensacaoDeReservaIT` C-04 (7 falhas transitórias + conexão recusada) e C-05 (6 respostas fora do contrato) | **14 falham** | `DELETE /reservas/res-1` nunca é chamado: bug 1.1 |
| `FalhasPagamentosIT` P-04 (HTTP 400 e 422) | **2 falham** | 3 chamadas em vez de 1: ponto 1.2, confirme com o contrato |
| `FalhasEstoqueIT` X-01 | **1 falha** | pedido `CONFIRMADO` com total negativo: ponto 1.3 |
| Todos os outros | passam | descrevem o comportamento correto que o código já tem |

Depois de aplicar a correção de 1.1, C-04 e C-05 devem ficar verdes. Aproveite para acrescentar um teste de regressão da própria correção: Pagamentos indisponível **e** `DELETE` da reserva falhando ainda deve devolver 503 (a falha de compensação não pode virar 500).

Pontos de atenção:

- **Timeouts de 500 ms** funcionam bem em máquina local. Em CI lenta, aumente `timeout-ms` (em `IntegracaoBase`) e `ATRASO_MS` juntos, mantendo o atraso bem acima do timeout.
- **Falhas de rede reaproveitando conexão.** O `HttpClient` do JDK mantém um pool, e o WireMock fecha a conexão nos testes com `Fault`. Se E-04 ou E-10 oscilarem, o suspeito é uma conexão fechada reaproveitada por outro teste. Rodar de novo confirma; por isso as contagens exatas ficam só em POST e DELETE.
- **Códigos 2xx dos stubs** (201 em reserva e pagamento, 204 em confirmação e liberação) são suposições. Os clients aceitam qualquer 2xx, então não muda o resultado, mas vale alinhar com os YAMLs.
- **P-04 é uma decisão de contrato.** Se o Pagamentos documentar que algum 4xx deve ser repetido, troque o `verify(1, ...)` pelo número de tentativas correto ou restrinja o teste aos códigos que realmente não são repetíveis.

Se você me passar `contratos/estoque-api.yaml` e `contratos/pagamentos-api.yaml`, eu ajusto os códigos HTTP, a política de retry e os campos de erro, e posso acrescentar os cenários que o contrato prevê e eu não consegui inferir do código.
