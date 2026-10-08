Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:ProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))

function Assert-ProjectRoot {
    $actualRoot = & git -C $script:ProjectRoot rev-parse --show-toplevel 2>$null
    if ($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath($actualRoot) -ne $script:ProjectRoot) {
        throw '脚本所在目录不是独立项目 Git 根。'
    }
    if (!(Test-Path -LiteralPath (Join-Path $script:ProjectRoot 'docs/SpotLink实施计划-2026-10-08.md'))) {
        throw '缺少唯一实施计划。'
    }
}

function Invoke-Native {
    param([string]$Command, [string[]]$Arguments)
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "子命令 $Command 失败，原始退出码 $LASTEXITCODE。" }
}

function Write-Utf8 {
    param([string]$Path, [string]$Content)
    [IO.File]::WriteAllText($Path, $Content.Replace("`r`n", "`n"), [Text.UTF8Encoding]::new($false))
}
