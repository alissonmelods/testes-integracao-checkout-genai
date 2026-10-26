> **Resposta da LLM, sem edição.** Prompt: [1-cenarios-integracao/v1.md](../../prompts/1-cenarios-integracao/v1.md) | Modelo: Claude Sonnet 5.5 | Data: 26/10/2026 | Código enviado: commit `4a9ccee` (antes das correções) | 2ª execução: a 1ª passou do limite de 128 mil tokens de saída e não terminou

---

# Testes de integração do checkout (`POST /pedidos`)

Li o código com olhar de QA e montei: (1) a estratégia, (2) o catálogo de cenários e (3) os testes em JUnit 5. No fim há uma lista de pontos do código que merecem uma decisão do time. O `pom.xml` não precisa de nenhuma dependência nova: `spring-boot-starter-test` (JUnit 5, AssertJ, JsonPath) e `wiremock-standalone` já estão lá.

## 1. Estratégia

- **Caixa-preta na API do serviço de Pedidos**, com o Spring Boot de verdade (`@SpringBootTest` em porta aleatória), os clients reais (`RestClient` + `HttpClient` do JDK) e **dois WireMock**: um faz o papel do Estoque, outro do Pagamentos. Nada de `@MockBean` nos clients, porque serialização, status HTTP, timeout, retentativa e headers são justamente o que queremos exercitar.
- Cada teste verifica três coisas: (a) a resposta de `POST /pedidos` (status HTTP e corpo); (b) as chamadas que **foram** feitas (quantidade, corpo, `Idempotency-Key`); (c) as chamadas que **não podem** ter sido feitas (ex.: cobrança sem estoque, liberação de reserva depois de pagamento aprovado).
- Rede de segurança: depois de cada teste a classe base falha se o serviço fez alguma chamada **sem stub**. Sem isso o WireMock responderia 404 e um erro de rota poderia passar despercebido, principalmente nas chamadas de compensação, cujo erro o serviço só registra em log.
- Os testes sobrescrevem a configuração: URLs dos mocks, timeout de 1 s (no `application.yml` são 2 s e 3 s) e 3 tentativas. Assim a suíte fica rápida e independente do YAML. Nos cenários de timeout a resposta do mock é atrasada em 2,5 s.
- Os WireMock sobem **uma vez por JVM** (bloco `static`) e são zerados antes de cada teste. O Spring reaproveita o contexto entre as classes de teste e esse contexto guarda as portas dos mocks; subir e derrubar o WireMock por classe quebraria a segunda classe. Consequência: não habilite execução de testes em paralelo.

## 2. Premissas

Os arquivos `contratos/*.yaml` não vieram na mensagem, então os stubs seguem o que o código dos clients envia e lê. Se o contrato real for diferente, ajuste apenas os helpers da classe base:

- `GET /produtos/{sku}`: 200 `{"sku","precoCentavos"}`; 404 se o SKU não existe.
- `POST /reservas`: 201 `{"id","status"}`; 409 `{"codigo","mensagem","sku","disponivel"}` sem estoque; 404 com `sku` se o produto não existe.
- `POST /reservas/{id}/confirmacao` e `DELETE /reservas/{id}`: 204.
- `POST /pagamentos`: 201 `{"id","status":"APROVADO|RECUSADO","motivo"}`. A recusa é uma resposta 2xx com `status=RECUSADO`; um 4xx viraria falha de integração (ver PG-06).

## 3. Regras do checkout que os testes cobrem

| Situação | `status` do pedido | HTTP | Reserva no Estoque | Cobranças |
|---|---|---|---|---|
| Pagamento aprovado | `CONFIRMADO` | 201 | confirmada; se a confirmação falhar, só log | 1 |
| SKU inexistente (consulta ou reserva) | `PRODUTO_INEXISTENTE` | 422 | nada reservado | 0 |
| Estoque insuficiente | `SEM_ESTOQUE` | 409 | nada reservado | 0 |
| Pagamento recusado | `PAGAMENTO_RECUSADO` | 402 | liberada; se a liberação falhar, só log | 1, sem retentativa |
| Estoque com timeout, 4xx/5xx ou resposta fora do contrato | `FALHA_INTEGRACAO` | 503 | sem id para liberar; Estoque nunca é repetido | 0 |
| Pagamentos esgota 3 tentativas, responde 4xx ou fora do contrato | `FALHA_INTEGRACAO` | 503 | liberada | de 1 a 3 (mesma `Idempotency-Key`) |
| Entrada inválida | nenhum pedido é criado | 400 | nada | 0 |

## 4. Catálogo de cenários

### CF: caminho feliz (`CheckoutSucessoIT`)

| ID | Cenário | Resultado esperado |
|---|---|---|
| CF-01 | PIX, 1 item (2 un. a 1000 centavos) | 201 `CONFIRMADO`, total 2000. Estoque: 1 consulta, 1 reserva (com `pedidoId` e itens), 1 confirmação, 0 liberações. Pagamentos: 1 cobrança com `Idempotency-Key = pedido-<id>` e sem `tokenCartao` |
| CF-02 | CARTAO com `tokenCartao` | 201; o token vai no corpo da cobrança |
| CF-03 | Dois itens (2 x 1000 + 3 x 550) | total 3650; uma única reserva com as duas linhas; cobrança de 3650 |
| CF-04 | Cliente tenta enviar preço/total no JSON | campos ignorados; vale o preço do Estoque |
| CF-05 | Respostas do Estoque e do Pagamentos com campos extras | tolerados; 201 |
| CF-06 | Quantidade 1 e 99 (limites) | 201 |
| CF-07 | 20 itens (limite) | 201; 20 consultas de preço |

### VAL: validação da entrada (`CheckoutValidacaoIT`)

| ID | Cenário | Resultado esperado |
|---|---|---|
| VAL-01 | 14 variações: `clienteId` ausente ou em branco; `itens` ausente, vazio, com 21 itens ou com item nulo; `sku` em branco; `quantidade` ausente, 0, negativa ou 100; `metodoPagamento` ausente; CARTAO sem token ou com token em branco | 400 `DADOS_INVALIDOS` com `detalhes`; nenhuma chamada ao Estoque ou ao Pagamentos |
| VAL-02 | CARTAO sem `tokenCartao` | 400 com a mensagem "tokenCartao é obrigatório quando metodoPagamento é CARTAO" |
| VAL-03 | Corpo ilegível: JSON truncado, corpo vazio, `metodoPagamento` desconhecido, `quantidade` textual, `quantidade` 1.5, raiz do JSON como array | 400 `DADOS_INVALIDOS` com a mensagem genérica; nenhuma chamada externa. O 1.5 prova que `accept-float-as-int: false` vale também para a entrada |
| VAL-04 | `Content-Type: text/plain` | 415 |

### ES: Estoque (`CheckoutEstoqueIT`)

| ID | Cenário | Resultado esperado |
|---|---|---|
| ES-01 | SKU inexistente na consulta (404) | 422 `PRODUTO_INEXISTENTE`; nada reservado nem cobrado |
| ES-02 | 2º item inexistente | 422; 1º item com preço, 2º sem preço, total nulo; nada reservado |
| ES-03 | Reserva responde 409 | 409 `SEM_ESTOQUE`, motivo com SKU e disponível; sem cobrança e sem liberação |
| ES-04 | 409 com corpo vazio, JSON truncado ou HTML | continua 409 `SEM_ESTOQUE` (corpo de erro ilegível não vira 503) |
| ES-05 | Reserva responde 404 | 422 `PRODUTO_INEXISTENTE` com o SKU do corpo de erro |
| ES-06 | Consulta de preço com HTTP 400/401/403/500/502/503 | 503 `FALHA_INTEGRACAO`; exatamente 1 chamada (sem retentativa); nada reservado nem cobrado |
| ES-07 | Produto fora do contrato: sem preço, preço nulo, negativo, 89.9, "abc", não-JSON, corpo vazio | 503; nada reservado nem cobrado |
| ES-08 | Timeout na consulta de preço | 503; 1 chamada; nada reservado |
| ES-09 | Reserva com HTTP 400/422/500/503 | 503; 1 chamada (reserva não é idempotente); sem cobrança; sem liberação |
| ES-10 | Timeout na reserva | 503; 1 chamada; sem cobrança |
| ES-11 | Reserva 201 sem `id` ou sem corpo | 503; sem cobrança |

