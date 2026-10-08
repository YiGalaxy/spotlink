. "$PSScriptRoot/lib/common.ps1"
Assert-ProjectRoot
& docker compose -p spotlink-next --project-directory $script:ProjectRoot -f "$script:ProjectRoot/ops/compose.yml" ps
