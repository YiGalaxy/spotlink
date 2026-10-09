param([switch]$NoPause, [string]$ProjectName = 'spotlink-next', [string]$EnvFile = '.env')
& "$PSScriptRoot/start.ps1" -Build -ProjectName $ProjectName -EnvFile $EnvFile -NoPause:$NoPause
exit $LASTEXITCODE
