<#
.SYNOPSIS
    Copia as classes Java de uma resposta da LLM (Markdown) para experimentos/java, sem alterar a lógica dos testes.

.DESCRIPTION
    Cada bloco ```java que declara uma classe vira um arquivo. A única mudança automática é a linha "package":
    as classes vão para com.estudo.checkout.experimentos.<prompt>.<versao>, para que os testes de experimentos
    diferentes possam ser compilados juntos sem conflito de nomes. Os imports das classes do projeto que antes
    estavam no mesmo pacote são adicionados. Qualquer outro ajuste é feito à mão e anotado no cabeçalho do arquivo.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File experimentos\extrair-testes.ps1 -Resposta experimentos\respostas\1-cenarios-v1.md -Prompt cenarios -Versao v1
#>
param(
    [Parameter(Mandatory)] [string]$Resposta,
    [Parameter(Mandatory)] [string]$Prompt,
    [Parameter(Mandatory)] [string]$Versao
)

$ErrorActionPreference = 'Stop'
$utf8SemBom = New-Object System.Text.UTF8Encoding $false
$raiz = Split-Path $PSScriptRoot -Parent
$pacote = "com.estudo.checkout.experimentos.$Prompt.$Versao"
$destino = Join-Path $PSScriptRoot ("java\" + $pacote.Replace('.', '\'))
New-Item -ItemType Directory -Force $destino | Out-Null

# Classes públicas do projeto, para recolocar os imports que a mudança de pacote quebraria.
$classesDoProjeto = @{}
Get-ChildItem -Recurse -Filter *.java (Join-Path $raiz 'src\main\java') | ForEach-Object {
    $conteudo = [IO.File]::ReadAllText($_.FullName, $utf8SemBom)
    $pacoteDaClasse = [regex]::Match($conteudo, '(?m)^package\s+([\w.]+);').Groups[1].Value
    $classesDoProjeto[$_.BaseName] = "$pacoteDaClasse.$($_.BaseName)"
}

$markdown = [IO.File]::ReadAllText((Resolve-Path $Resposta), $utf8SemBom)
$blocos = [regex]::Matches($markdown, '(?s)```java\r?\n(.*?)\r?\n```')
$gerados = 0
foreach ($bloco in $blocos) {
    $codigo = $bloco.Groups[1].Value
    $declaracao = [regex]::Match($codigo, '(?m)^(?:public\s+|abstract\s+|final\s+)*(?:class|record|interface|enum)\s+(\w+)')
    if (-not $declaracao.Success) { continue }
    $nome = $declaracao.Groups[1].Value

    $pacoteOriginal = [regex]::Match($codigo, '(?m)^package\s+([\w.]+);').Groups[1].Value
    if ($pacoteOriginal) {
        $codigo = [regex]::Replace($codigo, '(?m)^package\s+[\w.]+;', "package $pacote;")
    } else {
        $codigo = "package $pacote;`n`n" + $codigo
    }

    # Classes do pacote original eram visíveis sem import; agora precisam de import explícito.
    # A busca ignora textos e comentários, para "pedido" num DisplayName não virar import de Pedido.
    $somenteCodigo = [regex]::Replace($codigo, '(?s)""".*?"""|"(?:\\.|[^"\\])*"|//[^\n]*|/\*.*?\*/', '')
    $importsNovos = @()
    foreach ($classe in $classesDoProjeto.Keys) {
        $completo = $classesDoProjeto[$classe]
        $pacoteDaClasse = $completo.Substring(0, $completo.LastIndexOf('.'))
        if ($pacoteDaClasse -eq $pacoteOriginal -and $somenteCodigo -cmatch "\b$classe\b" -and $codigo -notmatch "import\s+$([regex]::Escape($completo));") {
            $importsNovos += "import $completo;"
        }
    }
    if ($importsNovos) {
        $codigo = [regex]::Replace($codigo, '(?m)^(package [\w.]+;\r?\n)', "`$1`n" + ($importsNovos -join "`n") + "`n", 1)
    }

    $arquivo = Join-Path $destino "$nome.java"
    if (Test-Path $arquivo) { Write-Warning "$nome.java aparece em mais de um bloco; ficou a última versão." }
    [IO.File]::WriteAllText($arquivo, $codigo + "`n", $utf8SemBom)
    "{0} (pacote original: {1})" -f $nome, $(if ($pacoteOriginal) { $pacoteOriginal } else { 'nenhum' })
    $gerados++
}
"$gerados classe(s) extraída(s) para $destino"
