package com.estudo.checkout.pedido;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Corpo de POST /pedidos. O preço não faz parte do pedido: ele é consultado no Estoque. */
public record NovoPedidoRequest(
        @NotBlank String clienteId,
        @NotEmpty @Size(max = 20) List<@Valid @NotNull Item> itens,
        @NotNull MetodoPagamento metodoPagamento,
        String tokenCartao) {

    public record Item(@NotBlank String sku, @Min(1) @Max(99) int quantidade) {
    }

    @JsonIgnore
    @AssertTrue(message = "tokenCartao é obrigatório quando metodoPagamento é CARTAO")
    public boolean isTokenCartaoInformadoQuandoCartao() {
        return metodoPagamento != MetodoPagamento.CARTAO || (tokenCartao != null && !tokenCartao.isBlank());
    }
}
