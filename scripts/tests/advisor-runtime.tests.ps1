param()
. "$PSScriptRoot/../lib/compose.ps1"
$projectName = 'spotlink-next-advisor-test'
$envFile = '.local/advisor-test.env'
$context = Get-ComposeContext -ProjectName $projectName -EnvFile $envFile -Initialize
$content = [IO.File]::ReadAllText($context.EnvFile)
$content = [regex]::Replace($content,'(?m)^BACKEND_PORT=.*$','BACKEND_PORT=48081')
$content = [regex]::Replace($content,'(?m)^FRONTEND_PORT=.*$','FRONTEND_PORT=48080')
Write-Utf8 $context.EnvFile $content
$context = Get-ComposeContext -ProjectName $projectName -EnvFile $envFile
$composeArgs = $context.Arguments + @('-f', "$script:ProjectRoot/ops/compose.advisor-test.yml")
try {
    & docker @composeArgs build backend frontend
    if ($LASTEXITCODE -ne 0) { throw '模型配置隔离镜像构建失败。' }
    & docker @composeArgs up -d --wait --wait-timeout 240
    if ($LASTEXITCODE -ne 0) { throw '模型配置隔离服务未就绪。' }
    Assert-PlatformReady $context
    # 仅清理本检查创建的配置记录，保证重复执行从默认配置开始，不清理数据卷。
    'DELETE FROM t_advisor_model_settings;' | & docker @composeArgs exec -T mysql sh -c 'MYSQL_PWD=$MYSQL_PASSWORD mysql -u$MYSQL_USER $MYSQL_DATABASE'
    if ($LASTEXITCODE -ne 0) { throw '检查配置场景准备失败。' }
    [IO.Directory]::CreateDirectory("$script:ProjectRoot/.local/browser") | Out-Null
    & docker compose -p spotlink-next-tools --project-directory $script:ProjectRoot -f "$script:ProjectRoot/ops/compose.tools.yml" run --rm --no-deps browser-tools sh /workspace/scripts/tests/advisor-browser.sh
    if ($LASTEXITCODE -ne 0) { throw '模型后台浏览器回归失败。' }
    Write-Host '模型后台浏览器回归通过；真实模型未调用。'
    exit 0
} catch { Write-Error $_.Exception.Message -ErrorAction Continue; exit 4 }
finally { & docker @composeArgs down }
