> **Resposta da LLM, sem edição.** Prompt: [2-falhas-comunicacao/v2.md](../../prompts/2-falhas-comunicacao/v2.md) | Modelo: Claude Sonnet 5.5 | Data: 26/10/2026 | Código enviado: commit `3d4ab64`

---

Montei os casos a partir das regras R1 a R12 e dos dois contratos. O código só foi lido depois, para conferir se contradiz alguma regra (seção 3).

## 1. Tabela de casos

Em todos os casos o serviço é chamado por HTTP (`postarPedido`), o pedido gravado é conferido com `consultarPedido` quando há `id` e os stubs seguem os exemplos e os campos obrigatórios dos contratos.

| ID | Regra(s) | Falha injetada (onde e como) | Resultado esperado | Interações verificadas |
|----|----------|------------------------------|--------------------|------------------------|
| F01 | R6, R7 | POST /pagamentos: 503 na 1ª tentativa e 201 APROVADO na 2ª (cenário) | 201 `CONFIRMADO` | /pagamentos 2x com `Idempotency-Key: pedido-{id}`; confirmação 1x; DELETE 0 |
| F02 | R2, R10, R12 | GET /produtos/LIV-001 demora `TIMEOUT_MS * 2` | 503 `FALHA_INTEGRACAO`, motivo `estoque:`, pedido gravado igual | GET 1x; /reservas 0; /pagamentos 0; sem confirmação nem DELETE |
| F03 | R2, R10 | GET /produtos/LIV-001 com `CONNECTION_RESET_BY_PEER` | 503, motivo `estoque:` | GET 1 ou mais; /reservas 0; /pagamentos 0 |
| F04 | R2, R10 | Pedido com 2 itens: GET do 1º item ok, GET do 2º (MOU-003) responde 500 | 503, motivo `estoque:` | cada GET 1x; /reservas 0; /pagamentos 0 |
| F05 | R2, R10 | GET 200 fora do contrato, 4 variantes: sem `precoCentavos`, negativo, `89.9`, texto | 503, motivo `estoque:` | GET 1x; /reservas 0; /pagamentos 0 |
| F06 | R4, R10 | POST /reservas demora `TIMEOUT_MS * 2` | 503, motivo `estoque:` | /reservas 1x (não repete); DELETE 0 (não há id); /pagamentos 0 |
| F07 | R10 | POST /reservas com `EMPTY_RESPONSE` | 503, motivo `estoque:` | /reservas 1x; DELETE 0; /pagamentos 0 |
| F08 | R10 | POST /reservas: 503 na 1ª chamada e 201 na 2ª (cenário) | 503, motivo `estoque:` (a reserva não é repetida) | /reservas 1x; /pagamentos 0 |
| F09 | R4, R10 (4xx por analogia, ver seção 3) | POST /reservas responde 422 `DADOS_INVALIDOS` | 503, motivo `estoque:` | /reservas 1x; /pagamentos 0; DELETE 0 |
| F10 | R10 | POST /reservas responde 201 sem `id` | 503, motivo `estoque:` | /reservas 1x; /pagamentos 0; DELETE 0 |
| F11 | R6, R9 | POST /pagamentos responde 503 nas 3 tentativas | 503, motivo `pagamentos:` (nunca 402) | /pagamentos 3x, todas com a mesma chave; DELETE 1x; confirmação 0 |
| F12 | R6, R9 | POST /pagamentos demora `TIMEOUT_MS * 2` nas 3 tentativas | 503, motivo `pagamentos:` | /pagamentos 3x, mesma chave; DELETE 1x; confirmação 0 |
| F13 | R6, R7 | POST /pagamentos: `CONNECTION_RESET_BY_PEER`, depois `EMPTY_RESPONSE`, depois 201 APROVADO (sucesso na última tentativa) | 201 `CONFIRMADO` | /pagamentos 3x, mesma chave; confirmação 1x; DELETE 0 |
| F14 | R5, R6, R7 | Resposta perdida (CARTAO): a 1ª tentativa foi processada, mas a resposta demora mais que o timeout; a 2ª recebe 200 com o pagamento original | 201 `CONFIRMADO`, `pagamentoId` = pag-1 | /pagamentos 2x, mesma chave e corpo idêntico (com `tokenCartao`); confirmação 1x; DELETE 0 |
| F15 | R6, R9 | POST /pagamentos responde 422 | 503, motivo `pagamentos:` (4xx não é repetido) | /pagamentos 1x; DELETE 1x; confirmação 0 |
| F16 | R6, R9 | POST /pagamentos: 503 na 1ª tentativa e 409 `CHAVE_IDEMPOTENCIA_REUTILIZADA` na 2ª | 503, motivo `pagamentos:` (para no 4xx, sem 3ª tentativa) | /pagamentos 2x, mesma chave; DELETE 1x; confirmação 0 |
| F17 | R9 | POST /pagamentos responde 201 fora do contrato, 3 variantes: sem `id`, sem `status`, `status` = PENDENTE | 503, motivo `pagamentos:` | /pagamentos 1x (não repete); DELETE 1x; confirmação 0 |
| F18 | R7, R11 | POST /reservas/res-1/confirmacao demora `TIMEOUT_MS * 2` | 201 `CONFIRMADO`, sem motivo | /pagamentos 1x; confirmação 1x; DELETE 0 |
| F19 | R7, R11 | POST /reservas/res-1/confirmacao responde 404 `RESERVA_INEXISTENTE` | 201 `CONFIRMADO`, sem motivo | /pagamentos 1x; confirmação 1x; DELETE 0 |
| F20 | R8, R11 | Pagamento RECUSADO e DELETE /reservas/res-1 com falha, 3 variantes: lento, 500, 404 | 402 `PAGAMENTO_RECUSADO`, motivo `LIMITE_PIX_EXCEDIDO` (o do Pagamentos) | /pagamentos 1x; DELETE 1x; confirmação 0 |
| F21 | R9, R11, R12 | Pagamentos 503 nas 3 tentativas e DELETE /reservas/res-1 lento | 503, motivo `pagamentos:` (a falha da liberação não troca o motivo nem o status) | /pagamentos 3x, mesma chave; DELETE 1x; confirmação 0 |

