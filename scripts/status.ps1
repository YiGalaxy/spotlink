param([switch]$NoPause, [string]$ProjectName = 'spotlink-next', [string]$EnvFile = '.env')
. "$PSScriptRoot/lib/compose.ps1"
try {
    Assert-DockerReady
    $context = Get-ComposeContext -ProjectName $ProjectName -EnvFile $EnvFile
    Invoke-ProjectCompose $context @('ps', '-a')
    Assert-PlatformReady $context
    Write-Host '[就绪] 数据库、缓存、应用及代理可访问。'
    exit 0
} catch { Write-Error $_.Exception.Message -ErrorAction Continue; exit 3 }
