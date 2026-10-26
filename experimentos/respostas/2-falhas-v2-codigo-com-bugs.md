> **Resposta da LLM, sem edição.** Experimento do [oráculo](../oraculo/README.md): prompt [2-falhas-comunicacao/v2.md](../../prompts/2-falhas-comunicacao/v2.md) com o **código com bugs** anexado | Modelo: Claude Sonnet 5.5 | Data: 26/10/2026

---

Escrevi 20 casos (F01 a F20; cinco deles parametrizados, 35 execuções no total) a partir de R1 a R12 e dos contratos, decidindo cada resultado esperado pelas regras e não pelo código. Pela leitura do código (não executei), F11, F13, F14, F15 e F18 devem falhar por causa de duas divergências reais, descritas na seção 3. Os demais devem passar.

## 1. Tabela de casos

Convenções: "mesma chave" significa `Idempotency-Key: pedido-{id do pedido}` em todas as chamadas a `POST /pagamentos`. "Motivo `estoque:`" significa que o motivo começa com `estoque:` (nunca comparo o texto inteiro). Todos os testes, exceto F01 (o exemplo do enunciado), consultam o pedido com `GET /pedidos/{id}` e conferem que status e motivo gravados são os mesmos devolvidos pelo POST (R12). A reserva é sempre `res-1` e o total é 2 x 8990 = 17980.

| ID | Regra(s) | Falha injetada (onde e como) | Resultado esperado | Interações verificadas |
|---|---|---|---|---|
| F01 | R6, R7 | `POST /pagamentos`: 503 na 1ª tentativa e 201 APROVADO na 2ª (cenário) | 201 `CONFIRMADO` | `POST /pagamentos` 2x, mesma chave; confirmação 1x; `DELETE` 0x |
| F02 | R10, R12 | `GET /produtos/LIV-001` lento (`withFixedDelay(TIMEOUT_MS * 2)`) | 503 `FALHA_INTEGRACAO`, motivo `estoque:` | `GET` >= 1x; `POST /reservas` 0x; `DELETE` 0x; `POST /pagamentos` 0x |
| F03 | R10, R12 | `GET /produtos/LIV-001` com `CONNECTION_RESET_BY_PEER` | idem F02 | idem F02 |
| F04 | R10, R12 | `GET /produtos/LIV-001` responde HTTP 500 | idem F02 | idem F02 |
| F05 | R10, R12 | `GET /produtos/LIV-001` responde 200 fora do contrato (4 variantes): `precoCentavos` ausente, -100, 89.9 e "89,90" | idem F02 | idem F02 |
| F06 | R10, R12 | `POST /reservas` lento | 503 `FALHA_INTEGRACAO`, motivo `estoque:` | `POST /reservas` 1x (não repete); confirmação 0x; `DELETE` 0x (não há id); `POST /pagamentos` 0x |
| F07 | R10, R12 | `POST /reservas` com `EMPTY_RESPONSE` | idem F06 | idem F06 |
| F08 | R10, R12 | `POST /reservas`: 503 na 1ª chamada e 201 na 2ª (cenário) | idem F06: a reserva não é repetida, mesmo que a 2ª chamada desse certo | idem F06 |
| F09 | R10 (interpretação), R12 | `POST /reservas` responde 422 `DADOS_INVALIDOS` | idem F06 (as regras não citam 4xx além de 404 e 409; assumi falha de integração) | idem F06 |
| F10 | R10, R12 | `POST /reservas` responde 201 sem `id` | idem F06 | idem F06 |
| F11 | R6, R9, R12 | `POST /pagamentos` lento nas 3 tentativas | 503 `FALHA_INTEGRACAO`, motivo `pagamentos:` (nunca `PAGAMENTO_RECUSADO`) | `POST /pagamentos` 3x, mesma chave; confirmação 0x; `DELETE /reservas/res-1` 1x |
| F12 | R6, R7, R12 | `POST /pagamentos`: reset na 1ª, resposta vazia na 2ª e 201 APROVADO na 3ª (cenário) | 201 `CONFIRMADO`, motivo nulo | `POST /pagamentos` 3x, mesma chave; confirmação 1x; `DELETE` 0x |
| F13 | R6, R9, R12 | `POST /pagamentos`: 500, 502 e 503 seguidos de 201 APROVADO que nunca deve ser alcançado (cenário) | 503 `FALHA_INTEGRACAO`, motivo `pagamentos:` | `POST /pagamentos` 3x (nunca 4), mesma chave; confirmação 0x; `DELETE` 1x |
| F14 | R6, R9, R12 | `POST /pagamentos` responde 4xx (3 variantes): 400, 409 e 422, com os corpos de erro do contrato | 503 `FALHA_INTEGRACAO`, motivo `pagamentos:` | `POST /pagamentos` 1x (4xx não é repetido); confirmação 0x; `DELETE` 1x |
| F15 | R9, R12 | `POST /pagamentos` responde 201 fora do contrato (4 variantes): sem `id`, sem `status`, `status` "PENDENTE" e `status` booleano | 503 `FALHA_INTEGRACAO`, motivo `pagamentos:` | `POST /pagamentos` 1x (resposta fora do contrato não está na lista de R6); confirmação 0x; `DELETE` 1x |
| F16 | R5, R6, R7, R12 | Resposta perdida: a 1ª resposta (APROVADO) demora mais que o timeout e a 2ª tentativa recebe 200 com o pagamento original | 201 `CONFIRMADO`, `pagamentoId` = `pag-1` | `POST /pagamentos` 2x, mesma chave e mesmo corpo (pedidoId, 17980, PIX, sem `tokenCartao`); confirmação 1x; `DELETE` 0x |
| F17 | R6, R8, R12 | Resposta perdida por `CONNECTION_RESET_BY_PEER`; a 2ª tentativa recebe 200 com o pagamento original RECUSADO | 402 `PAGAMENTO_RECUSADO`, motivo `LIMITE_PIX_EXCEDIDO` | `POST /pagamentos` 2x, mesma chave; `DELETE` 1x; confirmação 0x |
| F18 | R9, R11, R12 | `POST /pagamentos` com 503 nas 3 tentativas e `DELETE /reservas/res-1` lento | 503 `FALHA_INTEGRACAO` com motivo ainda `pagamentos:` (falha ao liberar não muda o status) | `POST /pagamentos` 3x; `DELETE` 1x; confirmação 0x |
| F19 | R7, R11, R12 | `POST /reservas/res-1/confirmacao` depois do pagamento aprovado (6 variantes): lenta, reset, vazia, 500, 404 e 409 | 201 `CONFIRMADO`, motivo nulo | `POST /pagamentos` 1x (sem nova cobrança); confirmação 1x (sem nova tentativa); `DELETE` 0x |
| F20 | R8, R11, R12 | `DELETE /reservas/res-1` depois de pagamento recusado (3 variantes): lento, 500 e 404 | 402 `PAGAMENTO_RECUSADO`, motivo `LIMITE_PIX_EXCEDIDO` (status inalterado) | `POST /pagamentos` 1x (recusa não é repetida); `DELETE` 1x; confirmação 0x |

