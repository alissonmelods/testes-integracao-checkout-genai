# Resultado da avaliação com defeitos plantados

Gerado por `mutantes/avaliar-mutantes.ps1` em 2026-10-26 01:13.

## Testes por suíte (código original, sem defeitos)

| Suíte | Testes | Passam | Falham no código correto |
|-------|-------:|-------:|-------------------------:|
| cenarios-v1 | 99 | 99 | 0 |
| cenarios-v2 | 15 | 15 | 0 |
| contrato-v1 | 78 | 78 | 0 |
| falhas-v1 | 64 | 64 | 0 |
| falhas-v2 | 28 | 28 | 0 |
| final | 140 | 140 | 0 |

## Defeitos detectados

| Defeito | cenarios-v1 | cenarios-v2 | contrato-v1 | falhas-v1 | falhas-v2 | final |
|---------|:---:|:---:|:---:|:---:|:---:|:---:|
| D1 SEM_COMPENSACAO | sim (23) | sim (2) | sim (3) | sim (21) | sim (11) | sim (18) |
| D2 VALOR_EM_REAIS | sim (4) | sim (7) | sim (2) | sim (1) | sim (1) | sim (11) |
| D3 NAO_CONFIRMA_RESERVA | sim (13) | sim (6) | sim (5) | sim (5) | sim (5) | sim (16) |
| D4 ESTOQUE_409_VIRA_FALHA | sim (4) | sim (1) | sim (3) | sim (5) | não | sim (7) |
| D5 SEM_RETENTATIVA | sim (15) | não | sim (10) | sim (9) | sim (7) | sim (17) |
| D6 NOVA_CHAVE_POR_TENTATIVA | sim (16) | sim (7) | sim (35) | sim (2) | sim (16) | sim (59) |
| D7 SEM_IDEMPOTENCY_KEY | sim (16) | sim (7) | sim (35) | sim (2) | sim (16) | sim (59) |
| D8 REPETE_ERRO_4XX | sim (6) | não | sim (3) | sim (6) | sim (2) | sim (5) |
| D9 FALHA_VIRA_RECUSA | sim (41) | não | sim (3) | sim (50) | sim (20) | sim (28) |
| D10 ACEITA_RESPOSTA_FORA_DO_CONTRATO | sim (3) | não | sim (3) | sim (4) | sim (2) | sim (5) |
| **Total detectado** | **10/10** | **6/10** | **10/10** | **10/10** | **9/10** | **10/10** |

Entre parênteses: quantos testes da suíte falharam com o defeito.