### PG: Pagamentos (`CheckoutPagamentosIT`)

| ID | Cenário | Resultado esperado |
|---|---|---|
| PG-01 | Pagamento recusado | 402 `PAGAMENTO_RECUSADO` com o motivo e o `pagamentoId`; 1 cobrança; reserva liberada 1 vez; sem confirmação |
| PG-02 | Recusa e a liberação responde 404/500/503 | continua 402; a liberação é tentada uma vez |
| PG-03 | Recusa e a liberação estoura o timeout | continua 402 |
| PG-04 | 1ª tentativa falha (HTTP 500/502/503/504, conexão resetada, resposta vazia, timeout) e a 2ª aprova | 201 `CONFIRMADO`; 2 cobranças com a **mesma** `Idempotency-Key` e o mesmo corpo; 1 reserva; 1 confirmação |
| PG-05 | Todas as tentativas falham (mesmos 7 tipos de falha) | 503 `FALHA_INTEGRACAO`, "pagamentos: indisponível após 3 tentativas"; exatamente 3 requisições, todas com a mesma chave; reserva liberada 1 vez; sem confirmação |
| PG-06 | HTTP 400/401/404/409/422/429 | não é repetido (1 chamada); 503; reserva liberada |
| PG-07 | 201 fora do contrato: sem `id`, sem `status`, `status` desconhecido, corpo vazio | não é repetido; 503; reserva liberada; sem `pagamentoId` |
| PG-08 | Falha no pagamento e na liberação | 503 com o motivo do pagamento; liberação tentada uma vez |
| PG-09 | Dois pedidos iguais em sequência | ids diferentes e `Idempotency-Key` diferentes (uma chave fixa faria o Pagamentos devolver a 1ª cobrança) |

### CO: confirmação, CP: consulta e IN: infraestrutura

| ID | Cenário | Resultado esperado |
|---|---|---|
| CO-01 | Pagamento aprovado e a confirmação responde 404/409/500/503 | continua 201 `CONFIRMADO`; confirmação tentada uma vez; reserva **não** é liberada |
| CO-02 | Pagamento aprovado e a confirmação estoura o timeout | idem |
| CP-01 | `GET /pedidos/{id}` de pedido confirmado | 200 com o mesmo corpo do POST; nenhuma chamada nova aos serviços |
| CP-02 | `GET /pedidos/{id}` de pedido recusado | 200 `PAGAMENTO_RECUSADO` (pedidos com falha também ficam gravados) |
| CP-03 | `GET /pedidos/{id}` inexistente | 404 |
| IN-01 | Estoque com conexão recusada | 503 `FALHA_INTEGRACAO` com motivo "estoque: ..." |

Fora do escopo desta entrega: concorrência pelo último item (responsabilidade do Estoque), carga, persistência (o repositório é em memória) e validação dos stubs contra os YAML de contrato (recomendada, ver o fim).

## 5. Implementação

Todos os arquivos ficam no pacote `com.estudo.checkout`, em `src/test/java/com/estudo/checkout/` (ou em `experimentos/java/com/estudo/checkout/`, se você roda com `-Pexperimentos`). Os nomes terminam em `IT`, que o seu `maven-surefire-plugin` já inclui.

```
IntegracaoBase.java              WireMock, propriedades, stubs e verificações reutilizáveis
CheckoutSucessoIT.java           CF-01 a CF-07
CheckoutValidacaoIT.java         VAL-01 a VAL-04
CheckoutEstoqueIT.java           ES-01 a ES-11
CheckoutPagamentosIT.java        PG-01 a PG-09
CheckoutConfirmacaoIT.java       CO-01, CO-02
ConsultaPedidoIT.java            CP-01 a CP-03
CheckoutServicosForaDoArIT.java  IN-01
```

### IntegracaoBase.java

