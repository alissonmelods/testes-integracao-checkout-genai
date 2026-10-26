<#
.SYNOPSIS
    Mede quais suítes de teste detectam cada defeito de integração plantado (mutante).

.DESCRIPTION
    1. Roda todos os testes (suíte final + testes gerados nos experimentos) no código original.
    2. Para cada mutante de mutantes.json: copia o projeto para uma pasta temporária, troca o trecho indicado,
       roda os testes de novo e compara com a execução original.

    Um mutante é DETECTADO por uma suíte quando algum teste que passa no código original falha com o defeito.
    Testes que já falham no código original não contam (são testes quebrados).
    O código do projeto nunca é alterado: cada mutante roda numa cópia.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File mutantes\avaliar-mutantes.ps1
    powershell -ExecutionPolicy Bypass -File mutantes\avaliar-mutantes.ps1 -Somente D1,D5
#>
param(
    [string[]]$Somente
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$utf8SemBom = New-Object System.Text.UTF8Encoding $false

$raiz = Split-Path $PSScriptRoot -Parent
$mutantes = Get-Content (Join-Path $PSScriptRoot 'mutantes.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($Somente) { $mutantes = $mutantes | Where-Object { $Somente -contains $_.id } }
$pastaTrabalho = Join-Path ([System.IO.Path]::GetTempPath()) 'avaliacao-mutantes-checkout'

function Copiar-Projeto([string]$destino) {
    if (Test-Path $destino) { Remove-Item -Recurse -Force $destino }
    New-Item -ItemType Directory -Force $destino | Out-Null
    foreach ($item in 'pom.xml', 'mvnw', 'mvnw.cmd', '.mvn', 'src', 'experimentos', 'contratos') {
        $origem = Join-Path $raiz $item
        if (Test-Path $origem) { Copy-Item -Recurse $origem $destino }
    }
}

# A suíte é definida pelo pacote: com.estudo.checkout.experimentos.<prompt>.<versao>.* ou "final" para o resto.
function Suite-Do-Teste([string]$classe) {
    if ($classe -match '\.experimentos\.([^.]+)\.(v\d+)\.') { return "$($Matches[1])-$($Matches[2])" }
    return 'final'
}

# Devolve um hashtable "classe#teste" -> passou | falhou | ignorado, ou $null se o projeto não compilou.
function Executar-Testes([string]$pasta) {
    # Avisos do Maven/Mockito no stderr não podem interromper o script (no PowerShell 5.1 eles viram erro).
    $ErrorActionPreference = 'Continue'
    $relatorios = Join-Path $pasta 'target\surefire-reports'
    if (Test-Path $relatorios) { Remove-Item -Recurse -Force $relatorios }
    $saida = & (Join-Path $pasta 'mvnw.cmd') -B -f (Join-Path $pasta 'pom.xml') -Pexperimentos test '-Dmaven.test.failure.ignore=true' 2>&1
    if (-not (Test-Path $relatorios)) {
        $saida | Select-String 'ERROR' | Select-Object -First 15 | ForEach-Object { Write-Host "   $_" -ForegroundColor DarkYellow }
        return $null
    }
    $resultados = @{}
    foreach ($arquivo in Get-ChildItem $relatorios -Filter 'TEST-*.xml') {
        # A classe de primeiro nível vem do nome do arquivo: no XML, o "classname" de testes @Nested pode vir com o
        # @DisplayName. A chave inclui os dois para não misturar métodos de mesmo nome em classes aninhadas diferentes.
        $classe = $arquivo.BaseName.Substring('TEST-'.Length)
        [xml]$xml = Get-Content $arquivo.FullName -Raw -Encoding UTF8
        foreach ($caso in $xml.testsuite.testcase) {
            $estado = 'passou'
            if ($caso.SelectSingleNode('failure') -or $caso.SelectSingleNode('error')) { $estado = 'falhou' }
            elseif ($caso.SelectSingleNode('skipped')) { $estado = 'ignorado' }
            $resultados["$classe#$($caso.classname)#$($caso.name)"] = $estado
        }
    }
    return $resultados
}

Write-Host "Executando os testes no código original..." -ForegroundColor Cyan
$pastaOriginal = Join-Path $pastaTrabalho 'original'
Copiar-Projeto $pastaOriginal
$original = Executar-Testes $pastaOriginal
if ($null -eq $original) { throw 'O projeto original não compilou.' }

# As suítes do experimento do oráculo foram geradas a partir de código com bugs e têm avaliação própria.
$suites = $original.Keys | ForEach-Object { Suite-Do-Teste ($_ -split '#')[0] } | Where-Object { $_ -notlike 'oraculo-*' } |
    Sort-Object -Unique
$resumoSuites = foreach ($suite in $suites) {
    $testes = $original.Keys | Where-Object { (Suite-Do-Teste ($_ -split '#')[0]) -eq $suite }
    [pscustomobject]@{
        suite   = $suite
        testes  = @($testes).Count
        passam  = @($testes | Where-Object { $original[$_] -eq 'passou' }).Count
        falham  = @($testes | Where-Object { $original[$_] -eq 'falhou' }).Count
    }
}
$resumoSuites | Format-Table -AutoSize | Out-String | Write-Host

$deteccoes = @()
foreach ($mutante in $mutantes) {
    Write-Host "Mutante $($mutante.id) $($mutante.nome)..." -ForegroundColor Cyan
    $pastaMutante = Join-Path $pastaTrabalho $mutante.id
    Copiar-Projeto $pastaMutante
    $arquivo = Join-Path $pastaMutante $mutante.arquivo
    $codigo = [System.IO.File]::ReadAllText($arquivo, $utf8SemBom)
    $ocorrencias = ([regex]::Matches($codigo, [regex]::Escape($mutante.trecho))).Count
    if ($ocorrencias -ne 1) { throw "Mutante $($mutante.id): o trecho aparece $ocorrencias vezes em $($mutante.arquivo) (esperado: 1)." }
    [System.IO.File]::WriteAllText($arquivo, $codigo.Replace($mutante.trecho, $mutante.substituto), $utf8SemBom)

    $comDefeito = Executar-Testes $pastaMutante
    if ($null -eq $comDefeito) { throw "Mutante $($mutante.id) não compilou." }

    foreach ($suite in $suites) {
        $testesQueDetectaram = @($original.Keys | Where-Object {
            (Suite-Do-Teste ($_ -split '#')[0]) -eq $suite -and $original[$_] -eq 'passou' -and $comDefeito[$_] -eq 'falhou'
        } | Sort-Object)
        $deteccoes += [pscustomobject]@{
            mutante = $mutante.id
            nome    = $mutante.nome
            suite   = $suite
            detectou = $testesQueDetectaram.Count -gt 0
            testes  = $testesQueDetectaram
        }
    }
    Remove-Item -Recurse -Force $pastaMutante
}

# Relatório em Markdown: uma linha por mutante, uma coluna por suíte.
$linhas = @()
$linhas += '# Resultado da avaliação com defeitos plantados'
$linhas += ''
$linhas += "Gerado por ``mutantes/avaliar-mutantes.ps1`` em $(Get-Date -Format 'yyyy-MM-dd HH:mm')."
$linhas += ''
$linhas += '## Testes por suíte (código original, sem defeitos)'
$linhas += ''
$linhas += '| Suíte | Testes | Passam | Falham no código correto |'
$linhas += '|-------|-------:|-------:|-------------------------:|'
foreach ($resumo in $resumoSuites) { $linhas += "| $($resumo.suite) | $($resumo.testes) | $($resumo.passam) | $($resumo.falham) |" }
$linhas += ''
$linhas += '## Defeitos detectados'
$linhas += ''
$linhas += "| Defeito | $($suites -join ' | ') |"
$linhas += "|---------|$((@($suites) | ForEach-Object { ':---:' }) -join '|')|"
foreach ($mutante in $mutantes) {
    $celulas = foreach ($suite in $suites) {
        $deteccao = $deteccoes | Where-Object { $_.mutante -eq $mutante.id -and $_.suite -eq $suite }
        if ($deteccao.detectou) { "sim ($(@($deteccao.testes).Count))" } else { 'não' }
    }
    $linhas += "| $($mutante.id) $($mutante.nome) | $($celulas -join ' | ') |"
}
$totais = foreach ($suite in $suites) {
    $detectados = @($deteccoes | Where-Object { $_.suite -eq $suite -and $_.detectou }).Count
    "**$detectados/$(@($mutantes).Count)**"
}
$linhas += "| **Total detectado** | $($totais -join ' | ') |"
$linhas += ''
$linhas += 'Entre parênteses: quantos testes da suíte falharam com o defeito.'

[System.IO.File]::WriteAllLines((Join-Path $PSScriptRoot 'resultado.md'), $linhas, $utf8SemBom)
$deteccoes | ConvertTo-Json -Depth 4 | ForEach-Object { [System.IO.File]::WriteAllText((Join-Path $PSScriptRoot 'resultado.json'), $_, $utf8SemBom) }
$linhas | Write-Host