### Combinações que ficaram de fora e por quê

- **`GET /produtos`**: `EMPTY_RESPONSE` repete o que o reset (F03) já exercita. O 4xx inesperado fica coberto pelo `POST /reservas` (F09), porque R10 não distingue 4xx de 5xx além do 404 (R3) e do 409 (R4). Falha transitória seguida de sucesso: as regras não dizem se uma consulta de preço pode ser repetida, então qualquer contagem esperada seria invenção minha. "Resposta perdida" não se aplica, porque o GET não cobra nada.
- **`POST /reservas`**: `CONNECTION_RESET` repete o `EMPTY_RESPONSE` (F07). Valor fora do enum em `status`: R10 só lista "reserva sem `id`" e não diz que um status inesperado deva derrubar o pedido, então registrei como lacuna na seção 3 em vez de escrever um teste. Resposta perdida: o serviço não recebe o id e não tem o que liberar nem repetir, que é exatamente o F06 (a reserva órfã expira sozinha em 15 minutos).
- **`POST /pagamentos`**: reset e resposta vazia nas 3 tentativas repetem o timeout do F11 (a mistura de faults está no F12). "Lento seguido de sucesso" é o F16, com 200. Conexão recusada (citada em R6) não dá para simular, porque os WireMock são estáticos e a infraestrutura não permite derrubá-los. Tipo errado com número no lugar de string em `id`: o Jackson converte número em texto sem reclamar, então o client não tem como distinguir e o resultado depende da configuração do Jackson, não de uma regra.
- **`POST /reservas/{id}/confirmacao`**: transitório seguido de sucesso e resposta perdida, porque R11 manda só registrar no log e não exige repetir a confirmação (embora ela seja idempotente no contrato). 2xx fora do contrato: o corpo da confirmação não é usado, só o status importa.
- **`DELETE /reservas/{id}`**: reset e resposta vazia repetem o caminho de "lenta" e "500" do F20. Transitório seguido de sucesso e resposta perdida: R11 não exige repetir a liberação. 2xx fora do contrato não existe (o 204 não tem corpo). O 409 "já confirmada" não acontece nos fluxos em que o serviço libera.
- **Método CARTAO**: a falha de comunicação não depende do método, então usei PIX em todos os testes. O descarte do token em PIX (R5) é conferido no corpo da cobrança do F16.

## 2. Classe `FalhasComunicacaoIT`

Pacote `com.estudo.checkout.falhas`, em `src/test/java/com/estudo/checkout/falhas/FalhasComunicacaoIT.java` (ou em `experimentos/java/...` rodando com `-Pexperimentos`).