```java
package com.estudo.checkout;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class IntegracaoBase {

    static final int MAX_TENTATIVAS = 3;
    static final int TIMEOUT_MS = 1000;
    /** Maior que o timeout: a resposta atrasada faz o client estourar o tempo. */
    static final int ATRASO_MS = 2500;

    static final String PEDIDO_PIX = """
            {"clienteId":"cli-1","metodoPagamento":"PIX","itens":[{"sku":"SKU-1","quantidade":2}]}""";

    // Um servidor por serviço externo, iniciado uma única vez por JVM (ver "Estratégia").
    static final WireMockServer estoque = new WireMockServer(options().dynamicPort());
    static final WireMockServer pagamentos = new WireMockServer(options().dynamicPort());

    static {
        estoque.start();
        pagamentos.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            estoque.stop();
            pagamentos.stop();
        }));
    }

    @DynamicPropertySource
    static void configurarServicosExternos(DynamicPropertyRegistry registry) {
        registry.add("servicos.estoque.url", estoque::baseUrl);
        registry.add("servicos.estoque.timeout-ms", () -> TIMEOUT_MS);
        registry.add("servicos.pagamentos.url", pagamentos::baseUrl);
        registry.add("servicos.pagamentos.timeout-ms", () -> TIMEOUT_MS);
        registry.add("servicos.pagamentos.max-tentativas", () -> MAX_TENTATIVAS);
    }

    @Autowired
    private TestRestTemplate http;

    @BeforeEach
    void zerarMocks() {
        estoque.resetAll();
        pagamentos.resetAll();
    }

    @AfterEach
    void nenhumaChamadaSemStub() {
        assertThat(estoque.findAllUnmatchedRequests()).as("chamadas ao Estoque sem stub").isEmpty();
        assertThat(pagamentos.findAllUnmatchedRequests()).as("chamadas ao Pagamentos sem stub").isEmpty();
    }

    // ------------------------------------------------------------------ chamadas ao serviço de Pedidos

    /** Status HTTP e corpo de uma resposta, com leitura de campos por JsonPath. */
    record Resposta(int status, String corpo) {

        String texto(String caminho) {
            Object valor = JsonPath.read(corpo, caminho);
            return valor == null ? null : valor.toString();
        }

        Long numero(String caminho) {
            Object valor = JsonPath.read(corpo, caminho);
            return valor == null ? null : ((Number) valor).longValue();
        }

        List<String> lista(String caminho) {
            List<?> valores = JsonPath.read(corpo, caminho);
            return valores.stream().map(Object::toString).toList();
        }
    }

    Resposta postarPedido(String corpoJson) {
        return enviarPedido(corpoJson, MediaType.APPLICATION_JSON);
    }

    Resposta enviarPedido(String corpo, MediaType tipoDoConteudo) {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(tipoDoConteudo);
        ResponseEntity<String> resposta =
                http.exchange("/pedidos", HttpMethod.POST, new HttpEntity<>(corpo, cabecalhos), String.class);
        return new Resposta(resposta.getStatusCode().value(), resposta.getBody());
    }

    Resposta buscarPedido(String id) {
        ResponseEntity<String> resposta = http.getForEntity("/pedidos/{id}", String.class, id);
        return new Resposta(resposta.getStatusCode().value(), resposta.getBody());
    }

    /** Pedido PIX com {@code quantidade} itens (SKU-1, SKU-2, ...), uma unidade de cada. */
    static String pedidoComItens(int quantidade) {
        String itens = IntStream.rangeClosed(1, quantidade)
                .mapToObj(i -> "{\"sku\":\"SKU-%d\",\"quantidade\":1}".formatted(i))
                .collect(Collectors.joining(","));
        return "{\"clienteId\":\"cli-1\",\"metodoPagamento\":\"PIX\",\"itens\":[" + itens + "]}";
    }

    // ------------------------------------------------------------------ stubs do Estoque

    static ResponseDefinitionBuilder jsonComStatus(int status, String corpo) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(corpo);
    }

    static void estoqueProduto(String sku, long precoCentavos) {
        estoqueProdutoComResposta(sku, jsonComStatus(200,
                "{\"sku\":\"%s\",\"precoCentavos\":%d}".formatted(sku, precoCentavos)));
    }

    static void estoqueProdutoComResposta(String sku, ResponseDefinitionBuilder resposta) {
        estoque.stubFor(get(urlEqualTo("/produtos/" + sku)).willReturn(resposta));
    }

    static void estoqueTodosOsProdutos(long precoCentavos) {
        estoque.stubFor(get(urlPathMatching("/produtos/.+"))
                .willReturn(jsonComStatus(200, "{\"precoCentavos\":%d}".formatted(precoCentavos))));
    }

    static void estoqueReserva(String reservaId) {
        estoqueReservaComResposta(jsonComStatus(201,
                "{\"id\":\"%s\",\"status\":\"RESERVADA\"}".formatted(reservaId)));
    }

    static void estoqueReservaComResposta(ResponseDefinitionBuilder resposta) {
        estoque.stubFor(post(urlEqualTo("/reservas")).willReturn(resposta));
    }

    static void estoqueConfirmacao(String reservaId, ResponseDefinitionBuilder resposta) {
        estoque.stubFor(post(urlEqualTo("/reservas/" + reservaId + "/confirmacao")).willReturn(resposta));
    }

    static void estoqueConfirmacaoOk(String reservaId) {
        estoqueConfirmacao(reservaId, aResponse().withStatus(204));
    }

    static void estoqueLiberacao(String reservaId, ResponseDefinitionBuilder resposta) {
        estoque.stubFor(delete(urlEqualTo("/reservas/" + reservaId)).willReturn(resposta));
    }

    static void estoqueLiberacaoOk(String reservaId) {
        estoqueLiberacao(reservaId, aResponse().withStatus(204));
    }

    // ------------------------------------------------------------------ stubs do Pagamentos

    static ResponseDefinitionBuilder respostaAprovada(String pagamentoId) {
        return jsonComStatus(201, "{\"id\":\"%s\",\"status\":\"APROVADO\"}".formatted(pagamentoId));
    }

    static ResponseDefinitionBuilder respostaRecusada(String pagamentoId, String motivo) {
        return jsonComStatus(201,
                "{\"id\":\"%s\",\"status\":\"RECUSADO\",\"motivo\":\"%s\"}".formatted(pagamentoId, motivo));
    }

    static void pagamentoResponde(ResponseDefinitionBuilder resposta) {
        pagamentos.stubFor(post(urlEqualTo("/pagamentos")).willReturn(resposta));
    }

    // ------------------------------------------------------------------ combinações usadas por vários testes

    /** SKU-1 custa 1000 centavos e a reserva res-1 é criada. */
    static void prepararEstoqueAteAReserva() {
        estoqueProduto("SKU-1", 1000);
        estoqueReserva("res-1");
    }

    /** Estoque até a reserva, pagamento aprovado (pag-1) e confirmação da reserva aceita. */
    static void prepararCaminhoFeliz() {
        prepararEstoqueAteAReserva();
        pagamentoResponde(respostaAprovada("pag-1"));
        estoqueConfirmacaoOk("res-1");
    }

    // ------------------------------------------------------------------ verificações

    static void verificarReservasCriadas(int vezes) {
        estoque.verify(vezes, postRequestedFor(urlEqualTo("/reservas")));
    }

    static void verificarConfirmacoes(String reservaId, int vezes) {
        estoque.verify(vezes, postRequestedFor(urlEqualTo("/reservas/" + reservaId + "/confirmacao")));
    }

    static void verificarLiberacoes(String reservaId, int vezes) {
        estoque.verify(vezes, deleteRequestedFor(urlEqualTo("/reservas/" + reservaId)));
    }

    static void verificarNenhumaLiberacao() {
        estoque.verify(0, deleteRequestedFor(anyUrl()));
    }

    static void verificarCobrancas(int vezes) {
        pagamentos.verify(vezes, postRequestedFor(urlEqualTo("/pagamentos")));
    }

    static void verificarNenhumaChamadaExterna() {
        assertThat(estoque.getAllServeEvents()).as("chamadas ao Estoque").isEmpty();
        assertThat(pagamentos.getAllServeEvents()).as("chamadas ao Pagamentos").isEmpty();
    }
}
```

### CheckoutSucessoIT.java

