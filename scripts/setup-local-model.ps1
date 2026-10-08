param([string]$Model = 'qwen3:4b', [switch]$NoPause)
. "$PSScriptRoot/lib/compose.ps1"
try {
    if ($Model -notmatch '^[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}$') { throw '模型名格式无效。' }
    Assert-DockerReady
    $context = Get-ComposeContext -Initialize
    Write-Host "启动可选 Ollama 服务，并下载 $Model。首次下载需要网络和模型磁盘空间。"
    Invoke-ProjectCompose $context @('--profile', 'local-ai', 'up', '-d', '--wait', '--wait-timeout', '180', 'ollama')
    Invoke-ProjectCompose $context @('exec', '-T', 'ollama', 'ollama', 'pull', $Model)
    # 无云端凭证时才写本地占位配置，保留用户已有 API 配置与数据库凭证。
    if ([string]::IsNullOrWhiteSpace($context.Settings.SPOTLINK_ADVISOR_API_KEY) -or $context.Settings.SPOTLINK_ADVISOR_API_KEY -in @('ollama', 'local', 'not-configured')) {
        $content = [IO.File]::ReadAllText($context.EnvFile)
        $values = @{ SPOTLINK_ADVISOR_API_KEY='ollama'; SPOTLINK_ADVISOR_BASE_URL='http://ollama:11434/v1'; SPOTLINK_ADVISOR_MODEL=$Model }
        foreach ($name in $values.Keys) {
            if ($content -match "(?m)^$name=") { $content = [regex]::Replace($content, "(?m)^$name=.*$", "$name=$($values[$name])") }
            else { $content = $content.TrimEnd() + "`n$name=$($values[$name])`n" }
        }
        Write-Utf8 $context.EnvFile $content
        Invoke-ProjectCompose $context @('up', '-d', '--wait', '--wait-timeout', '180', 'backend')
    }
    Write-Host '本地模型已安装。管理员打开 /admin/model，选择 Ollama 本项目服务；保存后可显式测试连接。'
    Write-Host '若已有后台覆盖配置，.env 不会覆盖它；请在后台选择本地服务或恢复环境配置。'
    exit 0
} catch { Write-Error $_.Exception.Message -ErrorAction Continue; exit 3 }
