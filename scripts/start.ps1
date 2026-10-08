param([switch]$Build, [switch]$NoPause)
. "$PSScriptRoot/lib/common.ps1"
try {
  Assert-ProjectRoot
  if (!(Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker 命令不可用。' }
  $compose = Join-Path $script:ProjectRoot 'ops/compose.yml'
  $args = @('compose','-p','spotlink-next','--project-directory',$script:ProjectRoot,'-f',$compose,'up','-d')
  if ($Build) { $args += '--build' }
  & docker @args
  if ($LASTEXITCODE -ne 0) { exit 3 }
  Write-Host '平台已启动，正在检查后端健康状态。'
  & docker compose -p spotlink-next --project-directory $script:ProjectRoot -f $compose ps
  exit 0
} catch { Write-Error $_.Exception.Message; exit 3 }
