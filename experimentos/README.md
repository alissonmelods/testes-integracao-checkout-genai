# Experimento: prompts para testes de integração

Este diretório guarda tudo o que a LLM gerou e como cada resposta foi avaliada.

| Caminho | Conteúdo |
|---------|----------|
| [`../prompts/`](../prompts) | Texto de cada prompt (v1 e v2), a técnica e, na v2, o que mudou e por quê |
| [`prompts-enviados/`](prompts-enviados) | O texto exato que a LLM recebeu, com os anexos já colados |
| [`respostas/`](respostas) | Resposta integral da LLM para cada prompt, sem edição |
| [`java/`](java/com/estudo/checkout/experimentos) | As classes de teste das respostas, prontas para compilar (ajustes anotados no cabeçalho do arquivo) |
| [`oraculo/`](oraculo) | Experimento extra: o teste gerado pega o bug ou confirma o bug? |
| [`extrair-testes.ps1`](extrair-testes.ps1) | Copia os blocos Java de uma resposta para `java/`, trocando só a linha `package` |
| [`../mutantes/`](../mutantes) | Os 10 defeitos plantados e o script que mede quais suítes os detectam |

## Como o experimento foi feito

1. **LLM:** Claude Sonnet 5.5 (Anthropic), executado pelo Claude Code. Cada prompt rodou numa sessão nova, sem
   histórico e sem acesso ao projeto: a LLM recebeu só o texto do prompt com os anexos (código e contratos colados,
   como num chat) e gravou a resposta num arquivo. Conferi no registro de cada execução que nenhum arquivo do projeto
   foi aberto.
2. **Uma resposta por prompt:** a primeira resposta completa de cada versão foi usada como veio, sem gerar de novo
   para escolher a melhor. As primeiras execuções do prompt 1 (v1 e v2) passaram do limite de 128 mil tokens de saída
   por mensagem e não terminaram, porque a LLM tentou gravar a resposta inteira de uma vez. Elas foram refeitas com o
   mesmo texto de prompt, só pedindo para gravar a resposta em partes.
3. **Extração:** os blocos Java da resposta foram copiados para `java/<prompt>/<versão>/`. A única mudança automática é
   o `package`, para os testes de experimentos diferentes compilarem juntos. Quando algo mais precisou mudar, a
   mudança foi a mínima possível e está anotada no arquivo.
4. **Avaliação de cada suíte:**
   - **Compilação e execução:** compilou de primeira? Quantos testes passam no código correto? Um teste que falha no
     código correto é um teste errado ou um bug real (as duas coisas aconteceram).
   - **Defeitos detectados:** quantos dos 10 [defeitos de integração plantados](../mutantes) a suíte pega. O defeito é
     detectado quando algum teste que passava no código correto passa a falhar.
   - **Diversidade:** que tipos de cenário a suíte cobre.
   - **Leitura dos testes:** integração de verdade ou mock disfarçado, verificação das chamadas aos serviços externos,
     asserções frágeis (texto exato de mensagem), tempo de execução e de onde vêm as expectativas.

## Resultado geral

| Prompt | Versão | Compilou? | Ajustes para rodar | Testes | Passam no código correto | Defeitos detectados | Tempo dos testes |
|--------|--------|-----------|--------------------|-------:|--------------------------|:-------------------:|-----------------:|
| 1. Cenários | v1 | sim | nenhum | 99 | 99 | 10/10 | ~10 s |
| 1. Cenários | v2 | sim | nenhum | 15 | 15 | 6/10 | ~1 s |
| 2. Falhas | v1 | sim | 1 linha (`@DirtiesContext`) | 64 | 16 antes do ajuste, 64 depois | 10/10 | ~9 s |
| 2. Falhas | v2 | sim | nenhum | 28 | 28 | 9/10 | ~4 s |
| 3. Contrato | v1 | sim | nenhum | 78 | 76 (os 2 que falhavam mostravam **bugs reais**); 78 após a correção | 10/10 | ~9 s |
| 3. Contrato | v2 | – | – | – | – | – | não rodou: interrompida pelo filtro de segurança da ferramenta (ver abaixo) |

O tempo é a soma do tempo dos testes, sem contar a subida do Spring. Nenhuma suíte usou Mockito, `@MockBean` ou
`Thread.sleep`: com o código e o `pom.xml` anexados, a LLM já escolheu WireMock e o serviço de verdade desde a v1.

### Defeitos plantados, suíte por suíte