**Convenções**

- As verificações de "o que não pode ter sido chamado" ficam em `verificarSemCobrancaConfirmacaoOuLiberacao()` (falhas do Estoque antes de existir reserva) e nos `verify(0, ...)` de cada teste (confirmação depois de falha, liberação depois de confirmação).
- Contagens exatas só onde a regra ou o comportamento do client as fixam: 3 tentativas no Pagamentos (R6), 1 reserva (R10) e 1 chamada para cada operação do Estoque, que não é repetida. A exceção é o GET com reset (F03): o `HttpClient` do JDK pode reenviar sozinho um GET quando uma conexão reaproveitada do pool cai, e nenhuma regra fixa esse número.
- Falhas de mensagem (`motivo`) de `FALHA_INTEGRACAO` são conferidas só pelo prefixo `estoque:` ou `pagamentos:`.

**Combinações que ficaram de fora**

- GET /produtos com 4xx diferente de 404, e com `EMPTY_RESPONSE`: caem nos mesmos ramos de F09 e F03.
- GET /produtos com falha transitória seguida de sucesso: R10 manda 503 em qualquer 5xx e não prevê repetição de GET. Um cenário "503 e depois 200" testaria uma decisão que a regra não tomou.
- POST /reservas com `CONNECTION_RESET_BY_PEER` (mesmo ramo de F07) e "resposta perdida" (o contrato do Estoque não tem repetição idempotente da reserva).
- POST /pagamentos com 400 ou 409 isolados (mesmo ramo de F15 e F16) e `EMPTY_RESPONSE` isolado (já em F13).
- Confirmação com 5xx, reset ou falha transitória seguida de sucesso: o resultado é 201 `CONFIRMADO` em qualquer caso (R11), então o teste não distinguiria nada além de F18 e F19. Confirmação com 2xx fora do contrato: o client ignora o corpo e R11 manda manter o 201.
- DELETE com reset ou `EMPTY_RESPONSE`, 409 `RESERVA_JA_CONFIRMADA`, falha transitória seguida de sucesso e 2xx fora do contrato: mesmo ramo de F20 e F21; R11 não prevê repetição da liberação (a reserva expira sozinha) e o 204 não tem corpo.
- Conexão recusada (R6): não dá para simular sem derrubar os WireMock estáticos, que são compartilhados com as outras classes.

## 2. Classe `FalhasComunicacaoIT`

