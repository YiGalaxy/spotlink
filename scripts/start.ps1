param([switch]$Build, [switch]$NoPause, [string]$ProjectName = 'spotlink-next', [string]$EnvFile = '.env', [int]$WaitSeconds = 180)
. "$PSScriptRoot/lib/data.ps1"
try {
    if ($WaitSeconds -lt 10 -or $WaitSeconds -gt 900) { throw '等待时间须在 10–900 秒之间。' }
    Assert-DockerReady
    $context = Get-ComposeContext -ProjectName $ProjectName -EnvFile $EnvFile -Initialize
    Invoke-ProjectCompose $context @('config', '--quiet')
    if ($Build) {
        Write-Host '正在从源码构建容器镜像，首次使用需要下载依赖。'
        Invoke-ProjectCompose $context @('build', 'backend', 'frontend', 'advisor-langchain')
    }
    # 首次演示与日常启动委托同一受控流程；已提交数据只核验，不重置。
    # 后端镜像缺失时仍由项目 Dockerfile 构建，不要求宿主 Java。
    $null = & docker image inspect spotlink-next-backend:local --format '{{.Id}}' 2>$null
    if ($LASTEXITCODE -ne 0) { Invoke-ProjectCompose $context @('build', 'backend') }
    Invoke-DataStep -Step all -ProjectName $ProjectName -EnvFile $EnvFile
    Write-Host "正在启动 $ProjectName，并等待全部服务健康检查。"
    $startArguments = @('up', '-d', '--wait', '--wait-timeout', "$WaitSeconds")
    if ($context.Settings.SPOTLINK_ADVISOR_BASE_URL -eq 'http://ollama:11434/v1' -and $context.Settings.SPOTLINK_ADVISOR_API_KEY -eq 'ollama') {
        $startArguments = @('--profile', 'local-ai') + $startArguments
    }
    Invoke-ProjectCompose $context $startArguments
    Assert-PlatformReady $context
    Write-Host "[就绪] 现货通：http://127.0.0.1:$($context.Settings.FRONTEND_PORT)"
    Write-Host '[模型] 业务已就绪；管理员可在 /admin/model 配置本地或云端模型并显式测试。'
    exit 0
} catch {
    Write-Error $_.Exception.Message -ErrorAction Continue
    Write-Host '[未就绪] 请执行 status.cmd；仅查看本项目日志：scripts/logs.ps1。'
    exit 3
}
