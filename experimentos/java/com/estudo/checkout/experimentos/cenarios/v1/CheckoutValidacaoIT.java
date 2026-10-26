package com.estudo.checkout.experimentos.cenarios.v1;

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