```java
package com.estudo.checkout.falhas;

import com.estudo.checkout.suporte.IntegracaoBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class FalhasComunicacaoIT extends IntegracaoBase {

    private static final String PEDIDO_PIX = """
            {"clienteId":"cli-1","itens":[{"sku":"LIV-001","quantidade":2}],"metodoPagamento":"PIX"}""";
    private static final String PEDIDO_CARTAO = """
            {"clienteId":"cli-1","itens":[{"sku":"LIV-001","quantidade":2}],"metodoPagamento":"CARTAO","tokenCartao":"tok-123"}""";
    private static final String PEDIDO_DOIS_ITENS = """
            {"clienteId":"cli-1","itens":[{"sku":"LIV-001","quantidade":2},{"sku":"MOU-003","quantidade":1}],"metodoPagamento":"PIX"}""";

    // Respostas válidas dos provedores, com os exemplos e os campos obrigatórios dos contratos
    private static final String PRODUTO_LIVRO = """
            {"sku":"LIV-001","nome":"Livro Testes de Software","precoCentavos":8990,"disponivel":10}""";
    private static final String RESERVA_RESERVADA = """
            {"id":"res-1","pedidoId":"p-1","status":"RESERVADA","itens":[{"sku":"LIV-001","quantidade":2}]}""";
    private static final String RESERVA_CONFIRMADA = """
            {"id":"res-1","pedidoId":"p-1","status":"CONFIRMADA","itens":[{"sku":"LIV-001","quantidade":2}]}""";
    private static final String PAGAMENTO_APROVADO = """
            {"id":"pag-1","pedidoId":"p-1","valorCentavos":17980,"metodo":"PIX","status":"APROVADO","motivo":null}""";
    private static final String PAGAMENTO_APROVADO_CARTAO = """
            {"id":"pag-1","pedidoId":"p-1","valorCentavos":17980,"metodo":"CARTAO","status":"APROVADO","motivo":null}""";
    private static final String PAGAMENTO_RECUSADO = """
            {"id":"pag-2","pedidoId":"p-1","valorCentavos":17980,"metodo":"PIX","status":"RECUSADO","motivo":"LIMITE_PIX_EXCEDIDO"}""";
    private static final String ERRO_INDISPONIVEL = """
            {"codigo":"INDISPONIVEL","mensagem":"Serviço temporariamente indisponível"}""";
    private static final String ERRO_INTERNO = """
            {"codigo":"ERRO_INTERNO","mensagem":"Erro interno do servidor"}""";
    private static final String ERRO_RESERVA_INEXISTENTE = """
            {"codigo":"RESERVA_INEXISTENTE","mensagem":"Reserva res-1 inexistente ou expirada"}""";

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

    @Test
    @DisplayName("F02 [R2, R10, R12] Estoque mais lento que o timeout na consulta de preço: 503 com motivo estoque:, sem reserva nem cobrança")
    void consultaDePrecoMaisLentaQueOTimeout() {
        // Arrange
        ESTOQUE.stubFor(get("/produtos/LIV-001").willReturn(okJson(PRODUTO_LIVRO).withFixedDelay(TIMEOUT_MS * 2)));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        // Assert
        assertFalhaDeIntegracao(resposta, "estoque");
        ESTOQUE.verify(1, getRequestedFor(urlEqualTo("/produtos/LIV-001")));
        ESTOQUE.verify(0, reserva());
        verificarSemCobrancaConfirmacaoOuLiberacao();
    }

    @Test
    @DisplayName("F03 [R2, R10] Estoque derruba a conexão (reset) na consulta de preço: 503 com motivo estoque:, sem reserva nem cobrança")
    void consultaDePrecoComConexaoResetada() {
        // Arrange
        ESTOQUE.stubFor(get("/produtos/LIV-001").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        // Assert: nenhuma regra fixa quantos GETs saem (o HttpClient do JDK pode reenviar um GET sozinho)
        assertFalhaDeIntegracao(resposta, "estoque");
        ESTOQUE.verify(moreThanOrExactly(1), getRequestedFor(urlEqualTo("/produtos/LIV-001")));
        ESTOQUE.verify(0, reserva());
        verificarSemCobrancaConfirmacaoOuLiberacao();
    }

    @Test
    @DisplayName("F04 [R2, R10] Estoque responde 500 na consulta do 2º item: 503 com motivo estoque:, sem reserva nem cobrança")
    void erroDeServidorNaConsultaDoSegundoItem() {
        // Arrange: o 1º item tem preço, o 2º falha
        ESTOQUE.stubFor(get("/produtos/LIV-001").willReturn(okJson(PRODUTO_LIVRO)));
        ESTOQUE.stubFor(get("/produtos/MOU-003").willReturn(jsonResponse(ERRO_INTERNO, 500)));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_DOIS_ITENS);

        // Assert
        assertFalhaDeIntegracao(resposta, "estoque");
        ESTOQUE.verify(1, getRequestedFor(urlEqualTo("/produtos/LIV-001")));
        ESTOQUE.verify(1, getRequestedFor(urlEqualTo("/produtos/MOU-003")));
        ESTOQUE.verify(0, reserva());
        verificarSemCobrancaConfirmacaoOuLiberacao();
    }

    static Stream<Arguments> precosForaDoContrato() {
        return Stream.of(
                Arguments.of("sem precoCentavos", """
                        {"sku":"LIV-001","nome":"Livro Testes de Software","disponivel":10}"""),
                Arguments.of("precoCentavos negativo", """
                        {"sku":"LIV-001","nome":"Livro Testes de Software","precoCentavos":-100,"disponivel":10}"""),
                Arguments.of("precoCentavos com casa decimal", """
                        {"sku":"LIV-001","nome":"Livro Testes de Software","precoCentavos":89.9,"disponivel":10}"""),
                Arguments.of("precoCentavos como texto", """
                        {"sku":"LIV-001","nome":"Livro Testes de Software","precoCentavos":"oitenta e nove","disponivel":10}"""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("precosForaDoContrato")
    @DisplayName("F05 [R2, R10] Estoque responde 200 fora do contrato na consulta de preço: 503 com motivo estoque:, sem reserva nem cobrança")
    void precoForaDoContrato(String variante, String corpoDoProduto) {
        // Arrange
        ESTOQUE.stubFor(get("/produtos/LIV-001").willReturn(okJson(corpoDoProduto)));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        // Assert
        assertFalhaDeIntegracao(resposta, "estoque");
        ESTOQUE.verify(1, getRequestedFor(urlEqualTo("/produtos/LIV-001")));
        ESTOQUE.verify(0, reserva());
        verificarSemCobrancaConfirmacaoOuLiberacao();
    }

    @Test
    @DisplayName("F06 [R4, R10] Estoque mais lento que o timeout na reserva: 503 com motivo estoque:, reserva não repetida e nenhuma cobrança")
    void reservaMaisLentaQueOTimeout() {
        // Arrange
        stubPreco();
        ESTOQUE.stubFor(post("/reservas").willReturn(jsonResponse(RESERVA_RESERVADA, 201).withFixedDelay(TIMEOUT_MS * 2)));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        // Assert: sem id de reserva não há o que liberar, e a cobrança não pode acontecer sem reserva
        assertFalhaDeIntegracao(resposta, "estoque");
        ESTOQUE.verify(1, reserva());
        verificarSemCobrancaConfirmacaoOuLiberacao();
    }

    @Test
    @DisplayName("F07 [R10] Estoque devolve resposta vazia na reserva: 503 com motivo estoque:, reserva não repetida e nenhuma cobrança")
    void reservaComRespostaVazia() {
        // Arrange
        stubPreco();
        ESTOQUE.stubFor(post("/reservas").willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        // Assert
        assertFalhaDeIntegracao(resposta, "estoque");
        ESTOQUE.verify(1, reserva());
        verificarSemCobrancaConfirmacaoOuLiberacao();
    }

    @Test
    @DisplayName("F08 [R10] Estoque responde 503 na reserva e se recuperaria na 2ª chamada: a reserva não é repetida, 503 com motivo estoque:")
    void reservaInstavelNaoERepetida() {
        // Arrange: a 2ª chamada teria sucesso, mas a reserva não é idempotente e não pode ser repetida
        stubPreco();
        ESTOQUE.stubFor(post("/reservas").inScenario("reserva-instavel").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(jsonResponse(ERRO_INTERNO, 503))
                .willSetStateTo("recuperado"));
        ESTOQUE.stubFor(post("/reservas").inScenario("reserva-instavel").whenScenarioStateIs("recuperado")
                .willReturn(jsonResponse(RESERVA_RESERVADA, 201)));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        // Assert
        assertFalhaDeIntegracao(resposta, "estoque");
        ESTOQUE.verify(1, reserva());
        verificarSemCobrancaConfirmacaoOuLiberacao();
    }

    @Test
    @DisplayName("F09 [R4, R10] Estoque responde 422 inesperado na reserva: 503 com motivo estoque:, nenhuma cobrança")
    void reservaRejeitadaComErro4xxInesperado() {
        // Arrange
        stubPreco();
        ESTOQUE.stubFor(post("/reservas").willReturn(jsonResponse("""
                {"codigo":"DADOS_INVALIDOS","mensagem":"Lista de itens vazia ou quantidade menor que 1"}""", 422)));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        // Assert
        assertFalhaDeIntegracao(resposta, "estoque");
        ESTOQUE.verify(1, reserva());
        verificarSemCobrancaConfirmacaoOuLiberacao();
    }

    @Test
    @DisplayName("F10 [R10] Estoque responde 201 sem id na reserva: 503 com motivo estoque:, nenhuma cobrança")
    void reservaSemIdForaDoContrato() {
        // Arrange
        stubPreco();
        ESTOQUE.stubFor(post("/reservas").willReturn(jsonResponse("""
                {"pedidoId":"p-1","status":"RESERVADA","itens":[{"sku":"LIV-001","quantidade":2}]}""", 201)));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        // Assert
        assertFalhaDeIntegracao(resposta, "estoque");
        ESTOQUE.verify(1, reserva());
        verificarSemCobrancaConfirmacaoOuLiberacao();
    }

    @Test
    @DisplayName("F11 [R6, R9] Pagamentos responde 503 nas 3 tentativas: mesma chave, libera a reserva e devolve 503 pagamentos: (nunca 402)")
    void pagamentoIndisponivelEmTodasAsTentativas() {
        // Arrange
        stubPreco();
        stubReserva();
        stubLiberacao();
        PAGAMENTOS.stubFor(post("/pagamentos").willReturn(jsonResponse(ERRO_INDISPONIVEL, 503)));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        // Assert: indisponibilidade não é recusa, a reserva é liberada e nada é confirmado
        assertFalhaDeIntegracao(resposta, "pagamentos");
        verificarCobrancas(3, resposta.getBody());
        ESTOQUE.verify(1, liberacao());
        ESTOQUE.verify(0, confirmacao());
    }

    @Test
    @DisplayName("F12 [R6, R9] Pagamentos mais lento que o timeout nas 3 tentativas: mesma chave, libera a reserva e devolve 503 pagamentos:")
    void pagamentoMaisLentoQueOTimeoutEmTodasAsTentativas() {
        // Arrange
        stubPreco();
        stubReserva();
        stubLiberacao();
        PAGAMENTOS.stubFor(post("/pagamentos").willReturn(jsonResponse(PAGAMENTO_APROVADO, 201).withFixedDelay(TIMEOUT_MS * 2)));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        // Assert
        assertFalhaDeIntegracao(resposta, "pagamentos");
        verificarCobrancas(3, resposta.getBody());
        ESTOQUE.verify(1, liberacao());
        ESTOQUE.verify(0, confirmacao());
    }

    @Test
    @DisplayName("F13 [R6, R7] Pagamentos reseta a conexão, devolve resposta vazia e aprova na 3ª tentativa: mesma chave nas 3 e pedido confirmado")
    void pagamentoAprovadoNaUltimaTentativaPermitida() {
        // Arrange
        stubPreco();
        stubReserva();
        stubConfirmacao();
        PAGAMENTOS.stubFor(post("/pagamentos").inScenario("conexao-instavel").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("segunda-tentativa"));
        PAGAMENTOS.stubFor(post("/pagamentos").inScenario("conexao-instavel").whenScenarioStateIs("segunda-tentativa")
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE))
                .willSetStateTo("terceira-tentativa"));
        PAGAMENTOS.stubFor(post("/pagamentos").inScenario("conexao-instavel").whenScenarioStateIs("terceira-tentativa")
                .willReturn(jsonResponse(PAGAMENTO_APROVADO, 201)));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        // Assert
        assertThat(resposta.getStatusCode().value()).isEqualTo(201);
        assertThat(resposta.getBody().get("status").asText()).isEqualTo("CONFIRMADO");
        assertThat(motivo(resposta.getBody())).isNull();
        assertPedidoGravado(resposta.getBody());
        verificarCobrancas(3, resposta.getBody());
        ESTOQUE.verify(1, confirmacao());
        ESTOQUE.verify(0, liberacao());
    }

    @Test
    @DisplayName("F14 [R5, R6, R7] Resposta do Pagamentos perdida: a repetição usa a mesma chave e o mesmo corpo, recebe 200 com o pagamento original e confirma")
    void respostaPerdidaERepeticaoIdempotente() {
        // Arrange: a 1ª tentativa foi processada, mas a resposta chega depois do timeout; a 2ª devolve o pagamento original com 200
        stubPreco();
        stubReserva();
        stubConfirmacao();
        PAGAMENTOS.stubFor(post("/pagamentos").inScenario("resposta-perdida").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(jsonResponse(PAGAMENTO_APROVADO_CARTAO, 201).withFixedDelay(TIMEOUT_MS * 2))
                .willSetStateTo("reenvio"));
        PAGAMENTOS.stubFor(post("/pagamentos").inScenario("resposta-perdida").whenScenarioStateIs("reenvio")
                .willReturn(jsonResponse(PAGAMENTO_APROVADO_CARTAO, 200)));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_CARTAO);

        // Assert: um único pedido confirmado, com o pagamento original
        assertThat(resposta.getStatusCode().value()).isEqualTo(201);
        assertThat(resposta.getBody().get("status").asText()).isEqualTo("CONFIRMADO");
        assertThat(resposta.getBody().get("pagamentoId").asText()).isEqualTo("pag-1");
        assertPedidoGravado(resposta.getBody());
        verificarCobrancas(2, resposta.getBody());
        // Mesma chave e mesmo corpo (com outro corpo o Pagamentos responderia 409); o token vai nas duas tentativas
        List<LoggedRequest> tentativas = PAGAMENTOS.findAll(cobranca());
        assertThat(tentativas).hasSize(2);
        assertThat(tentativas.get(1).getBodyAsString()).isEqualTo(tentativas.get(0).getBodyAsString());
        PAGAMENTOS.verify(2, cobranca()
                .withRequestBody(matchingJsonPath("$.metodo", equalTo("CARTAO")))
                .withRequestBody(matchingJsonPath("$.tokenCartao", equalTo("tok-123")))
                .withRequestBody(matchingJsonPath("$.valorCentavos", equalTo("17980"))));
        ESTOQUE.verify(1, confirmacao());
        ESTOQUE.verify(0, liberacao());
    }

    @Test
    @DisplayName("F15 [R6, R9] Pagamentos responde 422: não repete, libera a reserva e devolve 503 pagamentos:")
    void pagamentoRejeitadoComErro4xxNaoERepetido() {
        // Arrange
        stubPreco();
        stubReserva();
        stubLiberacao();
        PAGAMENTOS.stubFor(post("/pagamentos").willReturn(jsonResponse("""
                {"codigo":"DADOS_INVALIDOS","mensagem":"Dados inválidos"}""", 422)));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        // Assert
        assertFalhaDeIntegracao(resposta, "pagamentos");
        verificarCobrancas(1, resposta.getBody());
        ESTOQUE.verify(1, liberacao());
        ESTOQUE.verify(0, confirmacao());
    }

    @Test
    @DisplayName("F16 [R6, R9] Pagamentos responde 503 e depois 409: o 4xx interrompe as tentativas (2 no total), libera a reserva e devolve 503 pagamentos:")
    void erro4xxDepoisDeFalhaTransitoriaInterrompeAsTentativas() {
        // Arrange
        stubPreco();
        stubReserva();
        stubLiberacao();
        PAGAMENTOS.stubFor(post("/pagamentos").inScenario("503-e-409").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(jsonResponse(ERRO_INDISPONIVEL, 503))
                .willSetStateTo("segunda-tentativa"));
        PAGAMENTOS.stubFor(post("/pagamentos").inScenario("503-e-409").whenScenarioStateIs("segunda-tentativa")
                .willReturn(jsonResponse("""
                        {"codigo":"CHAVE_IDEMPOTENCIA_REUTILIZADA","mensagem":"A chave já foi usada com dados diferentes"}""", 409)));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        // Assert
        assertFalhaDeIntegracao(resposta, "pagamentos");
        verificarCobrancas(2, resposta.getBody());
        ESTOQUE.verify(1, liberacao());
        ESTOQUE.verify(0, confirmacao());
    }

    static Stream<Arguments> pagamentosForaDoContrato() {
        return Stream.of(
                Arguments.of("sem id", """
                        {"pedidoId":"p-1","valorCentavos":17980,"metodo":"PIX","status":"APROVADO","motivo":null}"""),
                Arguments.of("sem status", """
                        {"id":"pag-1","pedidoId":"p-1","valorCentavos":17980,"metodo":"PIX","motivo":null}"""),
                Arguments.of("status fora do enum", """
                        {"id":"pag-1","pedidoId":"p-1","valorCentavos":17980,"metodo":"PIX","status":"PENDENTE","motivo":null}"""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pagamentosForaDoContrato")
    @DisplayName("F17 [R9] Pagamentos responde 201 fora do contrato: não repete, libera a reserva e devolve 503 pagamentos:")
    void pagamentoForaDoContrato(String variante, String corpoDoPagamento) {
        // Arrange
        stubPreco();
        stubReserva();
        stubLiberacao();
        PAGAMENTOS.stubFor(post("/pagamentos").willReturn(jsonResponse(corpoDoPagamento, 201)));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        // Assert
        assertFalhaDeIntegracao(resposta, "pagamentos");
        verificarCobrancas(1, resposta.getBody());
        ESTOQUE.verify(1, liberacao());
        ESTOQUE.verify(0, confirmacao());
    }

    @Test
    @DisplayName("F18 [R7, R11] Confirmação da reserva mais lenta que o timeout depois do pagamento aprovado: o pedido continua 201 CONFIRMADO")
    void confirmacaoMaisLentaQueOTimeout() {
        // Arrange
        stubPreco();
        stubReserva();
        stubLiberacao();
        PAGAMENTOS.stubFor(post("/pagamentos").willReturn(jsonResponse(PAGAMENTO_APROVADO, 201)));
        ESTOQUE.stubFor(post("/reservas/res-1/confirmacao")
                .willReturn(jsonResponse(RESERVA_CONFIRMADA, 200).withFixedDelay(TIMEOUT_MS * 2)));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        // Assert: o cliente já foi cobrado, então nada é compensado
        assertThat(resposta.getStatusCode().value()).isEqualTo(201);
        assertThat(resposta.getBody().get("status").asText()).isEqualTo("CONFIRMADO");
        assertThat(motivo(resposta.getBody())).isNull();
        assertPedidoGravado(resposta.getBody());
        verificarCobrancas(1, resposta.getBody());
        ESTOQUE.verify(1, confirmacao());
        ESTOQUE.verify(0, liberacao());
    }

    @Test
    @DisplayName("F19 [R7, R11] Confirmação da reserva responde 404 (reserva expirada) depois do pagamento aprovado: o pedido continua 201 CONFIRMADO")
    void confirmacaoRejeitadaComReservaExpirada() {
        // Arrange
        stubPreco();
        stubReserva();
        stubLiberacao();
        PAGAMENTOS.stubFor(post("/pagamentos").willReturn(jsonResponse(PAGAMENTO_APROVADO, 201)));
        ESTOQUE.stubFor(post("/reservas/res-1/confirmacao").willReturn(jsonResponse(ERRO_RESERVA_INEXISTENTE, 404)));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        // Assert
        assertThat(resposta.getStatusCode().value()).isEqualTo(201);
        assertThat(resposta.getBody().get("status").asText()).isEqualTo("CONFIRMADO");
        assertThat(motivo(resposta.getBody())).isNull();
        assertPedidoGravado(resposta.getBody());
        verificarCobrancas(1, resposta.getBody());
        ESTOQUE.verify(1, confirmacao());
        ESTOQUE.verify(0, liberacao());
    }

    static Stream<Arguments> falhasNaLiberacao() {
        return Stream.of(
                Arguments.of("liberação mais lenta que o timeout",
                        aResponse().withStatus(204).withFixedDelay(TIMEOUT_MS * 2)),
                Arguments.of("liberação responde 500",
                        jsonResponse(ERRO_INTERNO, 500)),
                Arguments.of("liberação responde 404 (reserva expirada)",
                        jsonResponse(ERRO_RESERVA_INEXISTENTE, 404)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("falhasNaLiberacao")
    @DisplayName("F20 [R8, R11] Pagamento recusado e a liberação da reserva falha: o pedido continua 402 PAGAMENTO_RECUSADO com o motivo do Pagamentos")
    void recusaComFalhaNaLiberacao(String variante, ResponseDefinitionBuilder respostaDaLiberacao) {
        // Arrange
        stubPreco();
        stubReserva();
        PAGAMENTOS.stubFor(post("/pagamentos").willReturn(jsonResponse(PAGAMENTO_RECUSADO, 201)));
        ESTOQUE.stubFor(delete(urlEqualTo("/reservas/res-1")).willReturn(respostaDaLiberacao));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        // Assert: a falha ao liberar não muda o status do pedido (a reserva expira sozinha no Estoque)
        assertThat(resposta.getStatusCode().value()).isEqualTo(402);
        assertThat(resposta.getBody().get("status").asText()).isEqualTo("PAGAMENTO_RECUSADO");
        assertThat(motivo(resposta.getBody())).isEqualTo("LIMITE_PIX_EXCEDIDO");
        assertPedidoGravado(resposta.getBody());
        verificarCobrancas(1, resposta.getBody());
        ESTOQUE.verify(1, liberacao());
        ESTOQUE.verify(0, confirmacao());
    }

    @Test
    @DisplayName("F21 [R9, R11, R12] Pagamentos indisponível e a liberação da reserva também falha: continua 503 com motivo pagamentos:")
    void pagamentoIndisponivelComFalhaNaLiberacao() {
        // Arrange
        stubPreco();
        stubReserva();
        PAGAMENTOS.stubFor(post("/pagamentos").willReturn(jsonResponse(ERRO_INDISPONIVEL, 503)));
        ESTOQUE.stubFor(delete(urlEqualTo("/reservas/res-1")).willReturn(noContent().withFixedDelay(TIMEOUT_MS * 2)));

        // Act
        ResponseEntity<JsonNode> resposta = postarPedido(PEDIDO_PIX);

        // Assert: o motivo continua sendo o do Pagamentos, não o da falha ao liberar
        assertFalhaDeIntegracao(resposta, "pagamentos");
        verificarCobrancas(3, resposta.getBody());
        ESTOQUE.verify(1, liberacao());
        ESTOQUE.verify(0, confirmacao());
    }

    // ---------------------------------------------------------------- apoio

    /** R9, R10 e R12: 503 FALHA_INTEGRACAO, motivo com o prefixo do serviço e o mesmo resultado no pedido gravado. */
    private void assertFalhaDeIntegracao(ResponseEntity<JsonNode> resposta, String servico) {
        assertThat(resposta.getStatusCode().value()).isEqualTo(503);
        assertThat(resposta.getBody().get("status").asText()).isEqualTo("FALHA_INTEGRACAO");
        assertThat(motivo(resposta.getBody())).startsWith(servico + ":");
        assertPedidoGravado(resposta.getBody());
    }

    /** R12: GET /pedidos/{id} devolve o pedido com o mesmo status e o mesmo motivo do POST. */
    private void assertPedidoGravado(JsonNode pedido) {
        ResponseEntity<JsonNode> consulta = consultarPedido(pedido.get("id").asText());
        assertThat(consulta.getStatusCode().value()).isEqualTo(200);
        assertThat(consulta.getBody().get("status").asText()).isEqualTo(pedido.get("status").asText());
        assertThat(motivo(consulta.getBody())).isEqualTo(motivo(pedido));
    }

    private static String motivo(JsonNode pedido) {
        return pedido.hasNonNull("motivo") ? pedido.get("motivo").asText() : null;
    }

    /** R6: o total de cobranças recebidas é o esperado e todas trazem a mesma Idempotency-Key: pedido-{id}. */
    private static void verificarCobrancas(int tentativas, JsonNode pedido) {
        String chave = "pedido-" + pedido.get("id").asText();
        PAGAMENTOS.verify(tentativas, cobranca());
        PAGAMENTOS.verify(tentativas, cobranca().withHeader("Idempotency-Key", equalTo(chave)));
    }

    /** O Estoque falhou antes de existir reserva: não há cobrança, confirmação nem liberação de nenhuma reserva. */
    private static void verificarSemCobrancaConfirmacaoOuLiberacao() {
        PAGAMENTOS.verify(0, cobranca());
        ESTOQUE.verify(0, postRequestedFor(urlPathMatching("/reservas/.+/confirmacao")));
        ESTOQUE.verify(0, deleteRequestedFor(urlPathMatching("/reservas/.+")));
    }

    private static RequestPatternBuilder cobranca() {
        return postRequestedFor(urlEqualTo("/pagamentos"));
    }

    private static RequestPatternBuilder reserva() {
        return postRequestedFor(urlEqualTo("/reservas"));
    }

    private static RequestPatternBuilder confirmacao() {
        return postRequestedFor(urlEqualTo("/reservas/res-1/confirmacao"));
    }

    private static RequestPatternBuilder liberacao() {
        return deleteRequestedFor(urlEqualTo("/reservas/res-1"));
    }

    private static void stubPreco() {
        ESTOQUE.stubFor(get("/produtos/LIV-001").willReturn(okJson(PRODUTO_LIVRO)));
    }

    private static void stubReserva() {
        ESTOQUE.stubFor(post("/reservas").willReturn(jsonResponse(RESERVA_RESERVADA, 201)));
    }

    private static void stubConfirmacao() {
        ESTOQUE.stubFor(post("/reservas/res-1/confirmacao").willReturn(okJson(RESERVA_CONFIRMADA)));
    }

    private static void stubLiberacao() {
        ESTOQUE.stubFor(delete(urlEqualTo("/reservas/res-1")).willReturn(noContent()));
    }
}
```

