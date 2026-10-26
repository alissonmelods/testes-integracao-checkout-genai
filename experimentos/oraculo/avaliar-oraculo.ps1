<#
.SYNOPSIS
    Experimento do oráculo: os testes gerados a partir de código com bugs confirmam os bugs ou pegam os bugs?

.DESCRIPTION
    Roda as suítes do pacote com.estudo.checkout.experimentos.oraculo duas vezes, numa cópia do projeto:
    no código correto e no código com os bugs plantados (os arquivos de codigo-com-bugs/ por cima do original).

    Para cada teste:
      - falha com os bugs e passa no correto  -> PEGA o bug
      - passa com os bugs e falha no correto  -> CONFIRMA o bug (transformou o bug em requisito)
      - passa nos dois                        -> neutro (não depende dos bugs)
      - falha nos dois                        -> quebrado

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File experimentos\oraculo\avaliar-oraculo.ps1
#>
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$utf8SemBom = New-Object System.Text.UTF8Encoding $false

$raiz = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$pastaTrabalho = Join-Path ([System.IO.Path]::GetTempPath()) 'avaliacao-oraculo-checkout'

function Copiar-Projeto([string]$destino) {
    if (Test-Path $destino) { Remove-Item -Recurse -Force $destino }
    New-Item -ItemType Directory -Force $destino | Out-Null
    foreach ($item in 'pom.xml', 'mvnw', 'mvnw.cmd', '.mvn', 'src', 'experimentos', 'contratos') {
        $origem = Join-Path $raiz $item
        if (Test-Path $origem) { Copy-Item -Recurse $origem $destino }
    }
}

# Devolve um hashtable "arquivo#classe#teste" -> passou | falhou | ignorado.
function Executar-Testes([string]$pasta) {
    $ErrorActionPreference = 'Continue'
    $relatorios = Join-Path $pasta 'target\surefire-reports'
    $saida = & (Join-Path $pasta 'mvnw.cmd') -B -f (Join-Path $pasta 'pom.xml') -Pexperimentos test `
        '-Dtest=com.estudo.checkout.experimentos.oraculo.**' '-Dsurefire.failIfNoSpecifiedTests=false' `
        '-Dmaven.test.failure.ignore=true' 2>&1
    if (-not (Test-Path $relatorios)) {
        $saida | Select-String 'ERROR' | Select-Object -First 15 | ForEach-Object { Write-Host "   $_" -ForegroundColor DarkYellow }
        throw "Os testes não rodaram em $pasta"
    }
    $resultados = @{}
    foreach ($arquivo in Get-ChildItem $relatorios -Filter 'TEST-*.xml') {
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

Write-Host 'Rodando no código correto...' -ForegroundColor Cyan
$pastaCorreto = Join-Path $pastaTrabalho 'correto'
Copiar-Projeto $pastaCorreto
$correto = Executar-Testes $pastaCorreto

Write-Host 'Rodando no código com bugs...' -ForegroundColor Cyan
$pastaBugs = Join-Path $pastaTrabalho 'com-bugs'
Copiar-Projeto $pastaBugs
Copy-Item (Join-Path $PSScriptRoot 'codigo-com-bugs\PedidoService.java') `
    (Join-Path $pastaBugs 'src\main\java\com\estudo\checkout\pedido\') -Force
Copy-Item (Join-Path $PSScriptRoot 'codigo-com-bugs\PagamentosClient.java') `
    (Join-Path $pastaBugs 'src\main\java\com\estudo\checkout\integracao\pagamentos\') -Force
$comBugs = Executar-Testes $pastaBugs

$linhas = @('# Resultado do experimento do oráculo', '',
    "Gerado por ``experimentos/oraculo/avaliar-oraculo.ps1`` em $(Get-Date -Format 'yyyy-MM-dd HH:mm').", '',
    '| Suíte | Testes | Pegam os bugs | Confirmam os bugs | Neutros | Quebrados |',
    '|-------|-------:|--------------:|------------------:|--------:|----------:|')
$detalhes = @('', '## Testes que dependem dos bugs', '')
$suites = $correto.Keys | ForEach-Object { if ($_ -match '\.oraculo\.(v\d+)\.') { $Matches[1] } } | Sort-Object -Unique
foreach ($suite in $suites) {
    $testes = @($correto.Keys | Where-Object { $_ -match "\.oraculo\.$suite\." } | Sort-Object)
    $pegam = @($testes | Where-Object { $correto[$_] -eq 'passou' -and $comBugs[$_] -eq 'falhou' })
    $confirmam = @($testes | Where-Object { $correto[$_] -eq 'falhou' -and $comBugs[$_] -eq 'passou' })
    $neutros = @($testes | Where-Object { $correto[$_] -eq 'passou' -and $comBugs[$_] -eq 'passou' })
    $quebrados = @($testes | Where-Object { $correto[$_] -eq 'falhou' -and $comBugs[$_] -eq 'falhou' })
    $linhas += "| oraculo-$suite | $($testes.Count) | $($pegam.Count) | $($confirmam.Count) | $($neutros.Count) | $($quebrados.Count) |"
    $detalhes += "### oraculo-$suite", ''
    foreach ($t in $pegam) { $detalhes += "- PEGA: ``$(($t -split '#')[2])`` ($(($t -split '#')[1]))" }
    foreach ($t in $confirmam) { $detalhes += "- CONFIRMA: ``$(($t -split '#')[2])`` ($(($t -split '#')[1]))" }
    foreach ($t in $quebrados) { $detalhes += "- QUEBRADO: ``$(($t -split '#')[2])`` ($(($t -split '#')[1]))" }
    $detalhes += ''
}
$linhas += $detalhes
[System.IO.File]::WriteAllLines((Join-Path $PSScriptRoot 'resultado.md'), $linhas, $utf8SemBom)
$linhas | Write-Host
