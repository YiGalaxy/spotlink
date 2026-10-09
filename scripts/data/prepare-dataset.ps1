param([string]$Dataset='minimal', [string]$Batch='minimal-v1-20261009', [ValidateSet('initial','runtime')][string]$Scope='runtime', [string]$ProjectName='spotlink-next', [string]$EnvFile='.env', [switch]$Resume, [switch]$NoPause)
& "$PSScriptRoot/run-step.ps1" -Step prepare @PSBoundParameters
exit $LASTEXITCODE