| Defeito | Cenários v1 | Cenários v2 | Contrato v1 | Falhas v1 | Falhas v2 | Suíte final |
|---------|:-----------:|:-----------:|:-----------:|:---------:|:---------:|:-----------:|
| D1 SEM_COMPENSACAO | sim | sim | sim | sim | sim | sim |
| D2 VALOR_EM_REAIS | sim | sim | sim | sim | sim | sim |
| D3 NAO_CONFIRMA_RESERVA | sim | sim | sim | sim | sim | sim |
| D4 ESTOQUE_409_VIRA_FALHA | sim | sim | sim | sim | **não** | sim |
| D5 SEM_RETENTATIVA | sim | **não** | sim | sim | sim | sim |
| D6 NOVA_CHAVE_POR_TENTATIVA | sim | sim | sim | sim | sim | sim |
| D7 SEM_IDEMPOTENCY_KEY | sim | sim | sim | sim | sim | sim |
| D8 REPETE_ERRO_4XX | sim | **não** | sim | sim | sim | sim |
| D9 FALHA_VIRA_RECUSA | sim | **não** | sim | sim | sim | sim |
| D10 ACEITA_RESPOSTA_FORA_DO_CONTRATO | sim | **não** | sim | sim | sim | sim |
| **Total** | **10/10** | **6/10** | **10/10** | **10/10** | **9/10** | **10/10** |

Quantos testes de cada suíte falharam com cada defeito está em [`../mutantes/resultado.md`](../mutantes/resultado.md).

Como ler esta tabela:

- **As v1 pegaram tudo**, por dois motivos. Cada uma cobriu um pouco de tudo (a de cenários testou até
  retentativa), e testes derivados do código correto "congelam" o comportamento dele, então pegam qualquer desvio.
