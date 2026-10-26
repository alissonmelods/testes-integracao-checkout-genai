package com.estudo.checkout.pedido;

import com.estudo.checkout.integracao.FalhaIntegracaoException;
import com.estudo.checkout.integracao.estoque.ContratoEstoque;
import com.estudo.checkout.integracao.estoque.EstoqueClient;
import com.estudo.checkout.integracao.estoque.EstoqueInsuficienteException;
import com.estudo.checkout.integracao.estoque.ProdutoInexistenteException;
import com.estudo.checkout.integracao.pagamentos.ContratoPagamentos;
import com.estudo.checkout.integracao.pagamentos.PagamentosClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PedidoService {

    private static final Logger log = LoggerFactory.getLogger(PedidoService.class);

    private final EstoqueClient estoqueClient;
    private final PagamentosClient pagamentosClient;
    private final PedidoRepository pedidoRepository;

    public PedidoService(EstoqueClient estoqueClient, PagamentosClient pagamentosClient,
                         PedidoRepository pedidoRepository) {
        this.estoqueClient = estoqueClient;
        this.pagamentosClient = pagamentosClient;
        this.pedidoRepository = pedidoRepository;
    }

    /**
     * Checkout: consulta o preço de cada item no Estoque, reserva os itens, cobra no Pagamentos e confirma a reserva.
     * <p>
     * O pedido é gravado com o status que representa o resultado (ver {@link StatusPedido}). Quando a cobrança não
     * acontece, por recusa ou por falha de integração, a reserva é desfeita para devolver os itens ao estoque.
     */
    public Pedido criar(NovoPedidoRequest requisicao) {
        List<ItemPedido> itens = requisicao.itens().stream()
                .map(item -> new ItemPedido(item.sku(), item.quantidade()))
                .toList();
        Pedido pedido = new Pedido(requisicao.clienteId(), itens, requisicao.metodoPagamento());
        try {
            precificar(pedido);
            reservar(pedido);
            cobrar(pedido, requisicao.tokenCartao());
        } catch (ProdutoInexistenteException e) {
            pedido.finalizar(StatusPedido.PRODUTO_INEXISTENTE, e.getMessage());
        } catch (EstoqueInsuficienteException e) {
            pedido.finalizar(StatusPedido.SEM_ESTOQUE, e.getMessage());
        } catch (FalhaIntegracaoException e) {
            desfazerReserva(pedido);
            pedido.finalizar(StatusPedido.FALHA_INTEGRACAO, e.getMessage());
        }
        return pedidoRepository.salvar(pedido);
    }

    /** O preço vem sempre do Estoque: o cliente não informa preço no pedido. */
    private void precificar(Pedido pedido) {
        for (ItemPedido item : pedido.getItens()) {
            ContratoEstoque.Produto produto = estoqueClient.consultarProduto(item.getSku());
            item.definirPrecoUnitario(produto.precoCentavos());
        }
    }

    private void reservar(Pedido pedido) {
        List<ContratoEstoque.ItemReserva> itens = pedido.getItens().stream()
                .map(item -> new ContratoEstoque.ItemReserva(item.getSku(), item.getQuantidade()))
                .toList();
        ContratoEstoque.Reserva reserva = estoqueClient.reservar(pedido.getId(), itens);
        pedido.registrarReserva(reserva.id());
    }

    private void cobrar(Pedido pedido, String tokenCartao) {
        ContratoPagamentos.Pagamento pagamento = pagamentosClient.autorizar(
                pedido.getId(), pedido.getTotalCentavos(), pedido.getMetodoPagamento().name(), tokenCartao);
        pedido.registrarPagamento(pagamento.id());
        if (pagamento.status() == ContratoPagamentos.StatusPagamento.APROVADO) {
            confirmarReserva(pedido);
            pedido.finalizar(StatusPedido.CONFIRMADO, null);
        } else {
            desfazerReserva(pedido);
            pedido.finalizar(StatusPedido.PAGAMENTO_RECUSADO, pagamento.motivo());
        }
    }

    /** O pagamento já foi aprovado, então uma falha aqui não cancela o pedido: a confirmação fica pendente. */
    private void confirmarReserva(Pedido pedido) {
        try {
            estoqueClient.confirmar(pedido.getReservaId());
        } catch (FalhaIntegracaoException e) {
            log.error("Pedido {} pago, mas a confirmação da reserva {} ficou pendente: {}",
                    pedido.getId(), pedido.getReservaId(), e.getMessage());
        }
    }

    /**
     * Compensação: devolve ao estoque os itens reservados de um pedido que não vai ser cobrado.
     * Se a liberação falhar, a reserva expira sozinha no Estoque (15 minutos, pelo contrato), então só registramos o erro.
     */
    private void desfazerReserva(Pedido pedido) {
        if (pedido.getReservaId() == null) {
            return;
        }
        try {
            estoqueClient.liberar(pedido.getReservaId());
        } catch (FalhaIntegracaoException e) {
            log.error("Não foi possível liberar a reserva {} do pedido {}: {}",
                    pedido.getReservaId(), pedido.getId(), e.getMessage());
        }
    }
}
