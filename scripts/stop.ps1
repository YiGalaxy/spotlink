param([switch]$NoPause, [string]$ProjectName = 'spotlink-next', [string]$EnvFile = '.env')
. "$PSScriptRoot/lib/compose.ps1"
try {
    Assert-DockerReady
    $context = Get-ComposeContext -ProjectName $ProjectName -EnvFile $EnvFile
    Invoke-ProjectCompose $context @('down', '--timeout', '30')
    Write-Host '[已停止] 本项目容器和网络已停止，数据库与缓存数据卷保留。'
    exit 0
} catch { Write-Error $_.Exception.Message -ErrorAction Continue; exit 3 }
