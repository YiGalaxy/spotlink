param([ValidateSet('mysql','redis','backend','frontend')][string]$Service = 'backend', [ValidateRange(1,1000)][int]$Tail = 100, [string]$ProjectName = 'spotlink-next', [string]$EnvFile = '.env')
. "$PSScriptRoot/lib/compose.ps1"
try {
    Assert-DockerReady
    $context = Get-ComposeContext -ProjectName $ProjectName -EnvFile $EnvFile
    Invoke-ProjectCompose $context @('logs', '--no-color', '--tail', "$Tail", $Service)
    exit 0
} catch { Write-Error $_.Exception.Message -ErrorAction Continue; exit 3 }
