param([switch]$Build, [switch]$NoPause, [string]$ProjectName = 'spotlink-next', [string]$EnvFile = '.env', [int]$WaitSeconds = 180)
. "$PSScriptRoot/lib/compose.ps1"
try {
    if ($WaitSeconds -lt 10 -or $WaitSeconds -gt 900) { throw '等待时间须在 10–900 秒之间。' }
    Assert-DockerReady
    $context = Get-ComposeContext -ProjectName $ProjectName -EnvFile $EnvFile -Initialize
    Invoke-ProjectCompose $context @('config', '--quiet')
    if ($Build) {
        Write-Host '正在从源码构建容器镜像，首次使用需要下载依赖。'
        Invoke-ProjectCompose $context @('build', 'backend', 'frontend')
    }
    Write-Host "正在启动 $ProjectName，并等待全部服务健康检查。"
    Invoke-ProjectCompose $context @('up', '-d', '--wait', '--wait-timeout', "$WaitSeconds")
    Assert-PlatformReady $context
    Write-Host "[就绪] 现货通：http://127.0.0.1:$($context.Settings.FRONTEND_PORT)"
    Write-Host '[模型] 未配置聊天模型时，业务可用；向量默认不构建。'
    exit 0
} catch {
    Write-Error $_.Exception.Message -ErrorAction Continue
    Write-Host '[未就绪] 请执行 status.cmd；仅查看本项目日志：scripts/logs.ps1。'
    exit 3
}
