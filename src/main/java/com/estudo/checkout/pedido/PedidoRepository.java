package com.estudo.checkout.pedido;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Guarda os pedidos em memória: a persistência não faz parte do escopo desta atividade. */
@Repository
public class PedidoRepository {

    private final Map<String, Pedido> pedidos = new ConcurrentHashMap<>();

    public Pedido salvar(Pedido pedido) {
        pedidos.put(pedido.getId(), pedido);
        return pedido;
    }

    public Optional<Pedido> buscarPorId(String id) {
        return Optional.ofNullable(pedidos.get(id));
    }
}
