# Resultado do experimento do oráculo

Gerado por `experimentos/oraculo/avaliar-oraculo.ps1` em 2026-10-26 00:31.

| Suíte | Testes | Pegam os bugs | Confirmam os bugs | Neutros | Quebrados |
|-------|-------:|--------------:|------------------:|--------:|----------:|
| oraculo-v1 | 92 | 16 | 0 | 76 | 0 |
| oraculo-v2 | 35 | 10 | 0 | 25 | 0 |

## Testes que dependem dos bugs

### oraculo-v1

- PEGA: `pagamentoForaDoContrato_liberaReserva(String, String, String)[1]` (com.estudo.checkout.experimentos.oraculo.v1.CompensacaoDeReservaIT)
- PEGA: `pagamentoForaDoContrato_liberaReserva(String, String, String)[2]` (com.estudo.checkout.experimentos.oraculo.v1.CompensacaoDeReservaIT)
- PEGA: `pagamentoForaDoContrato_liberaReserva(String, String, String)[3]` (com.estudo.checkout.experimentos.oraculo.v1.CompensacaoDeReservaIT)
- PEGA: `pagamentoForaDoContrato_liberaReserva(String, String, String)[4]` (com.estudo.checkout.experimentos.oraculo.v1.CompensacaoDeReservaIT)
- PEGA: `pagamentoForaDoContrato_liberaReserva(String, String, String)[5]` (com.estudo.checkout.experimentos.oraculo.v1.CompensacaoDeReservaIT)
- PEGA: `pagamentoForaDoContrato_liberaReserva(String, String, String)[6]` (com.estudo.checkout.experimentos.oraculo.v1.CompensacaoDeReservaIT)
- PEGA: `pagamentoIndisponivel_liberaReserva(String, ResponseDefinitionBuilder)[1]` (com.estudo.checkout.experimentos.oraculo.v1.CompensacaoDeReservaIT)
- PEGA: `pagamentoIndisponivel_liberaReserva(String, ResponseDefinitionBuilder)[2]` (com.estudo.checkout.experimentos.oraculo.v1.CompensacaoDeReservaIT)
- PEGA: `pagamentoIndisponivel_liberaReserva(String, ResponseDefinitionBuilder)[3]` (com.estudo.checkout.experimentos.oraculo.v1.CompensacaoDeReservaIT)
- PEGA: `pagamentoIndisponivel_liberaReserva(String, ResponseDefinitionBuilder)[4]` (com.estudo.checkout.experimentos.oraculo.v1.CompensacaoDeReservaIT)
- PEGA: `pagamentoIndisponivel_liberaReserva(String, ResponseDefinitionBuilder)[5]` (com.estudo.checkout.experimentos.oraculo.v1.CompensacaoDeReservaIT)
- PEGA: `pagamentoIndisponivel_liberaReserva(String, ResponseDefinitionBuilder)[6]` (com.estudo.checkout.experimentos.oraculo.v1.CompensacaoDeReservaIT)
- PEGA: `pagamentoIndisponivel_liberaReserva(String, ResponseDefinitionBuilder)[7]` (com.estudo.checkout.experimentos.oraculo.v1.CompensacaoDeReservaIT)
- PEGA: `pagamentosForaDoAr_liberaReserva` (com.estudo.checkout.experimentos.oraculo.v1.CompensacaoDeReservaIT)
- PEGA: `erroDeCliente_naoDeveSerRepetido(int)[1]` (com.estudo.checkout.experimentos.oraculo.v1.FalhasPagamentosIT)
- PEGA: `erroDeCliente_naoDeveSerRepetido(int)[2]` (com.estudo.checkout.experimentos.oraculo.v1.FalhasPagamentosIT)

### oraculo-v2

- PEGA: `pagamentoCom4xxNaoERepetido(int, String)[1]` (com.estudo.checkout.experimentos.oraculo.v2.FalhasComunicacaoIT)
- PEGA: `pagamentoCom4xxNaoERepetido(int, String)[2]` (com.estudo.checkout.experimentos.oraculo.v2.FalhasComunicacaoIT)
- PEGA: `pagamentoCom4xxNaoERepetido(int, String)[3]` (com.estudo.checkout.experimentos.oraculo.v2.FalhasComunicacaoIT)
- PEGA: `pagamentoCom5xxParaNaTerceiraTentativa` (com.estudo.checkout.experimentos.oraculo.v2.FalhasComunicacaoIT)
- PEGA: `pagamentoForaDoContrato(String, String)[1]` (com.estudo.checkout.experimentos.oraculo.v2.FalhasComunicacaoIT)
- PEGA: `pagamentoForaDoContrato(String, String)[2]` (com.estudo.checkout.experimentos.oraculo.v2.FalhasComunicacaoIT)
- PEGA: `pagamentoForaDoContrato(String, String)[3]` (com.estudo.checkout.experimentos.oraculo.v2.FalhasComunicacaoIT)
- PEGA: `pagamentoForaDoContrato(String, String)[4]` (com.estudo.checkout.experimentos.oraculo.v2.FalhasComunicacaoIT)
- PEGA: `pagamentoIndisponivelEFalhaAoLiberar` (com.estudo.checkout.experimentos.oraculo.v2.FalhasComunicacaoIT)
- PEGA: `pagamentoLentoNasTresTentativas` (com.estudo.checkout.experimentos.oraculo.v2.FalhasComunicacaoIT)

