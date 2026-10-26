# Experimento extra: o teste pega o bug ou confirma o bug?

Na v1, a própria LLM avisou que "as expectativas saem do código e dos Javadocs". Com o código correto, isso funciona:
os testes "congelam" o comportamento certo e pegam qualquer desvio (por isso a v1 detectou todos os
[defeitos plantados](../../mutantes)). A dúvida é o que acontece quando **o código que vai no prompt já tem um bug**.

## Montagem

Copiei o código e plantei dois bugs realistas, com os Javadocs coerentes com o bug, como acontece quando quem escreveu
acha que está certo:

| Bug | O que o código com bug faz | Regra quebrada |
|-----|----------------------------|----------------|
| A | Quando o Pagamentos falha (indisponível, 4xx ou resposta fora do contrato), a reserva **não** é liberada; só a recusa libera | R9 |
| B | Toda resposta HTTP de erro do Pagamentos é repetida, **inclusive 4xx** | R6 |

Os dois arquivos estão em [`codigo-com-bugs/`](codigo-com-bugs). A diferença para o código correto:

```diff
 // PedidoService.criar
-     * O pedido é gravado com o status que representa o resultado (ver {@link StatusPedido}). Quando a cobrança não
-     * acontece, por recusa ou por falha de integração, a reserva é desfeita para devolver os itens ao estoque.
+     * O pedido é gravado com o status que representa o resultado (ver {@link StatusPedido}). Quando o pagamento é
+     * recusado, a reserva é desfeita para devolver os itens ao estoque.
 ...
         } catch (FalhaIntegracaoException e) {
-            desfazerReserva(pedido);
             pedido.finalizar(StatusPedido.FALHA_INTEGRACAO, e.getMessage());

 // PagamentosClient.autorizar
-     * Timeout, falha de conexão e HTTP 5xx são falhas transitórias: a chamada é repetida até {@code maxTentativas}
-     * vezes, sempre com a mesma Idempotency-Key. (...) HTTP 4xx não é repetido,
-     * porque a próxima tentativa receberia a mesma resposta.
+     * Se a chamada falhar (timeout, falha de conexão ou erro HTTP), ela é repetida até {@code maxTentativas} vezes,
+     * sempre com a mesma Idempotency-Key. (...)
 ...
-            } catch (HttpServerErrorException | ResourceAccessException e) {
+            } catch (HttpStatusCodeException | ResourceAccessException e) {
```

Mandei o prompt de falhas **v1** e **v2** sem mudar uma palavra, só trocando o código anexado pelo código com bugs. A
v2 leva também as regras e os contratos. Depois rodei os testes gerados no código com bugs e no código correto
([`avaliar-oraculo.ps1`](avaliar-oraculo.ps1)):

- teste que **falha com os bugs e passa no correto**: pegou o bug;
- teste que **passa com os bugs e falha no correto**: transformou o bug em requisito.

| | Prompt | Resposta da LLM | Testes |
|---|--------|-----------------|--------|
| v1 | [prompts/2-falhas-comunicacao/v1.md](../../prompts/2-falhas-comunicacao/v1.md) | [respostas/2-falhas-v1-codigo-com-bugs.md](../respostas/2-falhas-v1-codigo-com-bugs.md) | [`java/.../oraculo/v1`](../java/com/estudo/checkout/experimentos/oraculo/v1) |
| v2 | [prompts/2-falhas-comunicacao/v2.md](../../prompts/2-falhas-comunicacao/v2.md) | [respostas/2-falhas-v2-codigo-com-bugs.md](../respostas/2-falhas-v2-codigo-com-bugs.md) | [`java/.../oraculo/v2`](../java/com/estudo/checkout/experimentos/oraculo/v2) |

## Resultado

| Suíte | Testes | Pegam os bugs | Confirmam os bugs (transformaram o bug em requisito) |
|-------|-------:|--------------:|-----------------------------------------------------:|
| v1 (sem regras) | 92 | 16 | 0 |
| v2 (com regras e contratos) | 35 | 10 | 0 |

Detalhe por teste em [`resultado.md`](resultado.md).

**Nenhuma das duas versões transformou o bug em requisito**, mas por caminhos diferentes:

- **v1 (sem regras):** a LLM leu o código com olhar crítico. O bug A ela chamou de "bug confirmado", usando duas
  pistas que ficaram no código: o Javadoc de `StatusPedido.FALHA_INTEGRACAO` ("a reserva, se havia, foi desfeita"),
  que eu esqueci de alterar ao plantar o bug, e o `if (pedido.getReservaId() == null)` de `desfazerReserva`, que só
  faz sentido se o método for chamado no `catch` de falha. O bug B ela tratou como **"suspeita"**: escreveu o teste
  esperando uma única chamada (porque 4xx é determinístico), mas avisou que "o Javadoc fala em 'erro HTTP', então pode
  ser intencional".
- **v2 (com regras):** os dois viraram **"divergências reais"**, cada uma ligada à regra quebrada (R6 e R9), e a
  resposta já listava quais testes iam falhar. Sem "pode ser intencional": a regra decide.

**O que isso mostra.** Um modelo forte, lendo o código, pega bugs que deixam rastro (comentário que contradiz o código,
checagem que não faz sentido) ou que contrariam uma boa prática conhecida. O que ele não tem como saber sem a regra é a
**intenção** de negócio: no bug B, a v1 escreveu o teste certo, mas como palpite. Um bug sem rastro e sem boa prática
contrária (por exemplo, "pedido com total zero não é cobrado") não tem como ser pego sem a regra no prompt.

**Achado extra.** Um teste da v1 (`precoAbsurdoNaoPodeGerarPedidoSemCobranca`) falhava nos dois códigos, o correto e o
com bugs. Era um bug real que eu tinha introduzido ao corrigir o pedido com total zero: `2 x Long.MAX_VALUE` estourava
o total para -2, o `if (total > 0)` pulava a cobrança e o pedido saía `CONFIRMADO` sem pagar. Corrigido no commit
`6247e5f` (soma com aritmética exata). Por isso os arquivos de [`codigo-com-bugs/`](codigo-com-bugs) são o código atual
com os bugs A e B; o código exato que a LLM recebeu está em [`../prompts-enviados/`](../prompts-enviados).
