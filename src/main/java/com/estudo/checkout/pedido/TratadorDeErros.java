package com.estudo.checkout.pedido;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/** Entrada inválida é rejeitada com 400 antes de qualquer chamada ao Estoque ou ao Pagamentos. */
@RestControllerAdvice
public class TratadorDeErros {

    public record ErroResponse(String codigo, List<String> detalhes) {
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErroResponse> dadosInvalidos(MethodArgumentNotValidException e) {
        List<String> detalhes = e.getBindingResult().getAllErrors().stream()
                .map(erro -> erro instanceof FieldError campo
                        ? campo.getField() + ": " + campo.getDefaultMessage()
                        : erro.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest().body(new ErroResponse("DADOS_INVALIDOS", detalhes));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErroResponse> corpoIlegivel(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new ErroResponse("DADOS_INVALIDOS", List.of("corpo da requisição ausente ou com formato inválido")));
    }
}