```java
package com.estudo.checkout.falhas;

import com.estudo.checkout.suporte.IntegracaoBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;

import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class FalhasComunicacaoIT extends IntegracaoBase {

    private static final String PEDIDO_PIX = """
            {"clienteId":"cli-1","itens":[{"sku":"LIV-001","quantidade":2}],"metodoPagamento":"PIX"}""";

    // Respostas válidas dos provedores, tiradas dos exemplos e dos campos obrigatórios dos contratos.
    // Total do pedido: 2 x 8990 = 17980 centavos.
    private static final String PRODUTO = """
            {"sku":"LIV-001","nome":"Livro Testes de Software","precoCentavos":8990,"disponivel":10}""";
    private static final String RESERVA_RESERVADA = """
            {"id":"res-1","pedidoId":"p-1","status":"RESERVADA","itens":[{"sku":"LIV-001","quantidade":2}]}""";
    private static final String RESERVA_CONFIRMADA = """
            {"id":"res-1","pedidoId":"p-1","status":"CONFIRMADA","itens":[{"sku":"LIV-001","quantidade":2}]}""";
    private static final String PAGAMENTO_APROVADO = """
            {"id":"pag-1","pedidoId":"p-1","valorCentavos":17980,"metodo":"PIX","status":"APROVADO","motivo":null}""";
    private static final String PAGAMENTO_RECUSADO = """
            {"id":"pag-2","pedidoId":"p-1","valorCentavos":17980,"metodo":"PIX","status":"RECUSADO","motivo":"LIMITE_PIX_EXCEDIDO"}""";
    private static final String PAGAMENTOS_INDISPONIVEL = """
            {"codigo":"INDISPONIVEL","mensagem":"Serviço temporariamente indisponível"}""";
    private static final String ERRO_DADOS_INVALIDOS = """
            {"codigo":"DADOS_INVALIDOS","mensagem":"Requisição inválida"}""";
    private static final String ERRO_RESERVA_INEXISTENTE = """
            {"codigo":"RESERVA_INEXISTENTE","mensagem":"Reserva res-1 inexistente ou expirada"}""";
    private static final String ERRO_RESERVA_JA_LIBERADA = """
            {"codigo":"RESERVA_JA_LIBERADA","mensagem":"A reserva res-1 já foi liberada"}""";

    // ================================================================================================
    // Pagamentos: exemplo do enunciado
    // ================================================================================================

    @Test
    @DisplayName("F01 [R6, R7] Pagamentos responde 503 e depois aprova: repete com a mesma Idempotency-Key e confirma")
    void pagamentoInstavelQueSeRecupera() {
        // Arrange: Estoque saudável e Pagamentos com 503 na 1ª tentativa e aprovação na 2ª
        ESTOQUE.stubFor(get("/produtos/LIV-001").willReturn(okJson("""
                {"sku":"LIV-001","nome":"Livro Testes de Software","precoCentavos":8990,"disponivel":10}""")));
        ESTOQUE.stubFor(post("/reservas").willReturn(jsonResponse("""
                {"id":"res-1","pedidoId":"p-1","status":"RESERVADA","itens":[{"sku":"LIV-001","quantidade":2}]}""", 201)));
        ESTOQUE.stubFor(post("/reservas/res-1/confirmacao").willReturn(okJson("""
                {"id":"res-1","pedidoId":"p-1","status":"CONFIRMADA","itens":[{"sku":"LIV-001","quantidade":2}]}""")));
        PAGAMENTOS.stubFor(post("/pagamentos").inScenario("instavel").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(jsonResponse("""
                        {"codigo":"INDISPONIVEL","mensagem":"Serviço temporariamente indisponível"}""", 503))
                .willSetStateTo("recuperado"));
        PAGAMENTOS.stubFor(post("/pagamentos").inScenario("instavel").whenScenarioStateIs("recuperado")
                .willReturn(jsonResponse("""
                        {"id":"pag-1","pedidoId":"p-1","valorCentavos":17980,"metodo":"PIX","status":"APROVADO","motivo":null}""", 201)));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        // Assert: resultado para o cliente e o que foi (e o que não foi) chamado nos serviços externos
        assertThat(resposta.getStatusCode().value()).isEqualTo(201);
        assertThat(resposta.getBody().get("status").asText()).isEqualTo("CONFIRMADO");
        String chave = "pedido-" + resposta.getBody().get("id").asText();
        PAGAMENTOS.verify(2, postRequestedFor(urlEqualTo("/pagamentos")).withHeader("Idempotency-Key", equalTo(chave)));
        ESTOQUE.verify(1, postRequestedFor(urlEqualTo("/reservas/res-1/confirmacao")));
        ESTOQUE.verify(0, deleteRequestedFor(urlEqualTo("/reservas/res-1")));
    }

    // ================================================================================================
    // Estoque: GET /produtos/{sku} (consulta de preço)
    // R10 não limita quantas consultas de preço o serviço faz, e o HttpClient do JDK pode repetir um GET sozinho depois
    // de um reset em conexão reaproveitada. Por isso a contagem de GET é ">= 1". O que as regras exigem é que nada
    // seja reservado nem cobrado e que o motivo comece com "estoque:".
    // ================================================================================================

    @Test
    @DisplayName("F02 [R10, R12] Estoque lento na consulta de preço: 503 estoque:, sem reservar nem cobrar")
    void consultaDePrecoLenta() {
        estoqueSaudavel();
        ESTOQUE.stubFor(get("/produtos/LIV-001").willReturn(okJson(PRODUTO).withFixedDelay(TIMEOUT_MS * 2)));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertFalhaIntegracao(resposta, "estoque");
        ESTOQUE.verify(moreThanOrExactly(1), getRequestedFor(urlEqualTo("/produtos/LIV-001")));
        ESTOQUE.verify(0, postRequestedFor(urlEqualTo("/reservas")));
        assertSemCobrancaESemLiberacao();
    }

    @Test
    @DisplayName("F03 [R10, R12] Estoque derruba a conexão na consulta de preço (reset): 503 estoque:, sem reservar nem cobrar")
    void consultaDePrecoComConexaoResetada() {
        estoqueSaudavel();
        ESTOQUE.stubFor(get("/produtos/LIV-001").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertFalhaIntegracao(resposta, "estoque");
        ESTOQUE.verify(moreThanOrExactly(1), getRequestedFor(urlEqualTo("/produtos/LIV-001")));
        ESTOQUE.verify(0, postRequestedFor(urlEqualTo("/reservas")));
        assertSemCobrancaESemLiberacao();
    }

    @Test
    @DisplayName("F04 [R10, R12] Estoque responde 500 na consulta de preço: 503 estoque:, sem reservar nem cobrar")
    void consultaDePrecoComErro500() {
        estoqueSaudavel();
        ESTOQUE.stubFor(get("/produtos/LIV-001").willReturn(serverError()));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertFalhaIntegracao(resposta, "estoque");
        ESTOQUE.verify(moreThanOrExactly(1), getRequestedFor(urlEqualTo("/produtos/LIV-001")));
        ESTOQUE.verify(0, postRequestedFor(urlEqualTo("/reservas")));
        assertSemCobrancaESemLiberacao();
    }

    static Stream<Arguments> produtosForaDoContrato() {
        return Stream.of(
                Arguments.of("precoCentavos ausente", """
                        {"sku":"LIV-001","nome":"Livro Testes de Software","disponivel":10}"""),
                Arguments.of("precoCentavos negativo", """
                        {"sku":"LIV-001","nome":"Livro Testes de Software","precoCentavos":-100,"disponivel":10}"""),
                Arguments.of("precoCentavos com casa decimal", """
                        {"sku":"LIV-001","nome":"Livro Testes de Software","precoCentavos":89.9,"disponivel":10}"""),
                Arguments.of("precoCentavos com tipo errado (texto)", """
                        {"sku":"LIV-001","nome":"Livro Testes de Software","precoCentavos":"89,90","disponivel":10}"""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("produtosForaDoContrato")
    @DisplayName("F05 [R10, R12] Estoque responde 200 fora do contrato na consulta de preço: 503 estoque:, sem reservar nem cobrar")
    void consultaDePrecoForaDoContrato(String variante, String corpoDoProduto) {
        estoqueSaudavel();
        ESTOQUE.stubFor(get("/produtos/LIV-001").willReturn(okJson(corpoDoProduto)));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertFalhaIntegracao(resposta, "estoque");
        ESTOQUE.verify(moreThanOrExactly(1), getRequestedFor(urlEqualTo("/produtos/LIV-001")));
        ESTOQUE.verify(0, postRequestedFor(urlEqualTo("/reservas")));
        assertSemCobrancaESemLiberacao();
    }

    // ================================================================================================
    // Estoque: POST /reservas
    // R10: a reserva não é idempotente, então nunca é repetida automaticamente (1 chamada, seja qual for a falha).
    // Sem o id da reserva não há o que liberar: nenhum DELETE.
    // ================================================================================================

    @Test
    @DisplayName("F06 [R10, R12] Estoque lento na reserva: 503 estoque:, reserva não repetida e nenhuma cobrança")
    void reservaLenta() {
        estoqueSaudavel();
        ESTOQUE.stubFor(post("/reservas").willReturn(jsonResponse(RESERVA_RESERVADA, 201).withFixedDelay(TIMEOUT_MS * 2)));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertFalhaIntegracao(resposta, "estoque");
        ESTOQUE.verify(1, postRequestedFor(urlEqualTo("/reservas")));
        ESTOQUE.verify(0, postRequestedFor(urlEqualTo("/reservas/res-1/confirmacao")));
        assertSemCobrancaESemLiberacao();
    }

    @Test
    @DisplayName("F07 [R10, R12] Estoque fecha a conexão sem responder na reserva: 503 estoque:, reserva não repetida e nenhuma cobrança")
    void reservaComRespostaVazia() {
        estoqueSaudavel();
        ESTOQUE.stubFor(post("/reservas").willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertFalhaIntegracao(resposta, "estoque");
        ESTOQUE.verify(1, postRequestedFor(urlEqualTo("/reservas")));
        ESTOQUE.verify(0, postRequestedFor(urlEqualTo("/reservas/res-1/confirmacao")));
        assertSemCobrancaESemLiberacao();
    }

    @Test
    @DisplayName("F08 [R10, R12] Estoque responde 503 na reserva e daria certo na 2ª chamada: não repete, 503 estoque:, nenhuma cobrança")
    void reservaComFalhaTransitoriaNaoERepetida() {
        estoqueSaudavel();
        ESTOQUE.stubFor(post("/reservas").inScenario("reserva-instavel").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(serviceUnavailable())
                .willSetStateTo("recuperado"));
        ESTOQUE.stubFor(post("/reservas").inScenario("reserva-instavel").whenScenarioStateIs("recuperado")
                .willReturn(jsonResponse(RESERVA_RESERVADA, 201)));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertFalhaIntegracao(resposta, "estoque");
        ESTOQUE.verify(1, postRequestedFor(urlEqualTo("/reservas")));
        ESTOQUE.verify(0, postRequestedFor(urlEqualTo("/reservas/res-1/confirmacao")));
        assertSemCobrancaESemLiberacao();
    }

    @Test
    @DisplayName("F09 [R10, R12] Estoque responde 422 na reserva (4xx fora do contrato do fluxo): 503 estoque:, nenhuma cobrança")
    void reservaRejeitadaCom422() {
        estoqueSaudavel();
        ESTOQUE.stubFor(post("/reservas").willReturn(jsonResponse(ERRO_DADOS_INVALIDOS, 422)));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertFalhaIntegracao(resposta, "estoque");
        ESTOQUE.verify(1, postRequestedFor(urlEqualTo("/reservas")));
        ESTOQUE.verify(0, postRequestedFor(urlEqualTo("/reservas/res-1/confirmacao")));
        assertSemCobrancaESemLiberacao();
    }

    @Test
    @DisplayName("F10 [R10, R12] Estoque responde 201 sem id na reserva: 503 estoque:, nenhuma cobrança e nada a liberar")
    void reservaSemIdNaResposta() {
        estoqueSaudavel();
        ESTOQUE.stubFor(post("/reservas").willReturn(jsonResponse("""
                {"pedidoId":"p-1","status":"RESERVADA","itens":[{"sku":"LIV-001","quantidade":2}]}""", 201)));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertFalhaIntegracao(resposta, "estoque");
        ESTOQUE.verify(1, postRequestedFor(urlEqualTo("/reservas")));
        ESTOQUE.verify(0, postRequestedFor(urlEqualTo("/reservas/res-1/confirmacao")));
        assertSemCobrancaESemLiberacao();
    }

    // ================================================================================================
    // Pagamentos: POST /pagamentos
    // R6: até 3 tentativas no total, todas com a mesma Idempotency-Key, só para timeout, conexão resetada ou 5xx.
    // R9: esgotadas as tentativas, 4xx ou resposta fora do contrato => DELETE /reservas/{id} e 503 pagamentos:.
    // ================================================================================================

    @Test
    @DisplayName("F11 [R6, R9, R12] Pagamentos lento nas 3 tentativas: 3 chamadas com a mesma chave, reserva liberada e 503 pagamentos:")
    void pagamentoLentoNasTresTentativas() {
        estoqueSaudavel();
        PAGAMENTOS.stubFor(post("/pagamentos").willReturn(
                jsonResponse(PAGAMENTO_APROVADO, 201).withFixedDelay(TIMEOUT_MS * 2)));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertFalhaIntegracao(resposta, "pagamentos");
        assertCobrancas(3, resposta);
        ESTOQUE.verify(0, postRequestedFor(urlEqualTo("/reservas/res-1/confirmacao")));
        ESTOQUE.verify(1, deleteRequestedFor(urlEqualTo("/reservas/res-1")));
    }

    @Test
    @DisplayName("F12 [R6, R7, R12] Pagamentos reseta a conexão, responde vazio e aprova na 3ª tentativa: 3 chamadas com a mesma chave e confirma")
    void pagamentoSeRecuperaNaTerceiraTentativa() {
        estoqueSaudavel();
        PAGAMENTOS.stubFor(post("/pagamentos").inScenario("reset-vazio-aprovado").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("depois-do-reset"));
        PAGAMENTOS.stubFor(post("/pagamentos").inScenario("reset-vazio-aprovado").whenScenarioStateIs("depois-do-reset")
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE))
                .willSetStateTo("depois-do-vazio"));
        PAGAMENTOS.stubFor(post("/pagamentos").inScenario("reset-vazio-aprovado").whenScenarioStateIs("depois-do-vazio")
                .willReturn(jsonResponse(PAGAMENTO_APROVADO, 201)));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertConfirmado(resposta);
        assertCobrancas(3, resposta);
        ESTOQUE.verify(1, postRequestedFor(urlEqualTo("/reservas/res-1/confirmacao")));
        ESTOQUE.verify(0, deleteRequestedFor(urlEqualTo("/reservas/res-1")));
    }

    @Test
    @DisplayName("F13 [R6, R9, R12] Pagamentos responde 5xx 3 vezes (a 4ª aprovaria): para na 3ª tentativa, libera a reserva e devolve 503 pagamentos:")
    void pagamentoCom5xxParaNaTerceiraTentativa() {
        estoqueSaudavel();
        PAGAMENTOS.stubFor(post("/pagamentos").inScenario("cinco-xx-seguidos").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(serverError())
                .willSetStateTo("segunda"));
        PAGAMENTOS.stubFor(post("/pagamentos").inScenario("cinco-xx-seguidos").whenScenarioStateIs("segunda")
                .willReturn(aResponse().withStatus(502))
                .willSetStateTo("terceira"));
        PAGAMENTOS.stubFor(post("/pagamentos").inScenario("cinco-xx-seguidos").whenScenarioStateIs("terceira")
                .willReturn(jsonResponse(PAGAMENTOS_INDISPONIVEL, 503))
                .willSetStateTo("quarta"));
        PAGAMENTOS.stubFor(post("/pagamentos").inScenario("cinco-xx-seguidos").whenScenarioStateIs("quarta")
                .willReturn(jsonResponse(PAGAMENTO_APROVADO, 201)));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertFalhaIntegracao(resposta, "pagamentos");
        assertCobrancas(3, resposta); // "até 3 tentativas no total": a 4ª, que aprovaria, não pode existir
        ESTOQUE.verify(0, postRequestedFor(urlEqualTo("/reservas/res-1/confirmacao")));
        ESTOQUE.verify(1, deleteRequestedFor(urlEqualTo("/reservas/res-1")));
    }

    static Stream<Arguments> errosDe4xxDoPagamentos() {
        return Stream.of(
                Arguments.of(400, """
                        {"codigo":"IDEMPOTENCY_KEY_INVALIDA","mensagem":"O header Idempotency-Key é obrigatório"}"""),
                Arguments.of(409, """
                        {"codigo":"CHAVE_IDEMPOTENCIA_REUTILIZADA","mensagem":"A chave já foi usada com dados diferentes"}"""),
                Arguments.of(422, ERRO_DADOS_INVALIDOS));
    }

    @ParameterizedTest(name = "HTTP {0}")
    @MethodSource("errosDe4xxDoPagamentos")
    @DisplayName("F14 [R6, R9, R12] Pagamentos responde 4xx: não repete (1 chamada), libera a reserva e devolve 503 pagamentos:")
    void pagamentoCom4xxNaoERepetido(int statusHttp, String corpoDoErro) {
        estoqueSaudavel();
        PAGAMENTOS.stubFor(post("/pagamentos").willReturn(jsonResponse(corpoDoErro, statusHttp)));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertFalhaIntegracao(resposta, "pagamentos");
        assertCobrancas(1, resposta);
        ESTOQUE.verify(0, postRequestedFor(urlEqualTo("/reservas/res-1/confirmacao")));
        ESTOQUE.verify(1, deleteRequestedFor(urlEqualTo("/reservas/res-1")));
    }

    static Stream<Arguments> pagamentosForaDoContrato() {
        return Stream.of(
                Arguments.of("sem id", """
                        {"pedidoId":"p-1","valorCentavos":17980,"metodo":"PIX","status":"APROVADO","motivo":null}"""),
                Arguments.of("sem status", """
                        {"id":"pag-1","pedidoId":"p-1","valorCentavos":17980,"metodo":"PIX","motivo":null}"""),
                Arguments.of("status fora do enum", """
                        {"id":"pag-1","pedidoId":"p-1","valorCentavos":17980,"metodo":"PIX","status":"PENDENTE","motivo":null}"""),
                Arguments.of("status com tipo errado", """
                        {"id":"pag-1","pedidoId":"p-1","valorCentavos":17980,"metodo":"PIX","status":true,"motivo":null}"""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pagamentosForaDoContrato")
    @DisplayName("F15 [R9, R12] Pagamentos responde 201 fora do contrato: não repete, libera a reserva e devolve 503 pagamentos:")
    void pagamentoForaDoContrato(String variante, String corpoDoPagamento) {
        estoqueSaudavel();
        PAGAMENTOS.stubFor(post("/pagamentos").willReturn(jsonResponse(corpoDoPagamento, 201)));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertFalhaIntegracao(resposta, "pagamentos");
        assertCobrancas(1, resposta);
        ESTOQUE.verify(0, postRequestedFor(urlEqualTo("/reservas/res-1/confirmacao")));
        ESTOQUE.verify(1, deleteRequestedFor(urlEqualTo("/reservas/res-1")));
    }

    @Test
    @DisplayName("F16 [R5, R6, R7, R12] Resposta perdida: a repetição recebe 200 com o pagamento original aprovado, e o pedido confirma sem cobrar de novo")
    void respostaPerdidaERepeticaoIdempotenteAprovada() {
        estoqueSaudavel();
        // 1ª tentativa: o Pagamentos processa, mas a resposta chega depois do timeout do client
        PAGAMENTOS.stubFor(post("/pagamentos").inScenario("resposta-perdida").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(jsonResponse(PAGAMENTO_APROVADO, 201).withFixedDelay(TIMEOUT_MS * 2))
                .willSetStateTo("processado"));
        // 2ª tentativa: repetição idempotente do contrato (mesma chave e mesmo corpo): 200 com o pagamento original
        PAGAMENTOS.stubFor(post("/pagamentos").inScenario("resposta-perdida").whenScenarioStateIs("processado")
                .willReturn(jsonResponse(PAGAMENTO_APROVADO, 200)));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertConfirmado(resposta);
        assertThat(resposta.getBody().get("pagamentoId").asText()).isEqualTo("pag-1");
        assertCobrancas(2, resposta);
        // Mesma chave e MESMO corpo (senão o contrato responderia 409): valor = total e PIX sem tokenCartao (R5)
        String pedidoId = resposta.getBody().get("id").asText();
        PAGAMENTOS.verify(2, postRequestedFor(urlEqualTo("/pagamentos")).withRequestBody(equalToJson(
                "{\"pedidoId\":\"" + pedidoId + "\",\"valorCentavos\":17980,\"metodo\":\"PIX\"}")));
        ESTOQUE.verify(1, postRequestedFor(urlEqualTo("/reservas/res-1/confirmacao")));
        ESTOQUE.verify(0, deleteRequestedFor(urlEqualTo("/reservas/res-1")));
    }

    @Test
    @DisplayName("F17 [R6, R8, R12] Resposta perdida (reset): a repetição recebe 200 com o pagamento original recusado: 402, libera a reserva e não confirma")
    void respostaPerdidaERepeticaoIdempotenteRecusada() {
        estoqueSaudavel();
        PAGAMENTOS.stubFor(post("/pagamentos").inScenario("perdida-recusada").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("processado"));
        PAGAMENTOS.stubFor(post("/pagamentos").inScenario("perdida-recusada").whenScenarioStateIs("processado")
                .willReturn(jsonResponse(PAGAMENTO_RECUSADO, 200)));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertRecusado(resposta);
        assertCobrancas(2, resposta);
        ESTOQUE.verify(1, deleteRequestedFor(urlEqualTo("/reservas/res-1")));
        ESTOQUE.verify(0, postRequestedFor(urlEqualTo("/reservas/res-1/confirmacao")));
    }

    @Test
    @DisplayName("F18 [R9, R11, R12] Pagamentos indisponível e a liberação da reserva também falha: o status continua 503 pagamentos:")
    void pagamentoIndisponivelEFalhaAoLiberar() {
        estoqueSaudavel();
        ESTOQUE.stubFor(delete("/reservas/res-1").willReturn(noContent().withFixedDelay(TIMEOUT_MS * 2)));
        PAGAMENTOS.stubFor(post("/pagamentos").willReturn(jsonResponse(PAGAMENTOS_INDISPONIVEL, 503)));

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertFalhaIntegracao(resposta, "pagamentos"); // a falha ao liberar não troca o motivo para "estoque:"
        assertCobrancas(3, resposta);
        ESTOQUE.verify(1, deleteRequestedFor(urlEqualTo("/reservas/res-1")));
        ESTOQUE.verify(0, postRequestedFor(urlEqualTo("/reservas/res-1/confirmacao")));
    }

    // ================================================================================================
    // Estoque depois da cobrança: confirmação e liberação (R11)
    // ================================================================================================

    static Stream<Arguments> falhasNaConfirmacao() {
        return Stream.of(
                Arguments.of("lenta (mais que o timeout)", okJson(RESERVA_CONFIRMADA).withFixedDelay(TIMEOUT_MS * 2)),
                Arguments.of("conexão resetada", aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
                Arguments.of("resposta vazia", aResponse().withFault(Fault.EMPTY_RESPONSE)),
                Arguments.of("HTTP 500", serverError()),
                Arguments.of("HTTP 404 (reserva expirada)", jsonResponse(ERRO_RESERVA_INEXISTENTE, 404)),
                Arguments.of("HTTP 409 (reserva já liberada)", jsonResponse(ERRO_RESERVA_JA_LIBERADA, 409)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("falhasNaConfirmacao")
    @DisplayName("F19 [R7, R11, R12] Falha ao confirmar a reserva depois do pagamento aprovado: continua 201 CONFIRMADO, sem nova cobrança nem liberação")
    void falhaNaConfirmacaoNaoDesfazOPedido(String falha, ResponseDefinitionBuilder respostaDaConfirmacao) {
        estoqueSaudavel();
        ESTOQUE.stubFor(post("/reservas/res-1/confirmacao").willReturn(respostaDaConfirmacao));
        pagamentosAprova();

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertConfirmado(resposta);
        assertCobrancas(1, resposta);
        ESTOQUE.verify(1, postRequestedFor(urlEqualTo("/reservas/res-1/confirmacao")));
        ESTOQUE.verify(0, deleteRequestedFor(urlEqualTo("/reservas/res-1")));
    }

    static Stream<Arguments> falhasNaLiberacao() {
        return Stream.of(
                Arguments.of("lenta (mais que o timeout)", noContent().withFixedDelay(TIMEOUT_MS * 2)),
                Arguments.of("HTTP 500", serverError()),
                Arguments.of("HTTP 404 (reserva expirada)", jsonResponse(ERRO_RESERVA_INEXISTENTE, 404)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("falhasNaLiberacao")
    @DisplayName("F20 [R8, R11, R12] Pagamento recusado e a liberação da reserva falha: o pedido continua PAGAMENTO_RECUSADO (402) com o motivo do Pagamentos")
    void falhaNaLiberacaoNaoMudaORecusado(String falha, ResponseDefinitionBuilder respostaDaLiberacao) {
        estoqueSaudavel();
        ESTOQUE.stubFor(delete("/reservas/res-1").willReturn(respostaDaLiberacao));
        pagamentosRecusa();

        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        assertRecusado(resposta);
        assertCobrancas(1, resposta);
        ESTOQUE.verify(1, deleteRequestedFor(urlEqualTo("/reservas/res-1")));
        ESTOQUE.verify(0, postRequestedFor(urlEqualTo("/reservas/res-1/confirmacao")));
    }

    // ================================================================================================
    // Stubs saudáveis. Cada teste sobrescreve só o que quer quebrar (no WireMock o stub mais recente vence).
    // ================================================================================================

    private void estoqueSaudavel() {
        ESTOQUE.stubFor(get("/produtos/LIV-001").willReturn(okJson(PRODUTO)));
        ESTOQUE.stubFor(post("/reservas").willReturn(jsonResponse(RESERVA_RESERVADA, 201)));
        ESTOQUE.stubFor(post("/reservas/res-1/confirmacao").willReturn(okJson(RESERVA_CONFIRMADA)));
        ESTOQUE.stubFor(delete("/reservas/res-1").willReturn(noContent()));
    }

    private void pagamentosAprova() {
        PAGAMENTOS.stubFor(post("/pagamentos").willReturn(jsonResponse(PAGAMENTO_APROVADO, 201)));
    }

    private void pagamentosRecusa() {
        PAGAMENTOS.stubFor(post("/pagamentos").willReturn(jsonResponse(PAGAMENTO_RECUSADO, 201)));
    }

    // ================================================================================================
    // Asserções compartilhadas
    // ================================================================================================

    private static String statusDe(ResponseEntity<JsonNode> resposta) {
        return resposta.getBody().get("status").asText();
    }

    private static String motivoDe(ResponseEntity<JsonNode> resposta) {
        JsonNode motivo = resposta.getBody().get("motivo");
        return motivo == null || motivo.isNull() ? null : motivo.asText();
    }

    private static String chaveDe(ResponseEntity<JsonNode> resposta) {
        return "pedido-" + resposta.getBody().get("id").asText();
    }

    /** R9, R10 e R12: 503 FALHA_INTEGRACAO, motivo começando pelo serviço (só o prefixo) e pedido gravado igual. */
    private void assertFalhaIntegracao(ResponseEntity<JsonNode> resposta, String servico) {
        assertThat(resposta.getStatusCode().value()).isEqualTo(503);
        assertThat(statusDe(resposta)).isEqualTo("FALHA_INTEGRACAO");
        assertThat(motivoDe(resposta)).startsWith(servico + ":");
        assertPedidoGravado(resposta);
    }

    /** R7 e R12: 201 CONFIRMADO, sem motivo. */
    private void assertConfirmado(ResponseEntity<JsonNode> resposta) {
        assertThat(resposta.getStatusCode().value()).isEqualTo(201);
        assertThat(statusDe(resposta)).isEqualTo("CONFIRMADO");
        assertThat(motivoDe(resposta)).isNull();
        assertPedidoGravado(resposta);
    }

    /** R8 e R12: 402 PAGAMENTO_RECUSADO com o motivo informado pelo Pagamentos (o da constante PAGAMENTO_RECUSADO). */
    private void assertRecusado(ResponseEntity<JsonNode> resposta) {
        assertThat(resposta.getStatusCode().value()).isEqualTo(402);
        assertThat(statusDe(resposta)).isEqualTo("PAGAMENTO_RECUSADO");
        assertThat(motivoDe(resposta)).isEqualTo("LIMITE_PIX_EXCEDIDO");
        assertPedidoGravado(resposta);
    }

    /** R12: o pedido consultado tem o mesmo status e o mesmo motivo devolvidos pelo POST. */
    private void assertPedidoGravado(ResponseEntity<JsonNode> resposta) {
        ResponseEntity<JsonNode> consulta = consultarPedido(resposta.getBody().get("id").asText());
        assertThat(consulta.getStatusCode().value()).isEqualTo(200);
        assertThat(statusDe(consulta)).isEqualTo(statusDe(resposta));
        assertThat(motivoDe(consulta)).isEqualTo(motivoDe(resposta));
    }

    /** R6: todas as tentativas de cobrança usam a mesma Idempotency-Key (N com a chave e N no total). */
    private void assertCobrancas(int tentativas, ResponseEntity<JsonNode> resposta) {
        PAGAMENTOS.verify(tentativas, postRequestedFor(urlEqualTo("/pagamentos"))
                .withHeader("Idempotency-Key", equalTo(chaveDe(resposta))));
        PAGAMENTOS.verify(tentativas, postRequestedFor(urlEqualTo("/pagamentos")));
    }

    /** Cobrança sem reserva é proibida, e sem id de reserva não há o que liberar. */
    private void assertSemCobrancaESemLiberacao() {
        ESTOQUE.verify(0, deleteRequestedFor(urlMatching("/reservas/.*")));
        PAGAMENTOS.verify(0, postRequestedFor(urlEqualTo("/pagamentos")));
    }
}
```