```java
package com.estudo.checkout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Checkout: caminho feliz")
class CheckoutSucessoIT extends IntegracaoBase {

    @Test
    @DisplayName("CF-01: PIX com um item confirma o pedido, cobra o total e confirma a reserva")
    void pixComUmItemConfirmaOPedido() {
        prepararCaminhoFeliz();

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.texto("$.status")).isEqualTo("CONFIRMADO");
        assertThat(resposta.texto("$.motivo")).isNull();
        assertThat(resposta.texto("$.clienteId")).isEqualTo("cli-1");
        assertThat(resposta.texto("$.metodoPagamento")).isEqualTo("PIX");
        assertThat(resposta.numero("$.totalCentavos")).isEqualTo(2000L);
        assertThat(resposta.texto("$.itens[0].sku")).isEqualTo("SKU-1");
        assertThat(resposta.numero("$.itens[0].quantidade")).isEqualTo(2L);
        assertThat(resposta.numero("$.itens[0].precoUnitarioCentavos")).isEqualTo(1000L);
        assertThat(resposta.texto("$.reservaId")).isEqualTo("res-1");
        assertThat(resposta.texto("$.pagamentoId")).isEqualTo("pag-1");

        String pedidoId = resposta.texto("$.id");
        assertThat(pedidoId).isNotBlank();
        estoque.verify(1, getRequestedFor(urlEqualTo("/produtos/SKU-1")));
        estoque.verify(1, postRequestedFor(urlEqualTo("/reservas"))
                .withRequestBody(equalToJson("""
                        {"pedidoId":"%s","itens":[{"sku":"SKU-1","quantidade":2}]}""".formatted(pedidoId))));
        pagamentos.verify(1, postRequestedFor(urlEqualTo("/pagamentos"))
                .withHeader("Idempotency-Key", equalTo("pedido-" + pedidoId))
                .withRequestBody(equalToJson("""
                        {"pedidoId":"%s","valorCentavos":2000,"metodo":"PIX"}""".formatted(pedidoId))));
        verificarConfirmacoes("res-1", 1);
        verificarNenhumaLiberacao();
    }

    @Test
    @DisplayName("CF-02: CARTAO envia o tokenCartao ao Pagamentos")
    void cartaoEnviaOTokenAoPagamentos() {
        prepararCaminhoFeliz();

        Resposta resposta = postarPedido("""
                {"clienteId":"cli-1","metodoPagamento":"CARTAO","tokenCartao":"tok_abc123",
                 "itens":[{"sku":"SKU-1","quantidade":2}]}""");

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.texto("$.status")).isEqualTo("CONFIRMADO");
        assertThat(resposta.texto("$.metodoPagamento")).isEqualTo("CARTAO");
        pagamentos.verify(1, postRequestedFor(urlEqualTo("/pagamentos"))
                .withRequestBody(equalToJson("""
                        {"pedidoId":"%s","valorCentavos":2000,"metodo":"CARTAO","tokenCartao":"tok_abc123"}"""
                        .formatted(resposta.texto("$.id")))));
    }

    @Test
    @DisplayName("CF-03: vários itens somam preço x quantidade e vão na mesma reserva")
    void variosItensSomamPrecoVezesQuantidade() {
        estoqueProduto("SKU-1", 1000);
        estoqueProduto("SKU-2", 550);
        estoqueReserva("res-1");
        pagamentoResponde(respostaAprovada("pag-1"));
        estoqueConfirmacaoOk("res-1");

        Resposta resposta = postarPedido("""
                {"clienteId":"cli-1","metodoPagamento":"PIX","itens":[
                  {"sku":"SKU-1","quantidade":2},{"sku":"SKU-2","quantidade":3}]}""");

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.numero("$.totalCentavos")).isEqualTo(3650L); // 2 x 1000 + 3 x 550
        assertThat(resposta.numero("$.itens[1].precoUnitarioCentavos")).isEqualTo(550L);

        String pedidoId = resposta.texto("$.id");
        estoque.verify(1, getRequestedFor(urlEqualTo("/produtos/SKU-1")));
        estoque.verify(1, getRequestedFor(urlEqualTo("/produtos/SKU-2")));
        estoque.verify(1, postRequestedFor(urlEqualTo("/reservas"))
                .withRequestBody(equalToJson("""
                        {"pedidoId":"%s","itens":[{"sku":"SKU-1","quantidade":2},{"sku":"SKU-2","quantidade":3}]}"""
                        .formatted(pedidoId))));
        pagamentos.verify(1, postRequestedFor(urlEqualTo("/pagamentos"))
                .withRequestBody(equalToJson("""
                        {"pedidoId":"%s","valorCentavos":3650,"metodo":"PIX"}""".formatted(pedidoId))));
    }

    @Test
    @DisplayName("CF-04: o preço vem do Estoque; preço e total enviados pelo cliente são ignorados")
    void precoEnviadoPeloClienteEIgnorado() {
        prepararCaminhoFeliz();

        Resposta resposta = postarPedido("""
                {"clienteId":"cli-1","metodoPagamento":"PIX","totalCentavos":1,
                 "itens":[{"sku":"SKU-1","quantidade":2,"precoUnitarioCentavos":1}]}""");

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.numero("$.totalCentavos")).isEqualTo(2000L);
        assertThat(resposta.numero("$.itens[0].precoUnitarioCentavos")).isEqualTo(1000L);
        pagamentos.verify(1, postRequestedFor(urlEqualTo("/pagamentos"))
                .withRequestBody(equalToJson("""
                        {"pedidoId":"%s","valorCentavos":2000,"metodo":"PIX"}"""
                        .formatted(resposta.texto("$.id")))));
    }

    @Test
    @DisplayName("CF-05: campos extras nas respostas do Estoque e do Pagamentos são ignorados")
    void camposExtrasNasRespostasSaoIgnorados() {
        estoqueProdutoComResposta("SKU-1", jsonComStatus(200,
                "{\"sku\":\"SKU-1\",\"nome\":\"Camiseta\",\"precoCentavos\":1000,\"disponivel\":50}"));
        estoqueReservaComResposta(jsonComStatus(201,
                "{\"id\":\"res-1\",\"status\":\"RESERVADA\",\"expiraEm\":\"2030-01-01T00:00:00Z\"}"));
        pagamentoResponde(jsonComStatus(201,
                "{\"id\":\"pag-1\",\"status\":\"APROVADO\",\"adquirente\":\"X\",\"valorCentavos\":2000}"));
        estoqueConfirmacaoOk("res-1");

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.texto("$.status")).isEqualTo("CONFIRMADO");
        assertThat(resposta.numero("$.totalCentavos")).isEqualTo(2000L);
    }

    @ParameterizedTest(name = "quantidade {0}")
    @ValueSource(ints = {1, 99})
    @DisplayName("CF-06: quantidades-limite (1 e 99) são aceitas")
    void quantidadesLimiteSaoAceitas(int quantidade) {
        prepararCaminhoFeliz();

        Resposta resposta = postarPedido("""
                {"clienteId":"cli-1","metodoPagamento":"PIX","itens":[{"sku":"SKU-1","quantidade":%d}]}"""
                .formatted(quantidade));

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.numero("$.totalCentavos")).isEqualTo(1000L * quantidade);
    }

    @Test
    @DisplayName("CF-07: pedido com exatamente 20 itens (limite) é aceito")
    void pedidoComVinteItensEAceito() {
        estoqueTodosOsProdutos(100);
        estoqueReserva("res-1");
        pagamentoResponde(respostaAprovada("pag-1"));
        estoqueConfirmacaoOk("res-1");

        Resposta resposta = postarPedido(pedidoComItens(20));

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.numero("$.totalCentavos")).isEqualTo(2000L);
        assertThat(resposta.lista("$.itens[*].sku")).hasSize(20);
        estoque.verify(20, getRequestedFor(urlPathMatching("/produtos/.+")));
    }
}
```

### CheckoutValidacaoIT.java

