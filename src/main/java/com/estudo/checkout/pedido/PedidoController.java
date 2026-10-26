package com.estudo.checkout.pedido;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pedidos")
public class PedidoController {

    private final PedidoService pedidoService;
    private final PedidoRepository pedidoRepository;

    public PedidoController(PedidoService pedidoService, PedidoRepository pedidoRepository) {
        this.pedidoService = pedidoService;
        this.pedidoRepository = pedidoRepository;
    }

    @PostMapping
    public ResponseEntity<PedidoResponse> criar(@Valid @RequestBody NovoPedidoRequest requisicao) {
        Pedido pedido = pedidoService.criar(requisicao);
        return ResponseEntity.status(pedido.getStatus().getStatusHttp()).body(PedidoResponse.de(pedido));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PedidoResponse> buscar(@PathVariable String id) {
        return pedidoRepository.buscarPorId(id)
                .map(pedido -> ResponseEntity.ok(PedidoResponse.de(pedido)))
                .orElse(ResponseEntity.notFound().build());
    }
}
