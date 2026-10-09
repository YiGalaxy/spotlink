param([string]$Model = 'bge-m3', [int]$Dimensions = 1024, [switch]$NoPause)
. "$PSScriptRoot/lib/compose.ps1"
try {
    if ($Model -notmatch '^[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}$' -or $Dimensions -lt 1 -or $Dimensions -gt 4096) { throw '向量模型名称或维度无效。' }
    Assert-DockerReady
    $context = Get-ComposeContext -Initialize
    Invoke-ProjectCompose $context @('--profile', 'local-ai', 'up', '-d', '--wait', '--wait-timeout', '180', 'ollama')
    Invoke-ProjectCompose $context @('exec', '-T', 'ollama', 'ollama', 'pull', $Model)
    $content = [IO.File]::ReadAllText($context.EnvFile)
    $values = @{ SPOTLINK_EMBEDDING_ENABLED='true'; SPOTLINK_EMBEDDING_URL='http://ollama:11434'; SPOTLINK_EMBEDDING_MODEL=$Model; SPOTLINK_EMBEDDING_DIMENSIONS=[string]$Dimensions }
    foreach ($name in $values.Keys) {
        if ($content -match "(?m)^$name=") { $content = [regex]::Replace($content, "(?m)^$name=.*$", "$name=$($values[$name])") }
        else { $content = $content.TrimEnd() + "`n$name=$($values[$name])`n" }
    }
    Write-Utf8 $context.EnvFile $content
    Invoke-ProjectCompose $context @('up', '-d', '--wait', '--wait-timeout', '180', 'backend')
    Write-Host '向量服务已启用；聊天模型和后台配置保持原值。运行 build-knowledge-index.cmd 建立索引。'
    exit 0
} catch { Write-Error $_.Exception.Message -ErrorAction Continue; exit 3 }