```java
package com.estudo.checkout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Checkout: validação da entrada")
class CheckoutValidacaoIT extends IntegracaoBase {

    static Stream<Arguments> pedidosComDadosInvalidos() {
        return Stream.of(
                arguments("clienteId ausente", """
                        {"metodoPagamento":"PIX","itens":[{"sku":"SKU-1","quantidade":1}]}"""),
                arguments("clienteId em branco", """
                        {"clienteId":" ","metodoPagamento":"PIX","itens":[{"sku":"SKU-1","quantidade":1}]}"""),
                arguments("itens ausente", """
                        {"clienteId":"cli-1","metodoPagamento":"PIX"}"""),
                arguments("itens vazio", """
                        {"clienteId":"cli-1","metodoPagamento":"PIX","itens":[]}"""),
                arguments("21 itens", pedidoComItens(21)),
                arguments("item nulo", """
                        {"clienteId":"cli-1","metodoPagamento":"PIX","itens":[null]}"""),
                arguments("sku em branco", """
                        {"clienteId":"cli-1","metodoPagamento":"PIX","itens":[{"sku":"","quantidade":1}]}"""),
                arguments("quantidade ausente", """
                        {"clienteId":"cli-1","metodoPagamento":"PIX","itens":[{"sku":"SKU-1"}]}"""),
                arguments("quantidade 0", """
                        {"clienteId":"cli-1","metodoPagamento":"PIX","itens":[{"sku":"SKU-1","quantidade":0}]}"""),
                arguments("quantidade negativa", """
                        {"clienteId":"cli-1","metodoPagamento":"PIX","itens":[{"sku":"SKU-1","quantidade":-3}]}"""),
                arguments("quantidade 100", """
                        {"clienteId":"cli-1","metodoPagamento":"PIX","itens":[{"sku":"SKU-1","quantidade":100}]}"""),
                arguments("metodoPagamento ausente", """
                        {"clienteId":"cli-1","itens":[{"sku":"SKU-1","quantidade":1}]}"""),
                arguments("CARTAO sem tokenCartao", """
                        {"clienteId":"cli-1","metodoPagamento":"CARTAO","itens":[{"sku":"SKU-1","quantidade":1}]}"""),
                arguments("CARTAO com tokenCartao em branco", """
                        {"clienteId":"cli-1","metodoPagamento":"CARTAO","tokenCartao":"  ",
                         "itens":[{"sku":"SKU-1","quantidade":1}]}"""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pedidosComDadosInvalidos")
    @DisplayName("VAL-01: dados inválidos -> 400 e nenhuma chamada externa")
    void dadosInvalidosRetornam400SemChamarServicos(String caso, String corpo) {
        Resposta resposta = postarPedido(corpo);

        assertThat(resposta.status()).isEqualTo(400);
        assertThat(resposta.texto("$.codigo")).isEqualTo("DADOS_INVALIDOS");
        assertThat(resposta.lista("$.detalhes")).isNotEmpty();
        verificarNenhumaChamadaExterna();
    }

    @Test
    @DisplayName("VAL-02: CARTAO sem tokenCartao explica o motivo")
    void cartaoSemTokenExplicaOMotivo() {
        Resposta resposta = postarPedido("""
                {"clienteId":"cli-1","metodoPagamento":"CARTAO","itens":[{"sku":"SKU-1","quantidade":1}]}""");

        assertThat(resposta.status()).isEqualTo(400);
        assertThat(resposta.lista("$.detalhes"))
                .anyMatch(d -> d.contains("tokenCartao é obrigatório quando metodoPagamento é CARTAO"));
        verificarNenhumaChamadaExterna();
    }

    static Stream<Arguments> corposIlegiveis() {
        return Stream.of(
                arguments("JSON truncado", "{\"clienteId\":\"cli-1\","),
                arguments("corpo vazio", ""),
                arguments("metodoPagamento desconhecido", """
                        {"clienteId":"cli-1","metodoPagamento":"BOLETO","itens":[{"sku":"SKU-1","quantidade":1}]}"""),
                arguments("quantidade textual", """
                        {"clienteId":"cli-1","metodoPagamento":"PIX","itens":[{"sku":"SKU-1","quantidade":"duas"}]}"""),
                arguments("quantidade com casa decimal", """
                        {"clienteId":"cli-1","metodoPagamento":"PIX","itens":[{"sku":"SKU-1","quantidade":1.5}]}"""),
                arguments("raiz do JSON é um array", "[]"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corposIlegiveis")
    @DisplayName("VAL-03: corpo ilegível -> 400 com a mensagem genérica e nenhuma chamada externa")
    void corpoIlegivelRetorna400(String caso, String corpo) {
        Resposta resposta = postarPedido(corpo);

        assertThat(resposta.status()).isEqualTo(400);
        assertThat(resposta.texto("$.codigo")).isEqualTo("DADOS_INVALIDOS");
        assertThat(resposta.lista("$.detalhes"))
                .containsExactly("corpo da requisição ausente ou com formato inválido");
        verificarNenhumaChamadaExterna();
    }

    @Test
    @DisplayName("VAL-04: Content-Type não suportado -> 415")
    void contentTypeNaoSuportadoRetorna415() {
        Resposta resposta = enviarPedido(PEDIDO_PIX, MediaType.TEXT_PLAIN);

        assertThat(resposta.status()).isEqualTo(415);
        verificarNenhumaChamadaExterna();
    }
}
```

### CheckoutEstoqueIT.java

