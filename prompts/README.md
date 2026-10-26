# Prompts

Três tipos de prompt, cada um para um aspecto diferente da integração. Cada tipo tem uma v1 (a primeira tentativa,
do jeito que eu escreveria sem pensar muito) e uma v2 (ajustada a partir do que as respostas da v1 mostraram). Cada
arquivo traz o texto exato enviado, os anexos e, na v2, a tabela "o que mudou e por quê".

| Tipo | Para que serve | v1 | v2 |
|------|----------------|----|----|
| [1. Cenários de integração](1-cenarios-integracao) | Fluxos de negócio entre Pedidos, Estoque e Pagamentos (caminho feliz, sem estoque, recusa, produto inexistente, validação) | Zero-shot com papel e o código anexado | Prompt estruturado: escopo fechado, regras como fonte da verdade, infraestrutura fixa e matriz de rastreabilidade antes do código |
| [2. Falhas de comunicação](2-falhas-comunicacao) | Timeout, conexão derrubada, 5xx, 4xx, resposta fora do contrato, retentativa, idempotência e compensação | Zero-shot com papel e o código anexado | Few-shot (um teste de exemplo) + checklist de tipos de falha + regras e contratos como fonte da verdade |
| [3. Contrato de API](3-contrato-api) | O que o Pedidos envia e lê de cada API bate com o OpenAPI do provedor? | Zero-shot com papel, contratos e código anexados | Chain-of-thought (listar, comparar com o contrato e só então gerar) + o YAML como fonte única da verdade |

Os anexos são colados no fim do prompt, como num chat: o código de `src/main/java`, o `pom.xml` e o
`application.yml` em todas as versões, mais os contratos no prompt 3 v1 e, em todas as v2, os contratos, as regras
de integração e a classe `IntegracaoBase`.

Os prompts nunca receberam a lista de [defeitos plantados](../mutantes), que é o gabarito da avaliação.