- **As v2 ficaram no próprio escopo, como foi pedido.** A de cenários não pega os quatro defeitos de resiliência (D5,
  D8, D9 e D10), que são do prompt de falhas. A de falhas não pega o D4 (o 409 do Estoque deixar de virar "sem
  estoque"), que é regra de negócio e está no escopo do prompt de cenários. A suíte final, que junta as duas, pega
  os 10.
- **A régua tem limite:** ela mostra se a suíte percebe um desvio do código correto, não se a suíte testa o
  requisito. Isso foi medido à parte, no [experimento do oráculo](oraculo).

## O que cada tipo de prompt trouxe

### 1. Cenários de integração

**v1** ([resposta](respostas/1-cenarios-v1.md)): a mais abrangente. São 99 testes em 8 classes: caminho feliz,
validação, Estoque, Pagamentos, confirmação, consulta e infraestrutura. Sem escopo, ela também cobriu falhas de rede e
retentativas, que eram do prompt 2. Dois pontos chamam a atenção:

- Ela avisou que **"os testes acima descrevem o comportamento atual"**, ou seja, são testes de caracterização do
  código que recebeu.
- Na lista de "pontos de atenção" ela apontou os mesmos dois problemas que o prompt de contrato provou com testes
  (token do cartão repassado no PIX e cobrança com valor 0), mas **sem teste que os acusasse**: ficaram como
  observação.

**v2** ([resposta](respostas/1-cenarios-v2.md)): exatamente 15 cenários, o limite pedido, cada um com a regra que
cobre, e só fluxos de negócio. Compilou e passou de primeira, sem ajuste. A diferença está no foco, não no volume:

- **Rastreabilidade:** a tabela liga cada cenário a uma regra (CF-01 a CF-15 cobrem R1 a R5, R7, R8 e R12) e lista
  as interações que precisam e as que não podem acontecer.
- **Regressões dos bugs encontrados:** a v2 já recebeu o código corrigido e as regras novas, e escreveu testes para
  elas: o token descartado no PIX (CF-03) e o pedido com total zero, que não chama o Pagamentos (CF-05).
- **Cuidado para não afirmar além da regra:** na seção de divergências, listou o que as regras não definem (por
  exemplo, o nome do campo do erro em R1) e explicou por que os testes não afirmam nada sobre isso.
- **Menos variações do mesmo ramo:** a v1 tinha 22 testes só de validação; a v2 escolheu 3 e disse que as outras
  variantes eram "a mesma regra com outro campo". Na suíte final, recuperei algumas variantes da v1.

### 2. Falhas de comunicação

**v1** ([resposta](respostas/2-falhas-v1.md)): muito boa na variedade. Cobriu timeout, conexão resetada, 4xx, 5xx,
resposta fora do contrato, falhas na confirmação e na liberação e até vazamento de detalhes internos na resposta. Os
problemas apareceram na hora de rodar e de manter:

- **Bug de infraestrutura:** `WireMockExtension` + `@Nested` reiniciava os servidores em outra porta a cada classe
  aninhada, mas o contexto do Spring continuava apontando para a porta antiga. Só 16 dos 64 testes passaram, e alguns
  desses passaram **pelo motivo errado** (o teste esperava "Estoque fora do ar" e o Estoque estava mesmo fora, mas por
  causa do bug). Irônico: a própria resposta tinha alertado para esse risco.
- **Expectativas tiradas do código:** "as expectativas saem do código e dos Javadocs", porque os contratos não vieram.
- **Asserções frágeis:** comparação com o texto inteiro do `motivo`.
- **Stubs fora do contrato:** respostas sem campos obrigatórios (ex.: pagamento sem `pedidoId`, `valorCentavos` e
  `metodo`).
- **Lentidão:** timeout de 1 s com atraso de 2,5 s.

**v2** ([resposta](respostas/2-falhas-v2.md)): 28 testes, compilou e passou de primeira, **sem nenhum ajuste**, em
menos da metade do tempo. São menos testes porque ela escolheu combinações que exercitam regras diferentes, em vez de
repetir variações do mesmo ramo. Usou a infraestrutura pedida, os stubs com os campos do contrato e só o prefixo do `motivo`. Trouxe o
caso que faltava, a **resposta perdida** (a primeira tentativa é processada, a resposta se perde e a repetição recebe
200 com o pagamento original), e listou as combinações que deixou de fora, com o motivo. Começou dizendo "montei os
casos a partir das regras R1 a R12 e dos dois contratos; o código só foi lido depois". Na seção de divergências,
apontou uma **lacuna na especificação**: a R10 não dizia o que fazer com um 4xx do Estoque diferente de 404 e 409. A
regra foi corrigida.

### 3. Contrato de API

**v1** ([resposta](respostas/3-contrato-v1.md)): a de maior valor do experimento. Ela criou uma regra que valida
**todo** o tráfego enviado aos provedores num `@AfterEach` e testou cada status documentado das quatro operações do
Estoque e da operação do Pagamentos. Dois testes vieram marcados como "lacuna", feitos para falhar no código da época,
e os dois mostravam **bugs reais**:

1. com PIX, o `tokenCartao` informado no pedido era repassado ao Pagamentos, e o contrato diz "não enviar com PIX";
2. um produto de preço 0 (válido no contrato do Estoque) gerava `valorCentavos: 0`, abaixo do mínimo 1 do Pagamentos.

Os dois foram corrigidos no commit `dceb9d6`. O ponto fraco, que a própria resposta reconheceu: o contrato foi
"transcrito à mão" em asserções. Se o YAML mudar, os testes não percebem.

**v2:** a geração foi interrompida por um filtro de segurança da ferramenta, sem o motivo. O aviso pedia para não
produzir o mesmo conteúdo de novo, nem reformulado, então não insisti. O prompt v2 está documentado em
[`prompts/3-contrato-api/v2.md`](../prompts/3-contrato-api/v2.md), com os ajustes planejados (o principal: os testes
lerem o YAML em vez de copiá-lo), mas sem resultado para comparar.

## Experimento extra: o teste pega o bug ou confirma o bug?

Na v1, a LLM avisou que tirava as expectativas do código. Para ver o risco disso, rodei o prompt de falhas v1 e v2
sobre uma cópia do código com dois bugs plantados, com os comentários coerentes com o bug. Nenhuma versão transformou
o bug em requisito: a v1 pegou os dois por pistas no código e por boa prática, chamando um deles de "suspeita, pode
ser intencional"; a v2 apontou os dois como divergências das regras R6 e R9. Detalhes em [`oraculo/`](oraculo).

## Bugs reais encontrados pela LLM

| # | Bug | Quem achou | Correção |
|---|-----|------------|----------|
| 1 | Com PIX, o `tokenCartao` do pedido era repassado ao Pagamentos (o contrato proíbe) | Contrato v1 (teste); Cenários v1 (observação) | `dceb9d6` |
| 2 | Pedido só com itens de preço 0 enviava `valorCentavos: 0` (o Pagamentos exige no mínimo 1) | Contrato v1 (teste); Cenários v1 (observação) | `dceb9d6` |
| 3 | `2 x Long.MAX_VALUE` estourava o total para negativo e o pedido era confirmado sem cobrança. Esse bug **eu introduzi** ao corrigir o item 2 | Oráculo v1 (teste) | `6247e5f` |

Além disso, as respostas levantaram riscos que ficaram documentados como aceitos em
[`docs/regras-de-integracao.md`](../docs/regras-de-integracao.md) (reserva órfã depois de timeout, cobrança sem
pedido confirmado depois de três timeouts e o fato de os clients validarem só os campos que usam).

## Limites do experimento

- **Uma resposta por versão.** A LLM não é determinística; outra execução do mesmo prompt pode dar outro resultado.
- **Um único modelo.** Os resultados são do Claude Sonnet 5.5 com o código anexado. Um modelo menor, ou um prompt sem o
  código, provavelmente mostraria uma diferença maior entre v1 e v2.
- **A régua dos defeitos plantados mede detecção de regressão.** Testes derivados do código correto já pegam desvios
  dele. Por isso as v1, que cobriram de tudo, tiraram a nota máxima nessa métrica, e as v2, com escopo fechado,
  ficaram abaixo quando avaliadas sozinhas. O experimento do oráculo foi necessário para medir a outra coisa: testar
  o requisito em vez do código.