```java
package com.estudo.checkout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Checkout: cenários do Estoque")
class CheckoutEstoqueIT extends IntegracaoBase {

    private static final String PEDIDO_COM_DOIS_ITENS = """
            {"clienteId":"cli-1","metodoPagamento":"PIX","itens":[
              {"sku":"SKU-1","quantidade":1},{"sku":"SKU-2","quantidade":1}]}""";

    @Test
    @DisplayName("ES-01: SKU inexistente na consulta de preço -> 422, sem reservar nem cobrar")
    void skuInexistenteNaConsultaDePreco() {
        estoqueProdutoComResposta("SKU-1", aResponse().withStatus(404));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(422);
        assertThat(resposta.texto("$.status")).isEqualTo("PRODUTO_INEXISTENTE");
        assertThat(resposta.texto("$.motivo")).contains("SKU-1");
        assertThat(resposta.texto("$.reservaId")).isNull();
        assertThat(resposta.texto("$.pagamentoId")).isNull();
        verificarReservasCriadas(0);
        verificarCobrancas(0);
    }

    @Test
    @DisplayName("ES-02: segundo item inexistente -> 422, preços parciais, total nulo e nada reservado")
    void segundoItemInexistente() {
        estoqueProduto("SKU-1", 1000);
        estoqueProdutoComResposta("SKU-2", aResponse().withStatus(404));

        Resposta resposta = postarPedido(PEDIDO_COM_DOIS_ITENS);

        assertThat(resposta.status()).isEqualTo(422);
        assertThat(resposta.texto("$.status")).isEqualTo("PRODUTO_INEXISTENTE");
        assertThat(resposta.texto("$.motivo")).contains("SKU-2");
        assertThat(resposta.numero("$.itens[0].precoUnitarioCentavos")).isEqualTo(1000L);
        assertThat(resposta.numero("$.itens[1].precoUnitarioCentavos")).isNull();
        assertThat(resposta.numero("$.totalCentavos")).isNull();
        verificarReservasCriadas(0);
        verificarCobrancas(0);
    }

    @Test
    @DisplayName("ES-03: sem estoque -> 409, sem cobrar e sem liberar (nada foi reservado)")
    void semEstoque() {
        estoqueProduto("SKU-1", 1000);
        estoqueReservaComResposta(jsonComStatus(409, """
                {"codigo":"ESTOQUE_INSUFICIENTE","mensagem":"Estoque insuficiente","sku":"SKU-1","disponivel":1}"""));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(409);
        assertThat(resposta.texto("$.status")).isEqualTo("SEM_ESTOQUE");
        assertThat(resposta.texto("$.motivo")).contains("SKU-1").contains("disponível: 1");
        assertThat(resposta.numero("$.totalCentavos")).isEqualTo(2000L);
        assertThat(resposta.texto("$.reservaId")).isNull();
        verificarReservasCriadas(1);
        verificarCobrancas(0);
        verificarNenhumaLiberacao();
    }

    static Stream<Arguments> corposDeErroIlegiveis() {
        return Stream.of(
                arguments("corpo vazio", "application/json", ""),
                arguments("JSON truncado", "application/json", "{\"codigo\":\"ESTOQUE_INSU"),
                arguments("HTML de um gateway", "text/html", "<html><body>Conflict</body></html>"));
    }

    @ParameterizedTest(name = "409 com {0}")
    @MethodSource("corposDeErroIlegiveis")
    @DisplayName("ES-04: 409 com corpo de erro ilegível continua sendo SEM_ESTOQUE")
    void semEstoqueComCorpoDeErroIlegivel(String caso, String tipoDoConteudo, String corpo) {
        estoqueProduto("SKU-1", 1000);
        estoqueReservaComResposta(aResponse().withStatus(409)
                .withHeader("Content-Type", tipoDoConteudo).withBody(corpo));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(409);
        assertThat(resposta.texto("$.status")).isEqualTo("SEM_ESTOQUE");
        verificarCobrancas(0);
    }

    @Test
    @DisplayName("ES-05: reserva responde 404 -> 422 com o SKU informado pelo Estoque")
    void skuInexistenteNaReserva() {
        estoqueProduto("SKU-1", 1000);
        estoqueReservaComResposta(jsonComStatus(404, """
                {"codigo":"PRODUTO_INEXISTENTE","mensagem":"SKU removido do catálogo","sku":"SKU-1"}"""));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(422);
        assertThat(resposta.texto("$.status")).isEqualTo("PRODUTO_INEXISTENTE");
        assertThat(resposta.texto("$.motivo")).contains("SKU-1");
        verificarCobrancas(0);
        verificarNenhumaLiberacao();
    }

    @ParameterizedTest(name = "HTTP {0} na consulta de preço")
    @ValueSource(ints = {400, 401, 403, 500, 502, 503})
    @DisplayName("ES-06: erro HTTP na consulta de preço -> 503, sem retentativa, sem reservar nem cobrar")
    void erroHttpNaConsultaDePreco(int statusHttp) {
        estoqueProdutoComResposta("SKU-1", aResponse().withStatus(statusHttp));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(503);
        assertThat(resposta.texto("$.status")).isEqualTo("FALHA_INTEGRACAO");
        assertThat(resposta.texto("$.motivo")).startsWith("estoque:");
        estoque.verify(1, getRequestedFor(urlEqualTo("/produtos/SKU-1")));
        verificarReservasCriadas(0);
        verificarCobrancas(0);
    }

    static Stream<Arguments> produtosForaDoContrato() {
        return Stream.of(
                arguments("sem precoCentavos", "{\"sku\":\"SKU-1\"}"),
                arguments("precoCentavos nulo", "{\"sku\":\"SKU-1\",\"precoCentavos\":null}"),
                arguments("precoCentavos negativo", "{\"sku\":\"SKU-1\",\"precoCentavos\":-1}"),
                arguments("precoCentavos com casa decimal (89.9)", "{\"sku\":\"SKU-1\",\"precoCentavos\":89.9}"),
                arguments("precoCentavos não numérico", "{\"sku\":\"SKU-1\",\"precoCentavos\":\"abc\"}"),
                arguments("corpo que não é JSON", "isto não é JSON"),
                arguments("corpo vazio", ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("produtosForaDoContrato")
    @DisplayName("ES-07: produto fora do contrato -> 503, sem reservar nem cobrar")
    void produtoForaDoContrato(String caso, String corpo) {
        estoqueProdutoComResposta("SKU-1", jsonComStatus(200, corpo));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(503);
        assertThat(resposta.texto("$.status")).isEqualTo("FALHA_INTEGRACAO");
        verificarReservasCriadas(0);
        verificarCobrancas(0);
    }

    @Test
    @DisplayName("ES-08: timeout na consulta de preço -> 503, uma única chamada")
    void timeoutNaConsultaDePreco() {
        estoqueProdutoComResposta("SKU-1",
                jsonComStatus(200, "{\"sku\":\"SKU-1\",\"precoCentavos\":1000}").withFixedDelay(ATRASO_MS));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(503);
        assertThat(resposta.texto("$.status")).isEqualTo("FALHA_INTEGRACAO");
        estoque.verify(1, getRequestedFor(urlEqualTo("/produtos/SKU-1")));
        verificarReservasCriadas(0);
        verificarCobrancas(0);
    }

    @ParameterizedTest(name = "HTTP {0} ao reservar")
    @ValueSource(ints = {400, 422, 500, 503})
    @DisplayName("ES-09: erro HTTP na reserva -> 503, sem retentativa, sem cobrar e sem liberar")
    void erroHttpNaReserva(int statusHttp) {
        estoqueProduto("SKU-1", 1000);
        estoqueReservaComResposta(aResponse().withStatus(statusHttp));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(503);
        assertThat(resposta.texto("$.status")).isEqualTo("FALHA_INTEGRACAO");
        assertThat(resposta.texto("$.motivo")).startsWith("estoque:");
        assertThat(resposta.texto("$.reservaId")).isNull();
        verificarReservasCriadas(1);
        verificarCobrancas(0);
        verificarNenhumaLiberacao();
    }

    @Test
    @DisplayName("ES-10: timeout na reserva -> 503, uma única chamada (reserva não é idempotente) e sem cobrança")
    void timeoutNaReserva() {
        estoqueProduto("SKU-1", 1000);
        estoqueReservaComResposta(
                jsonComStatus(201, "{\"id\":\"res-1\",\"status\":\"RESERVADA\"}").withFixedDelay(ATRASO_MS));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(503);
        assertThat(resposta.texto("$.status")).isEqualTo("FALHA_INTEGRACAO");
        assertThat(resposta.texto("$.reservaId")).isNull();
        verificarReservasCriadas(1);
        verificarCobrancas(0);
        verificarNenhumaLiberacao();
    }

    static Stream<Arguments> reservasForaDoContrato() {
        return Stream.of(
                arguments("sem id", "{\"status\":\"RESERVADA\"}"),
                arguments("com id nulo", "{\"id\":null,\"status\":\"RESERVADA\"}"),
                arguments("sem corpo", ""));
    }

    @ParameterizedTest(name = "reserva 201 {0}")
    @MethodSource("reservasForaDoContrato")
    @DisplayName("ES-11: reserva fora do contrato -> 503 e sem cobrança")
    void reservaForaDoContrato(String caso, String corpo) {
        estoqueProduto("SKU-1", 1000);
        estoqueReservaComResposta(jsonComStatus(201, corpo));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(503);
        assertThat(resposta.texto("$.status")).isEqualTo("FALHA_INTEGRACAO");
        assertThat(resposta.texto("$.reservaId")).isNull();
        verificarCobrancas(0);
        verificarNenhumaLiberacao();
    }
}
```

### CheckoutPagamentosIT.java

