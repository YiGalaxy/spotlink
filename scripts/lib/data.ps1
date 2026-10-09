. "$PSScriptRoot/compose.ps1"

function Get-DataArguments {
    return @('compose', '-p', 'spotlink-next-tools', '--project-directory', $script:ProjectRoot, '-f', "$script:ProjectRoot/ops/compose.tools.yml", 'run', '--rm', '--no-deps', 'data-tools')
}

function Assert-DataOptions {
    param([string]$Dataset, [string]$Batch)
    if ($Dataset -notin @('minimal', 'acceptance', 'performance')) { throw '数据包无效；完整 demo 待 C37 实现，请使用 minimal。' }
    if ($Batch -notmatch '^[a-z0-9][a-z0-9-]{0,63}$') { throw '批次名无效。' }
}

function Invoke-DataPreparation {
    param([string]$Dataset, [string]$Batch)
    Assert-DataOptions $Dataset $Batch
    $arguments = @('node', 'data/generators/generate.mjs', '--dataset', $Dataset, '--batch', $Batch)
    if (Test-Path -LiteralPath "$script:ProjectRoot/.local/data/v1/$Batch/manifest.json") { $arguments += '--resume' }
    & docker @(Get-DataArguments) @arguments
    if ($LASTEXITCODE -ne 0) { throw '数据准备失败；已有不兼容批次不能覆盖，请指定新批次并检查版本。' }
}

function Assert-DataPackage {
    param([string]$Dataset, [string]$Batch)
    Assert-DataOptions $Dataset $Batch
    $file = "$script:ProjectRoot/.local/data/v1/$Batch/manifest.json"
    if (!(Test-Path -LiteralPath $file)) { throw '数据包不存在；先执行 scripts/data/prepare-dataset.ps1。' }
    $manifest = [IO.File]::ReadAllText($file) | ConvertFrom-Json
    if ($manifest.dataset -ne $Dataset) { throw '批次与请求的数据包不一致。' }
    & docker @(Get-DataArguments) node data/generators/generate.mjs --verify ".local/data/v1/$Batch"
    if ($LASTEXITCODE -ne 0) { throw '数据包校验未通过；禁止导入损坏或与规则不一致的数据。' }
}

function Invoke-DataCli {
    param($Context, [string[]]$Arguments)
    [IO.Directory]::CreateDirectory("$script:ProjectRoot/.local/logs") | Out-Null
    $log = "$script:ProjectRoot/.local/logs/data-$($Context.ProjectName)-$($Arguments[0])-$(Get-Date -Format 'yyyyMMdd-HHmmss-fff').log"
    & docker @($Context.Arguments) run --rm --no-deps data-cli data @Arguments | Tee-Object -FilePath $log
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        $exception = [Exception]::new("数据作业失败，退出码 $code；查看 data-status.cmd 依据数据库台账确认结果。")
        $exception.Data['ExitCode'] = $code
        throw $exception
    }
    Write-Host "[日志] $log"
}

function Invoke-DataMaintenance {
    param($Context, [scriptblock]$Operation)
    [IO.Directory]::CreateDirectory("$script:ProjectRoot/.local") | Out-Null
    $lock = $null
    $running = @()
    try {
        try { $lock = [IO.File]::Open("$script:ProjectRoot/.local/data-operation.lock", [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::Write, [IO.FileShare]::None) }
        catch {
            $exception = [Exception]::new('本地另一个迁移/导入/验证正在执行，请等待该操作完成。')
            $exception.Data['ExitCode'] = 5
            throw $exception
        }
        $services = @(& docker @($Context.Arguments) ps --status running --services)
        if ($LASTEXITCODE -ne 0) { throw '无法确认业务服务运行状态；导入未开始。' }
        $running = @($services | Where-Object { $_ -in @('backend', 'frontend') })
        if ($running.Count -gt 0) {
            Write-Host '[维护] 暂停前后端，阻止写入、定时扫描与异步通知竞争。'
            Invoke-ProjectCompose $Context (@('stop') + $running)
        }
        & $Operation
    } finally {
        try {
            if ($null -ne $lock -and $running.Count -gt 0) {
                Write-Host '[维护] 恢复原来运行的业务服务。'
                Invoke-ProjectCompose $Context (@('up', '-d', '--no-deps', '--wait', '--wait-timeout', '180') + $running)
            }
        } finally { if ($null -ne $lock) { $lock.Dispose() } }
    }
}

function Invoke-DataStep {
    param([string]$Step, [string]$Dataset = 'minimal', [string]$Batch = 'minimal-v1-20261009',
          [string]$Scope = 'runtime', [string]$ProjectName = 'spotlink-next', [string]$EnvFile = '.env', [switch]$Build)
    try { Assert-DockerReady } catch {
        $exception = [Exception]::new($_.Exception.Message)
        $exception.Data['ExitCode'] = 3
        throw $exception
    }
    $context = Get-ComposeContext -ProjectName $ProjectName -EnvFile $EnvFile -Initialize:($Step -in @('all', 'environment', 'infra'))
    Assert-DataOptions $Dataset $Batch
    Invoke-ProjectCompose $context @('config', '--quiet')
    switch ($Step) {
        'environment' { Write-Host '[依赖] Docker 与本地配置检查通过。'; return }
        'prepare' { Invoke-DataPreparation $Dataset $Batch; return }
        'infra' { Invoke-ProjectCompose $context @('up', '-d', '--wait', 'mysql', 'redis'); return }
        'status' { Invoke-DataCli $context @('status'); return }
    }
    if ($Build) { Invoke-ProjectCompose $context @('build', 'backend', 'frontend') }
    $null = & docker image inspect spotlink-next-backend:local --format '{{.Id}}' 2>$null
    if ($LASTEXITCODE -ne 0) { throw '缺少数据作业镜像；先执行 setup-demo.cmd 或 import-data.cmd -Build。' }
    Invoke-ProjectCompose $context @('up', '-d', '--wait', '--wait-timeout', '180', 'mysql', 'redis')
    if ($Step -eq 'all') { Invoke-DataPreparation $Dataset $Batch }
    if ($Step -ne 'migrate') { Assert-DataPackage $Dataset $Batch }
    if ($Step -notin @('all','migrate','business','knowledge','verify')) { throw '未知数据步骤。' }
    Invoke-DataMaintenance $context {
        if ($Step -in @('all', 'migrate')) { Invoke-DataCli $context @('migrate') }
        if ($Step -in @('all', 'business')) { Invoke-DataCli $context @('business', $Batch) }
        if ($Step -in @('all', 'knowledge')) { Invoke-DataCli $context @('knowledge', $Batch) }
        if ($Step -in @('all', 'verify')) { Invoke-DataCli $context @('verify', $Batch, $Scope) }
    }
    Write-Host "[数据] $Step 完成；数据包 $Dataset，批次 $Batch。"
    if ($Step -eq 'all') {
        Write-Host '[账号] min_seller01 / min_buyer01 / min_empty01 / min_admin / min_auditor，演示口令 Admin@123。'
        Write-Host '[模型] 未发起模型调用；文本/关键词已导入，向量未构建时仍明确标记。'
    }
}
