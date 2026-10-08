. "$PSScriptRoot/../lib/compose.ps1"
$projectName = 'spotlink-next-c05-test'
$envFile = '.local/c05-test.env'
$context = Get-ComposeContext -ProjectName $projectName -EnvFile $envFile -Initialize
$config = [IO.File]::ReadAllText($context.EnvFile).Replace('BACKEND_PORT=18081', 'BACKEND_PORT=28081').Replace('FRONTEND_PORT=18080', 'FRONTEND_PORT=28080')
Write-Utf8 $context.EnvFile $config
$context = Get-ComposeContext -ProjectName $projectName -EnvFile $envFile

function Read-DataMarker {
    # 密码在容器内部从环境读取，不出现在宿主参数或检查输出中。
    $sql = 'SELECT COUNT(*),SUM(id) FROM t_user; SELECT COUNT(*) FROM flyway_schema_history WHERE success=1; SELECT marker FROM spotlink_ops_probe WHERE id=1;'
    $result = $sql | & docker @($context.Arguments) exec -T mysql sh -c 'MYSQL_PWD=$MYSQL_PASSWORD mysql -u$MYSQL_USER -N $MYSQL_DATABASE'
    if ($LASTEXITCODE -ne 0) { throw '数据保留探测失败。' }
    return $result -join '|'
}

try {
    Assert-DockerReady
    & "$script:ProjectRoot/scripts/start.ps1" -ProjectName $projectName -EnvFile $envFile -NoPause
    if ($LASTEXITCODE -ne 0) { throw '隔离平台首次启动失败。' }
    $mysqlVersion = & docker @($context.Arguments) exec -T mysql mysql --version
    $redisVersion = & docker @($context.Arguments) exec -T redis redis-server --version
    if ($mysqlVersion -notmatch '8\.4\.7' -or $redisVersion -notmatch 'v=7\.4\.2') { throw '运行服务版本不符合固定镜像定义。' }
    'CREATE TABLE IF NOT EXISTS spotlink_ops_probe (id INT PRIMARY KEY, marker VARCHAR(36)); INSERT INTO spotlink_ops_probe VALUES(1,UUID()) ON DUPLICATE KEY UPDATE marker=marker;' | & docker @($context.Arguments) exec -T mysql sh -c 'MYSQL_PWD=$MYSQL_PASSWORD mysql -u$MYSQL_USER $MYSQL_DATABASE'
    if ($LASTEXITCODE -ne 0) { throw '隔离测试标记写入失败。' }
    $before = Read-DataMarker
    $configBytes = [IO.File]::ReadAllText($context.EnvFile)
    & "$script:ProjectRoot/scripts/start.ps1" -ProjectName $projectName -EnvFile $envFile -NoPause
    if ($LASTEXITCODE -ne 0) { throw '隔离平台重复启动失败。' }
    if ([IO.File]::ReadAllText($context.EnvFile) -ne $configBytes -or (Read-DataMarker) -ne $before) { throw '重复启动改写配置或数据。' }
    & "$script:ProjectRoot/scripts/stop.ps1" -ProjectName $projectName -EnvFile $envFile -NoPause
    if ($LASTEXITCODE -ne 0) { throw '停止失败。' }
    $remaining = @(& docker @($context.Arguments) ps -aq)
    if ($LASTEXITCODE -ne 0 -or $remaining.Count -gt 0) { throw '停止后仍存在项目容器。' }
    & "$script:ProjectRoot/scripts/start.ps1" -ProjectName $projectName -EnvFile $envFile -NoPause
    if ($LASTEXITCODE -ne 0 -or (Read-DataMarker) -ne $before) { throw '停止后再次启动未保留数据。' }
    & "$script:ProjectRoot/scripts/stop.ps1" -ProjectName $projectName -EnvFile $envFile -NoPause
    if ($LASTEXITCODE -ne 0) { throw '隔离环境收尾停止失败。' }
    $output = & "$script:ProjectRoot/scripts/status.ps1" -ProjectName $projectName -EnvFile $envFile -NoPause 2>&1
    if ($LASTEXITCODE -eq 0) { throw '停止环境的状态入口错误返回成功。' }
    $invalid = '.local/c05-invalid.env'
    Write-Utf8 (Join-Path $script:ProjectRoot $invalid) ($config -replace '(?m)^SPOTLINK_JWT_SECRET=.*$', 'SPOTLINK_JWT_SECRET=invalid')
    $output = & "$script:ProjectRoot/scripts/start.ps1" -ProjectName $projectName -EnvFile $invalid -NoPause 2>&1
    if ($LASTEXITCODE -eq 0) { throw '错误 JWT 配置被接受。' }
    Write-Host '隔离 Compose：真实版本、健康/代理、重复启动、停止保留数据、失败状态及非法配置检查通过。'
    exit 0
} catch { Write-Error $_.Exception.Message -ErrorAction Continue; exit 4 }
