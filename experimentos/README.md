# Experimento: prompts para testes de integração

Este diretório guarda tudo o que a LLM gerou e como cada resposta foi avaliada.

| Caminho | Conteúdo |
|---------|----------|
| [`../prompts/`](../prompts) | Texto exato de cada prompt (v1 e v2) e a técnica usada |
| [`respostas/`](respostas) | Resposta integral da LLM para cada prompt, sem edição |
| [`java/`](java) | As classes de teste das respostas, prontas para compilar (ajustes anotados no cabeçalho de cada arquivo) |
| [`extrair-testes.ps1`](extrair-testes.ps1) | Copia os blocos Java de uma resposta para `java/`, trocando só a linha `package` |
| [`../mutantes/`](../mutantes) | Defeitos plantados e o script que mede quais suítes os detectam |

## Como o experimento foi feito

1. **LLM:** Claude Sonnet 5.5 (Anthropic), executado pelo Claude Code. Cada prompt rodou numa sessão nova, sem
   histórico e sem acesso ao projeto: a LLM recebeu só o texto do prompt com os anexos (código e contratos colados,
   como num chat).
2. **Uma resposta por prompt:** a primeira resposta de cada versão foi usada como veio, sem gerar de novo para escolher
   a melhor.
3. **Extração:** os blocos Java da resposta foram copiados para `java/<prompt>/<versão>/`. A única mudança automática é
   o `package`, para os testes de experimentos diferentes compilarem juntos. Quando algo mais precisou mudar para
   compilar, a mudança foi a mínima possível e está anotada no cabeçalho do arquivo e na análise.
4. **Avaliação:** para cada suíte gerada foram medidos:
   - **Compilação:** compilou de primeira? Quantos ajustes foram necessários?
   - **Execução:** quantos testes passam no código correto. Um teste que falha no código correto é um teste errado
     (expectativa inventada ou montagem quebrada).
   - **Defeitos detectados:** quantos dos 10 [defeitos de integração plantados](../mutantes/mutantes.json) a suíte
     pega. Cada defeito é uma mudança pequena no código (ex.: não liberar a reserva quando o pagamento é recusado) e é
     considerado detectado quando algum teste que passava no código correto passa a falhar.
   - **Diversidade:** quais categorias de cenário a suíte cobre (fluxo feliz, regras de negócio, falhas de rede,
     falhas HTTP, idempotência, compensação, contrato de requisição, contrato de resposta, validação de entrada).
   - **Leitura dos testes:** se é integração de verdade ou mock disfarçado, se verifica as chamadas aos serviços
     externos ou só o status HTTP, e se as expectativas vêm das regras ou foram inventadas.

## Como reproduzir

```powershell
# testes gerados nos experimentos + suíte final
.\mvnw.cmd test -Pexperimentos

# avaliação com os defeitos plantados (gera mutantes/resultado.md)
powershell -ExecutionPolicy Bypass -File mutantes\avaliar-mutantes.ps1
```
