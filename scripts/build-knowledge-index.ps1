param([string]$Username = 'admin', [Security.SecureString]$Password, [int]$BatchSize = 10, [switch]$StatusOnly, [switch]$NoPause)
. "$PSScriptRoot/lib/compose.ps1"
try {
    if ($BatchSize -lt 1 -or $BatchSize -gt 50) { throw '每批数量须为 1–50。' }
    $context = Get-ComposeContext
    $base = 'http://127.0.0.1:' + $context.Settings.BACKEND_PORT
    if ($null -eq $Password) { $Password = Read-Host '请输入有知识库管理权限的账号密码' -AsSecureString }
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password)
    try {
        $payload = @{ username=$Username; password=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) } | ConvertTo-Json -Compress
        $login = Invoke-RestMethod "$base/api/auth/login" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($payload)) -TimeoutSec 20
        if ($login.code -ne 0) { throw '登录失败，请检查账号和权限。' }
    } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $payload = $null }
    $headers = @{ Authorization='Bearer ' + $login.data.accessToken }
    $login = $null
    $stats = Invoke-RestMethod "$base/api/admin/knowledge/stats" -Headers $headers -TimeoutSec 20
    if ($stats.code -ne 0) { throw '知识库状态读取失败。' }
    if (!$StatusOnly) {
        if (!$stats.data.embeddingEnabled) { throw '向量服务尚未启用，请先运行 setup-knowledge-model.cmd。' }
        while ($stats.data.pending -gt 0) {
            $result = Invoke-RestMethod "$base/api/admin/knowledge/embed-pending?limit=$BatchSize" -Method Post -Headers $headers -TimeoutSec 600
            if ($result.code -ne 0 -or $result.data.embedded -eq 0) { throw '本批未成功建向量。已完成分块保留；检查模型服务后重新运行。' }
            $stats = @{ data=$result.data.stats }
            Write-Host "当前模型有效向量：$($stats.data.embedded)，待处理：$($stats.data.pending)。"
        }
    }
    [IO.Directory]::CreateDirectory("$script:ProjectRoot/.local/knowledge") | Out-Null
    Write-Utf8 "$script:ProjectRoot/.local/knowledge/index-status.json" ($stats.data | ConvertTo-Json)
    Write-Host "模型 $($stats.data.model)，维度 $($stats.data.dimensions)，有效 $($stats.data.embedded)，待处理 $($stats.data.pending)。"
    $headers.Clear()
    exit 0
} catch { Write-Error '索引任务未完成。请检查账号权限、后端及向量服务；已成功分块可以继续使用。' -ErrorAction Continue; exit 3 }
