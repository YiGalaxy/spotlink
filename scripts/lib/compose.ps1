. "$PSScriptRoot/common.ps1"

# 只允许操作 SpotLink 自己的 Compose 项目，测试与日常环境使用独立卷。
function Get-ComposeContext {
    param([string]$ProjectName = 'spotlink-next', [string]$EnvFile = '.env', [switch]$Initialize)
    Assert-ProjectRoot
    if ($ProjectName -notmatch '^spotlink-next(?:-[a-z0-9]+)*$') { throw 'Compose 项目名必须位于 spotlink-next 范围。' }
    $envPath = if ([IO.Path]::IsPathRooted($EnvFile)) { [IO.Path]::GetFullPath($EnvFile) } else { [IO.Path]::GetFullPath((Join-Path $script:ProjectRoot $EnvFile)) }
    if (!$envPath.StartsWith($script:ProjectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw '配置文件必须位于当前项目内。' }
    if (!(Test-Path -LiteralPath $envPath)) {
        if (!$Initialize) { throw '缺少本地配置，请先执行 start.cmd。' }
        $parent = Split-Path -Parent $envPath
        [IO.Directory]::CreateDirectory($parent) | Out-Null
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        try {
            $values = @{}
            foreach ($key in @('MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'REDIS_PASSWORD', 'SPOTLINK_JWT_SECRET', 'SPOTLINK_ADVISOR_CONFIG_SECRET')) {
                $bytes = New-Object byte[] 32
                $random.GetBytes($bytes)
                $values[$key] = if ($key -in @('SPOTLINK_JWT_SECRET', 'SPOTLINK_ADVISOR_CONFIG_SECRET')) { [Convert]::ToBase64String($bytes) } else { -join ($bytes | ForEach-Object { $_.ToString('x2') }) }
            }
        } finally { $random.Dispose() }
        $template = [IO.File]::ReadAllText((Join-Path $script:ProjectRoot '.env.example'))
        foreach ($key in $values.Keys) { $template = [regex]::Replace($template, "(?m)^$key=.*$", "$key=$($values[$key])") }
        Write-Utf8 -Path $envPath -Content $template
        Write-Host '已生成本项目本地配置；后续启动保留同一凭证。'
    }
    $settings = @{}
    foreach ($line in [IO.File]::ReadAllLines($envPath)) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $settings[$Matches[1]] = $Matches[2].Trim() }
    }
    foreach ($key in @('MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'REDIS_PASSWORD', 'SPOTLINK_JWT_SECRET', 'BACKEND_PORT', 'FRONTEND_PORT')) {
        if (!$settings.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($settings[$key]) -or $settings[$key] -match '^replace-|^change-me|^GENERATE') { throw "本地配置 $key 缺失或仍为占位值。" }
    }
    foreach ($key in @('BACKEND_PORT', 'FRONTEND_PORT')) {
        if ($settings[$key] -notmatch '^\d+$' -or [int]$settings[$key] -lt 1024 -or [int]$settings[$key] -gt 65535) { throw "$key 端口无效。" }
    }
    if ($settings.BACKEND_PORT -eq $settings.FRONTEND_PORT) { throw '前后端端口不能相同。' }
    try { $secret = [Convert]::FromBase64String($settings.SPOTLINK_JWT_SECRET) } catch { throw 'JWT 密钥必须是 Base64 编码。' }
    if ($secret.Length -lt 32) { throw 'JWT 密钥至少需要 256 位。' }
    $arguments = @('compose', '-p', $ProjectName, '--project-directory', $script:ProjectRoot, '--env-file', $envPath, '-f', (Join-Path $script:ProjectRoot 'ops/compose.yml'))
    if ($settings.ContainsKey('SPOTLINK_LOCAL_AI_GPU') -and $settings['SPOTLINK_LOCAL_AI_GPU'] -eq 'true') {
        $arguments += @('-f', (Join-Path $script:ProjectRoot 'ops/compose.gpu.yml'))
    }
    return @{ Arguments = $arguments; Settings = $settings; ProjectName = $ProjectName; EnvFile = $envPath }
}

function Assert-DockerReady {
    if (!(Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker 命令不可用，请启动 Docker Desktop。' }
    $null = & docker info --format '{{.ServerVersion}}' 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Docker 引擎未就绪，请启动 Docker Desktop。' }
}

function Assert-PlatformReady {
    param($Context)
    $health = Invoke-RestMethod "http://127.0.0.1:$($Context.Settings.BACKEND_PORT)/actuator/health" -TimeoutSec 10
    if ($health.status -ne 'UP') { throw '后端尚未就绪。' }
    $proxy = Invoke-RestMethod "http://127.0.0.1:$($Context.Settings.FRONTEND_PORT)/actuator/health" -TimeoutSec 10
    if ($proxy.status -ne 'UP') { throw 'Nginx 后端代理尚未就绪。' }
    $root = Invoke-WebRequest "http://127.0.0.1:$($Context.Settings.FRONTEND_PORT)/" -UseBasicParsing -TimeoutSec 10
    if ($root.StatusCode -ne 200 -or $root.Content -notmatch 'id="root"') { throw '前端应用入口不可用。' }
}

function Invoke-ProjectCompose {
    param($Context, [string[]]$CommandArguments)
    & docker @($Context.Arguments) @CommandArguments
    if ($LASTEXITCODE -ne 0) { throw "Compose 操作失败，原始退出码 $LASTEXITCODE。" }
}
