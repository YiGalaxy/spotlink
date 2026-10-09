. "$PSScriptRoot/../lib/data.ps1"
$context = Get-ComposeContext -ProjectName 'spotlink-next-data-initial-check' -EnvFile '.local/config/data-initial-check.env' -Initialize
try {
    # 独立项目、配置及卷；不接触用户的日常库，也不下载模型。
    Invoke-DataStep -Step all -ProjectName $context.ProjectName -EnvFile $context.EnvFile -Scope runtime
    Invoke-DataStep -Step verify -ProjectName $context.ProjectName -EnvFile $context.EnvFile -Scope initial
    Invoke-DataStep -Step all -ProjectName $context.ProjectName -EnvFile $context.EnvFile
    Invoke-DataCli $context @('status')
    foreach ($name in @('check-environment','start-infra','migrate-schema','prepare-dataset','import-business','import-knowledge','verify-dataset')) {
        if (!(Test-Path -LiteralPath "$script:ProjectRoot/scripts/data/$name.cmd") -or !(Test-Path -LiteralPath "$script:ProjectRoot/scripts/data/$name.ps1")) { throw "缺少独立步骤入口 $name" }
    }
    Write-Host '[通过] 独立数据库初始化、initial/runtime 校验、重试跳过、数据库台账、无模型知识导入与分步入口。'
    exit 0
} catch {
    Write-Error $_.Exception.Message -ErrorAction Continue
    exit 4
} finally { Invoke-ProjectCompose $context @('stop', 'mysql', 'redis') }