```java
package com.estudo.checkout;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Checkout: cenários do Pagamentos")
class CheckoutPagamentosIT extends IntegracaoBase {

    /** Falhas que o PagamentosClient trata como transitórias e, por isso, repete. */
    enum FalhaTransitoria {
        HTTP_500, HTTP_502, HTTP_503, HTTP_504, CONEXAO_RESETADA, RESPOSTA_VAZIA, TIMEOUT;

        ResponseDefinitionBuilder resposta() {
            return switch (this) {
                case HTTP_500 -> aResponse().withStatus(500);
                case HTTP_502 -> aResponse().withStatus(502);
                case HTTP_503 -> aResponse().withStatus(503);
                case HTTP_504 -> aResponse().withStatus(504);
                case CONEXAO_RESETADA -> aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER);
                case RESPOSTA_VAZIA -> aResponse().withFault(Fault.EMPTY_RESPONSE);
                case TIMEOUT -> jsonComStatus(201, "{\"id\":\"pag-lento\",\"status\":\"APROVADO\"}")
                        .withFixedDelay(ATRASO_MS);
            };
        }
    }

    @Test
    @DisplayName("PG-01: pagamento recusado -> 402, reserva liberada e sem confirmação")
    void pagamentoRecusado() {
        prepararEstoqueAteAReserva();
        pagamentoResponde(respostaRecusada("pag-1", "saldo insuficiente"));
        estoqueLiberacaoOk("res-1");

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(402);
        assertThat(resposta.texto("$.status")).isEqualTo("PAGAMENTO_RECUSADO");
        assertThat(resposta.texto("$.motivo")).isEqualTo("saldo insuficiente");
        assertThat(resposta.texto("$.reservaId")).isEqualTo("res-1");
        assertThat(resposta.texto("$.pagamentoId")).isEqualTo("pag-1");
        verificarCobrancas(1); // recusa não é repetida
        verificarLiberacoes("res-1", 1);
        verificarConfirmacoes("res-1", 0);
    }

    @ParameterizedTest(name = "liberação responde HTTP {0}")
    @ValueSource(ints = {404, 500, 503})
    @DisplayName("PG-02: recusa com falha na liberação da reserva continua sendo 402")
    void recusaComFalhaNaLiberacao(int statusHttp) {
        prepararEstoqueAteAReserva();
        pagamentoResponde(respostaRecusada("pag-1", "saldo insuficiente"));
        estoqueLiberacao("res-1", aResponse().withStatus(statusHttp));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(402);
        assertThat(resposta.texto("$.status")).isEqualTo("PAGAMENTO_RECUSADO");
        verificarLiberacoes("res-1", 1); // a liberação não é repetida
    }

    @Test
    @DisplayName("PG-03: recusa com timeout na liberação da reserva continua sendo 402")
    void recusaComTimeoutNaLiberacao() {
        prepararEstoqueAteAReserva();
        pagamentoResponde(respostaRecusada("pag-1", "saldo insuficiente"));
        estoqueLiberacao("res-1", aResponse().withStatus(204).withFixedDelay(ATRASO_MS));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(402);
        assertThat(resposta.texto("$.status")).isEqualTo("PAGAMENTO_RECUSADO");
        verificarLiberacoes("res-1", 1);
    }

    @ParameterizedTest(name = "1ª tentativa: {0}")
    @EnumSource(FalhaTransitoria.class)
    @DisplayName("PG-04: falha transitória na 1ª tentativa -> repete com a mesma Idempotency-Key e confirma")
    void falhaTransitoriaSeguidaDeSucesso(FalhaTransitoria falha) {
        prepararEstoqueAteAReserva();
        estoqueConfirmacaoOk("res-1");
        pagamentos.stubFor(post(urlEqualTo("/pagamentos")).inScenario("tentativas")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(falha.resposta())
                .willSetStateTo("segunda-tentativa"));
        pagamentos.stubFor(post(urlEqualTo("/pagamentos")).inScenario("tentativas")
                .whenScenarioStateIs("segunda-tentativa")
                .willReturn(respostaAprovada("pag-1")));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.texto("$.status")).isEqualTo("CONFIRMADO");
        assertThat(resposta.texto("$.pagamentoId")).isEqualTo("pag-1");

        List<LoggedRequest> tentativas = pagamentos.findAll(postRequestedFor(urlEqualTo("/pagamentos")));
        assertThat(tentativas).hasSize(2);
        assertThat(tentativas).extracting(t -> t.getHeader("Idempotency-Key"))
                .containsOnly("pedido-" + resposta.texto("$.id"));
        assertThat(tentativas).extracting(LoggedRequest::getBodyAsString)
                .containsOnly(tentativas.get(0).getBodyAsString());
        verificarReservasCriadas(1);
        verificarConfirmacoes("res-1", 1);
        verificarNenhumaLiberacao();
    }

    @ParameterizedTest(name = "todas as tentativas: {0}")
    @EnumSource(FalhaTransitoria.class)
    @DisplayName("PG-05: todas as tentativas falham -> 503, reserva liberada e sem confirmação")
    void todasAsTentativasFalham(FalhaTransitoria falha) {
        prepararEstoqueAteAReserva();
        estoqueLiberacaoOk("res-1");
        pagamentoResponde(falha.resposta());

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(503);
        assertThat(resposta.texto("$.status")).isEqualTo("FALHA_INTEGRACAO");
        assertThat(resposta.texto("$.motivo")).isEqualTo("pagamentos: indisponível após 3 tentativas");
        assertThat(resposta.texto("$.reservaId")).isEqualTo("res-1");
        assertThat(resposta.texto("$.pagamentoId")).isNull();

        List<LoggedRequest> tentativas = pagamentos.findAll(postRequestedFor(urlEqualTo("/pagamentos")));
        assertThat(tentativas).hasSize(MAX_TENTATIVAS); // nem a mais (stack HTTP), nem a menos
        assertThat(tentativas).extracting(t -> t.getHeader("Idempotency-Key"))
                .containsOnly("pedido-" + resposta.texto("$.id"));
        verificarLiberacoes("res-1", 1);
        verificarConfirmacoes("res-1", 0);
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {400, 401, 404, 409, 422, 429})
    @DisplayName("PG-06: erro 4xx do Pagamentos não é repetido -> 503 e reserva liberada")
    void erro4xxNaoEhRepetido(int statusHttp) {
        prepararEstoqueAteAReserva();
        estoqueLiberacaoOk("res-1");
        pagamentoResponde(aResponse().withStatus(statusHttp));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(503);
        assertThat(resposta.texto("$.status")).isEqualTo("FALHA_INTEGRACAO");
        assertThat(resposta.texto("$.motivo")).isEqualTo("pagamentos: erro não transitório em POST /pagamentos");
        verificarCobrancas(1);
        verificarLiberacoes("res-1", 1);
        verificarConfirmacoes("res-1", 0);
    }

    static Stream<Arguments> pagamentosForaDoContrato() {
        return Stream.of(
                arguments("sem id", "{\"status\":\"APROVADO\"}"),
                arguments("sem status", "{\"id\":\"pag-1\"}"),
                arguments("status desconhecido", "{\"id\":\"pag-1\",\"status\":\"PENDENTE\"}"),
                arguments("corpo vazio", ""));
    }

    @ParameterizedTest(name = "201 {0}")
    @MethodSource("pagamentosForaDoContrato")
    @DisplayName("PG-07: resposta fora do contrato não é repetida -> 503 e reserva liberada")
    void respostaForaDoContrato(String caso, String corpo) {
        prepararEstoqueAteAReserva();
        estoqueLiberacaoOk("res-1");
        pagamentoResponde(jsonComStatus(201, corpo));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(503);
        assertThat(resposta.texto("$.status")).isEqualTo("FALHA_INTEGRACAO");
        assertThat(resposta.texto("$.pagamentoId")).isNull();
        verificarCobrancas(1);
        verificarLiberacoes("res-1", 1);
        verificarConfirmacoes("res-1", 0);
    }

    @Test
    @DisplayName("PG-08: falha no pagamento e na liberação -> 503 com o motivo do pagamento")
    void falhaNoPagamentoEFalhaNaLiberacao() {
        prepararEstoqueAteAReserva();
        pagamentoResponde(aResponse().withStatus(500));
        estoqueLiberacao("res-1", aResponse().withStatus(500));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(503);
        assertThat(resposta.texto("$.status")).isEqualTo("FALHA_INTEGRACAO");
        assertThat(resposta.texto("$.motivo")).startsWith("pagamentos:");
        verificarCobrancas(MAX_TENTATIVAS);
        verificarLiberacoes("res-1", 1);
    }

    @Test
    @DisplayName("PG-09: cada pedido usa a sua própria Idempotency-Key")
    void cadaPedidoUsaSuaPropriaChaveDeIdempotencia() {
        prepararCaminhoFeliz();

        Resposta primeiro = postarPedido(PEDIDO_PIX);
        Resposta segundo = postarPedido(PEDIDO_PIX);

        String idPrimeiro = primeiro.texto("$.id");
        String idSegundo = segundo.texto("$.id");
        assertThat(idPrimeiro).isNotEqualTo(idSegundo);

        List<LoggedRequest> cobrancas = pagamentos.findAll(postRequestedFor(urlEqualTo("/pagamentos")));
        assertThat(cobrancas).extracting(c -> c.getHeader("Idempotency-Key"))
                .containsExactlyInAnyOrder("pedido-" + idPrimeiro, "pedido-" + idSegundo);
    }
}
```

### CheckoutConfirmacaoIT.java

