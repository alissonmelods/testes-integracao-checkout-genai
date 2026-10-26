# Defeitos plantados (mutantes)

Para comparar as suítes geradas por cada prompt com uma régua objetiva, plantei 10 defeitos típicos de integração no
serviço de Pedidos. Cada defeito é uma troca pequena de código (um "mutante") que quebra uma das
[regras de integração](../docs/regras-de-integracao.md). Uma suíte **detecta** o defeito quando algum teste que passa
no código correto passa a falhar com o defeito.

Os prompts nunca receberam esta lista: ela é o gabarito da avaliação.

| Defeito | O que muda no código | Regra quebrada |
|---------|----------------------|----------------|
| D1 SEM_COMPENSACAO | Não libera a reserva quando o pedido não é cobrado | R8, R9 |
| D2 VALOR_EM_REAIS | Envia ao Pagamentos o total dividido por 100 (reais em vez de centavos) | R5 |
| D3 NAO_CONFIRMA_RESERVA | Não confirma a reserva depois do pagamento aprovado | R7 |
| D4 ESTOQUE_409_VIRA_FALHA | O 409 do Estoque deixa de ser "sem estoque" e vira falha genérica (503) | R4 |
| D5 SEM_RETENTATIVA | Faz uma única tentativa de pagamento | R6 |
| D6 NOVA_CHAVE_POR_TENTATIVA | Cada tentativa usa uma Idempotency-Key diferente | R6 |
| D7 SEM_IDEMPOTENCY_KEY | Não envia o header Idempotency-Key | R5 |
| D8 REPETE_ERRO_4XX | Repete também respostas 4xx | R6 |
| D9 FALHA_VIRA_RECUSA | Falha de integração vira PAGAMENTO_RECUSADO (402) | R9, R10 |
| D10 ACEITA_RESPOSTA_FORA_DO_CONTRATO | Não valida a resposta do Pagamentos (sem id/status vira recusa) | R9 |

O trecho exato de cada troca está em [`mutantes.json`](mutantes.json).

## Como rodar

```powershell
powershell -ExecutionPolicy Bypass -File mutantes\avaliar-mutantes.ps1            # todos os defeitos
powershell -ExecutionPolicy Bypass -File mutantes\avaliar-mutantes.ps1 -Somente D1,D6
```

O script nunca altera o projeto. Para cada defeito ele copia o projeto para uma pasta temporária, aplica a troca, roda
todas as suítes (`-Pexperimentos`) e compara com a execução no código correto. O resultado vai para
[`resultado.md`](resultado.md) e `resultado.json` (quais testes detectaram cada defeito). Leva cerca de 10 minutos.
