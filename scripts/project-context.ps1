. "$PSScriptRoot/lib/common.ps1"
. "$PSScriptRoot/lib/check-registry.ps1"
Assert-ProjectRoot
Write-Host "项目根：$script:ProjectRoot"
& git -C $script:ProjectRoot status --short --branch
& git -C $script:ProjectRoot log -1 --oneline
Write-Host "已实现检查节点：$($script:CheckRegistry.Keys -join '、')"
foreach ($entry in @('start.cmd', 'stop.cmd', 'setup-demo.cmd', 'setup-local-model.cmd', 'import-data.cmd', 'check.cmd')) {
    Write-Host "$entry ：$(if (Test-Path -LiteralPath (Join-Path $script:ProjectRoot $entry)) { '存在' } else { '待实现' })"
}
Write-Host "本机 .env ：$(if (Test-Path -LiteralPath (Join-Path $script:ProjectRoot '.env')) { '存在（不显示内容）' } else { '尚未生成' })"
if (Get-Command docker -ErrorAction SilentlyContinue) {
    & docker --version
    & docker compose version
    & docker info --format '{{.ServerVersion}}' 2>$null
    if ($LASTEXITCODE -ne 0) { Write-Host 'Docker 引擎不可用。' }
    if (Test-Path -LiteralPath (Join-Path $script:ProjectRoot 'ops/compose.yml')) {
        & docker compose -p spotlink-next --project-directory $script:ProjectRoot -f "$script:ProjectRoot/ops/compose.yml" ps
    }
} else { Write-Host 'Docker 命令不可用。' }
Write-Host '数据/知识索引状态：尚未实现读取，不以零记录代替未探测。'