```java
package com.estudo.checkout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Checkout: confirmação da reserva depois do pagamento aprovado")
class CheckoutConfirmacaoIT extends IntegracaoBase {

    @ParameterizedTest(name = "confirmação responde HTTP {0}")
    @ValueSource(ints = {404, 409, 500, 503})
    @DisplayName("CO-01: falha na confirmação não cancela o pedido já pago")
    void falhaNaConfirmacaoNaoCancelaPedidoPago(int statusHttp) {
        prepararEstoqueAteAReserva();
        pagamentoResponde(respostaAprovada("pag-1"));
        estoqueConfirmacao("res-1", aResponse().withStatus(statusHttp));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.texto("$.status")).isEqualTo("CONFIRMADO");
        assertThat(resposta.texto("$.pagamentoId")).isEqualTo("pag-1");
        verificarCobrancas(1);
        verificarConfirmacoes("res-1", 1); // sem retentativa
        verificarNenhumaLiberacao();       // pedido pago: a reserva não pode voltar para o estoque
    }

    @Test
    @DisplayName("CO-02: timeout na confirmação não cancela o pedido já pago")
    void timeoutNaConfirmacaoNaoCancelaPedidoPago() {
        prepararEstoqueAteAReserva();
        pagamentoResponde(respostaAprovada("pag-1"));
        estoqueConfirmacao("res-1", aResponse().withStatus(204).withFixedDelay(ATRASO_MS));

        Resposta resposta = postarPedido(PEDIDO_PIX);

        assertThat(resposta.status()).isEqualTo(201);
        assertThat(resposta.texto("$.status")).isEqualTo("CONFIRMADO");
        verificarConfirmacoes("res-1", 1);
        verificarNenhumaLiberacao();
    }
}
```

### ConsultaPedidoIT.java

```java
package com.estudo.checkout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Consulta de pedidos: GET /pedidos/{id}")
class ConsultaPedidoIT extends IntegracaoBase {

    @Test
    @DisplayName("CP-01: pedido confirmado volta igual ao do POST, sem novas chamadas aos serviços")
    void pedidoConfirmadoPodeSerConsultado() {
        prepararCaminhoFeliz();
        Resposta criado = postarPedido(PEDIDO_PIX);
        int chamadasAntes = estoque.getAllServeEvents().size() + pagamentos.getAllServeEvents().size();

        Resposta consultado = buscarPedido(criado.texto("$.id"));

        assertThat(consultado.status()).isEqualTo(200);
        assertThat(consultado.corpo()).isEqualTo(criado.corpo());
        assertThat(consultado.texto("$.status")).isEqualTo("CONFIRMADO");
        int chamadasDepois = estoque.getAllServeEvents().size() + pagamentos.getAllServeEvents().size();
        assertThat(chamadasDepois).isEqualTo(chamadasAntes);
    }

    @Test
    @DisplayName("CP-02: pedido com falha também fica gravado e consultável")
    void pedidoRecusadoTambemPodeSerConsultado() {
        prepararEstoqueAteAReserva();
        pagamentoResponde(respostaRecusada("pag-1", "saldo insuficiente"));
        estoqueLiberacaoOk("res-1");
        Resposta criado = postarPedido(PEDIDO_PIX);
        assertThat(criado.status()).isEqualTo(402);

        Resposta consultado = buscarPedido(criado.texto("$.id"));

        assertThat(consultado.status()).isEqualTo(200);
        assertThat(consultado.texto("$.status")).isEqualTo("PAGAMENTO_RECUSADO");
        assertThat(consultado.corpo()).isEqualTo(criado.corpo());
    }

    @Test
    @DisplayName("CP-03: pedido inexistente -> 404")
    void pedidoInexistenteRetorna404() {
        Resposta resposta = buscarPedido("nao-existe");

        assertThat(resposta.status()).isEqualTo(404);
        verificarNenhumaChamadaExterna();
    }
}
```

### CheckoutServicosForaDoArIT.java

```java
package com.estudo.checkout;

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
```

## 6. Como executar

```
mvnw test                               # suíte inteira (o surefire do pom já inclui *IT.java)
mvnw test -Dtest=CheckoutPagamentosIT   # uma classe
mvnw test -Pexperimentos                # se os arquivos estiverem em experimentos/java
```

- A suíte gasta cerca de 10 s só esperando timeouts (ES-08, ES-10, PG-03, PG-04 e PG-05 com `TIMEOUT`, CO-02), além de subir dois contextos do Spring (o da `IntegracaoBase` e o de `CheckoutServicosForaDoArIT`).
- Se algum cenário de timeout ficar instável em uma máquina lenta, aumente `TIMEOUT_MS` e `ATRASO_MS` na `IntegracaoBase`, mantendo o atraso bem acima do timeout.
- Quando um `verify` falha, o WireMock lista as requisições que ele realmente recebeu. Isso costuma bastar para descobrir se o erro está no teste, no stub ou no serviço.
- Não executei os testes aqui. Se algum falhar na sua máquina, me mande o stack trace e eu ajusto.

## 7. Pontos de atenção encontrados na leitura do código

Os testes acima descrevem o comportamento **atual**. Os itens abaixo são decisões que eu levaria ao time, porque alguns deles podem mudar o resultado esperado de um cenário.

1. **Pagamento em estado desconhecido.** Se as 3 tentativas terminam em timeout ou conexão resetada, ou se um 2xx chega ilegível ou sem `id`, o serviço assume "não cobrou", libera a reserva e responde 503 (PG-05 e PG-07). Só que a cobrança pode ter sido processada e apenas a resposta se perdeu. Não há consulta pela `Idempotency-Key`, estorno nem um status "pagamento indeterminado". O risco é cliente cobrado sem pedido e sem reserva.
2. **`POST /pedidos` não é idempotente.** O id do pedido é um UUID novo a cada requisição, então a `Idempotency-Key` só protege as retentativas internas. Se o cliente repetir o POST depois de um 503 ou de um timeout, nasce outro pedido com outra chave e a cobrança pode ser duplicada (PG-09 mostra as chaves distintas). Considere aceitar uma chave de idempotência enviada pelo cliente.
3. **Confirmação pendente sem rastro.** Pagamento aprovado com confirmação falhando devolve 201 `CONFIRMADO` e deixa apenas uma linha de log (CO-01 e CO-02). Como a reserva expira em 15 minutos, se ninguém reprocessar, o item volta ao estoque com o pedido já pago. Falta um campo ou status no pedido, ou um reprocessamento.
4. **Exceção inesperada depois da reserva.** `PedidoService.criar` trata só três exceções. Qualquer outra `RuntimeException` depois de `reservar` (um bug, por exemplo) sai como 500, o pedido não é gravado e a reserva não é liberada. Um `catch` genérico que chame `desfazerReserva` fecha a brecha. Não há como provocar isso de forma determinística pela API; cubra com um teste unitário do `PedidoService` usando um client que lança `IllegalStateException`.
5. **4xx do Pagamentos vira 503.** Erros como 400, 401 e 422 (bug de integração, credencial vencida) chegam ao cliente como "indisponível, tente depois" (PG-06). Já 429 e 408 provavelmente deveriam ser repetidos com espera. Vale alinhar com o time de Pagamentos.
6. **Preço 0 passa na validação.** A checagem é `precoCentavos < 0`, então um produto de preço 0 gera uma cobrança de `valorCentavos: 0`. Confirme se isso é válido para o Estoque e para o Pagamentos.
7. **SKU repetido no mesmo pedido** não é consolidado: vira duas consultas de preço e duas linhas na reserva. Defina a regra (somar ou rejeitar) e cubra com um teste.
8. **`tokenCartao` em pedido PIX** é repassado ao Pagamentos se vier no JSON. Provavelmente deveria ser descartado.
9. **Os stubs são aproximações do contrato.** Para os mocks não se afastarem das APIs reais, valide as respostas dos stubs contra `contratos/estoque-api.yaml` e `contratos/pagamentos-api.yaml` (por exemplo com o `swagger-request-validator`) ou faça contract tests (Pact, Spring Cloud Contract) com os outros times. Estes testes garantem que o seu serviço se comporta bem diante do que o mock devolve, não que o mock seja fiel à API real.