Observações sobre a classe:

- `estoqueSaudavel()` registra os quatro stubs do Estoque e cada teste sobrescreve só o que quer quebrar. Nos cenários, todos os estados têm stub, então a resposta nunca cai por engano no stub saudável.
- A contagem de `GET /produtos/{sku}` usa `moreThanOrExactly(1)`. R10 não limita as consultas de preço e o `HttpClient` do JDK pode repetir um GET sozinho depois de um reset em conexão reaproveitada. Para `POST` o JDK não repete, então as contagens de `POST /reservas`, `POST /pagamentos` e da confirmação são exatas. O `DELETE` também é contado com valor exato (F18 e F20).
- Cada tentativa lenta custa cerca de 400 ms (F11 leva cerca de 1,2 s), sem `Thread.sleep`.
- Os testes parametrizados usam `@ParameterizedTest` com `@MethodSource` (o `junit-jupiter-params` já vem no `spring-boot-starter-test`) e cada variante aparece com o próprio nome no relatório.
- Resultado esperado com o código atual, pela leitura (não executei): F11, F13, F14 (as 3 variantes), F15 (as 4) e F18 falham, e os demais passam.

## 3. Divergências entre o código e as regras

Escrevi os testes pela regra. Estas são as duas divergências que a leitura do código mostra, com os testes que as acusam.

