. "$PSScriptRoot/lib/common.ps1"
try { Assert-ProjectRoot; & docker compose -p spotlink-next --project-directory $script:ProjectRoot -f "$script:ProjectRoot/ops/compose.yml" down; exit $LASTEXITCODE } catch { Write-Error $_.Exception.Message; exit 3 }