Os três `@ParameterizedTest` (F05, F17 e F20) usam `junit-jupiter-params`, que já vem no `spring-boot-starter-test`. Não executei a suíte aqui.

## 3. Divergências entre o código e as regras

**Contradição direta com R1 a R12 nos fluxos de falha: nenhuma encontrada.** Conferi o que as regras fixam e o código faz o mesmo:

- R6: 3 tentativas no total (`servicos.pagamentos.max-tentativas`), todas com `Idempotency-Key: pedido-{id}` e o mesmo corpo; timeout, reset e 5xx repetem, 4xx e resposta fora do contrato não.
- R9 e R10: 503 `FALHA_INTEGRACAO` com o prefixo `estoque:` ou `pagamentos:`; o Pagamentos não é chamado quando o Estoque falha; a reserva só é liberada quando existe `id`.
- R8 e R11: a confirmação que falha depois da aprovação e a liberação que falha depois da recusa ou da indisponibilidade são absorvidas (vão para o log) e não mudam status nem motivo.
- R12: todos esses caminhos gravam o pedido com o status final.

**Pontos de atenção.** Não são contradições literais com a regra, por isso não escrevi testes que falhem de propósito, mas vale decidir:

1. **O código valida o contrato menos do que o contrato exige.**
   - `PagamentosClient.validarContrato` só exige `id` e `status`. Um 201 sem `pedidoId`, `valorCentavos` ou `metodo` (todos obrigatórios no contrato), ou com `metodo` fora do enum, é aceito e o pedido sai `CONFIRMADO`. R9 cita "sem `id` ou `status`" como exemplo ("ex."); se a intenção for o contrato inteiro, o código diverge de R9.
   - `ContratoEstoque.Reserva.status` é `String` e não é conferido: um `status` fora de `RESERVADA/CONFIRMADA/LIBERADA` passa. R10 só cita "reserva sem `id`". No `Produto`, a ausência de `nome` ou `disponivel` também passa, e um `precoCentavos` numérico em texto (`"8990"`) é convertido pelo Jackson.
   - Se o time confirmar que R9 e R10 valem para todos os campos obrigatórios, bastam novas variantes em F10, F05 e F17 (503, motivo com prefixo, mesmas interações).
2. **R10 não diz o que fazer com 4xx do Estoque diferente de 404 e 409** (F09). O código devolve 503 `FALHA_INTEGRACAO` com `estoque:`, que é a única saída coerente com R4 (nunca cobrar sem reserva) e R12, mas a regra deveria dizer isso.
3. **Riscos que as próprias regras aceitam.**
   - Timeout na reserva (F06): o Estoque pode ter criado a reserva e o serviço não tem o `id` para liberar; só a expiração de 15 minutos devolve os itens.
   - Três timeouts no Pagamentos (F12): a cobrança pode ter sido processada com todas as respostas perdidas. O fluxo libera a reserva e responde 503 sem consultar o pagamento (o contrato tem `GET /pagamentos/{id}`, que o serviço não usa), então o cliente pode ficar cobrado sem pedido confirmado.
