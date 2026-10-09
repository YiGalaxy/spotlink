param([switch]$Browser)
. "$PSScriptRoot/../lib/data.ps1"
$projectName = 'spotlink-next-eval-early'
$envFile = '.local/config/eval-early.env'
$context = Get-ComposeContext -ProjectName $projectName -EnvFile $envFile -Initialize
$content = [IO.File]::ReadAllText($context.EnvFile)
$content = [regex]::Replace($content,'(?m)^BACKEND_PORT=.*$','BACKEND_PORT=58081')
$content = [regex]::Replace($content,'(?m)^FRONTEND_PORT=.*$','FRONTEND_PORT=58080')
# 独立配置只允许替身；不从日常 env 读取模型凭证。
foreach ($setting in @('SPOTLINK_ADVISOR_API_KEY=offline-evaluation-only', 'SPOTLINK_ADVISOR_BASE_URL=http://eval-model:8080/v1', 'SPOTLINK_ADVISOR_MODEL=offline-early-v1', 'SPOTLINK_LOCAL_AI_GPU=false')) {
    $key = $setting.Split('=')[0]
    $content = [regex]::Replace($content, "(?m)^$key=.*$", $setting)
}
Write-Utf8 $context.EnvFile $content
$context = Get-ComposeContext -ProjectName $projectName -EnvFile $envFile
$arguments = $context.Arguments + @('-f', "$script:ProjectRoot/ops/compose.eval.yml")
$code = 0
try {
    Assert-DockerReady
    & docker @arguments build backend
    if ($LASTEXITCODE -ne 0) { throw '离线顾问镜像构建失败。' }
    Invoke-DataStep -Step all -ProjectName $projectName -EnvFile $envFile
    & docker @arguments up -d --wait --wait-timeout 240 backend eval-model
    if ($LASTEXITCODE -ne 0) { throw '离线顾问服务未就绪。' }
    & docker @arguments run --rm --no-deps eval-runner
    if ($LASTEXITCODE -ne 0) { throw '早期离线基线未通过，查看 .local/evals 中的断言分类。' }
    if ($Browser) {
        & docker @arguments up -d --wait --wait-timeout 180 frontend
        if ($LASTEXITCODE -ne 0) { throw '早期顾问页面未就绪。' }
        [IO.Directory]::CreateDirectory("$script:ProjectRoot/.local/browser") | Out-Null
        & docker compose -p spotlink-next-tools --project-directory $script:ProjectRoot -f "$script:ProjectRoot/ops/compose.tools.yml" run --rm --no-deps browser-tools sh /workspace/scripts/tests/advisor-early-browser.sh
        if ($LASTEXITCODE -ne 0) { throw '导入数据到顾问页面的浏览器验收失败。' }
    }
} catch { Write-Error $_.Exception.Message -ErrorAction Continue; $code = 4 }
finally {
    & docker @arguments down
    if ($LASTEXITCODE -ne 0) { $code = 4 }
}
exit $code