### D1. R6 e R9: um 4xx do Pagamentos é repetido

- **Regra**: R6 diz que "HTTP 4xx não é repetido", e R9 manda tratar a resposta 4xx como falha imediata (liberar a reserva e devolver 503).
- **Código**: em `PagamentosClient.autorizar`, o `catch (HttpStatusCodeException | ResourceAccessException e)` pega também `HttpClientErrorException` (4xx), registra a falha e volta ao laço. O Javadoc do método confirma a intenção ("timeout, falha de conexão ou erro HTTP ... é repetida"). Um 400, 409 ou 422 gera 3 chamadas a `POST /pagamentos` em vez de 1 e termina em "pagamentos: indisponível após 3 tentativas", o que descreve errado o que aconteceu e atrasa a resposta ao cliente.
- **Teste que acusa**: F14, nas 3 variantes. `assertCobrancas(1, ...)` encontra 3 chamadas.
- **Correção sugerida**: repetir só `HttpServerErrorException | ResourceAccessException`. Os demais `RestClientException`, incluindo `HttpClientErrorException`, já caem no `catch (RestClientException e)` que lança `FalhaIntegracaoException` na hora.

### D2. R9: a reserva não é liberada quando o Pagamentos falha

- **Regra**: R9 (e a nota do diagrama de sequência) manda fazer `DELETE /reservas/{id}` e devolver 503 quando o Pagamentos está indisponível depois das tentativas, responde 4xx ou responde fora do contrato.
- **Código**: `PedidoService.criar` captura `FalhaIntegracaoException` e só chama `pedido.finalizar(FALHA_INTEGRACAO, ...)`. A compensação `desfazerReserva` só é chamada no ramo de pagamento RECUSADO, dentro de `cobrar`. Quando `autorizar` lança a exceção, ela sobe direto para o `catch` e a reserva fica no Estoque até expirar (15 minutos), segurando itens de um pedido que não será cobrado. O Javadoc de `StatusPedido.FALHA_INTEGRACAO` ("A reserva, se havia, foi desfeita") e o de `desfazerReserva` descrevem o comportamento correto, que o código não entrega.
- **Testes que acusam**: F11, F13, F14, F15 e F18 falham em `ESTOQUE.verify(1, deleteRequestedFor(urlEqualTo("/reservas/res-1")))`, com 0 chamadas. O F14 falha antes na contagem de cobranças (D1) e só mostra a D2 depois que a D1 for corrigida.
- **Correção sugerida**: chamar `desfazerReserva(pedido)` no `catch (FalhaIntegracaoException e)` de `criar`, antes de finalizar. Quando a falha vem do Estoque (consulta de preço ou reserva), `reservaId` é nulo e `desfazerReserva` já retorna sem fazer nada, então F02 a F10 continuam sem nenhum `DELETE`.

### Pontos de atenção (não são divergências confirmadas)

- R10 não diz o que fazer com um 4xx do Estoque diferente de 404 e 409. O F09 assume 503 `estoque:` por analogia, que é o que o código faz. Vale esclarecer na regra.
- R10 só cita a reserva sem `id`. O client ignora `status` e `itens` da resposta de `POST /reservas`, então uma reserva com `status` fora do enum (ou já `LIBERADA`) seria aceita. Se a intenção é rejeitar, faltam a regra e o código, e por isso não escrevi teste.
- Depois de 3 timeouts, R9 libera a reserva e responde 503, mas o Pagamentos pode ter aprovado a cobrança (foi a resposta que se perdeu). A Idempotency-Key permite repetir depois com segurança, mas nada no fluxo reconcilia esse pedido. É um risco de negócio da regra, não um defeito do código.
- A "conexão recusada" citada em R6 não foi simulada, porque os WireMock são estáticos e a infraestrutura não permite derrubá-los.

