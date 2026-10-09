param(
    [ValidateSet('environment','infra','migrate','prepare','business','knowledge','verify','status','all')][string]$Step = 'all',
    [string]$Dataset = 'minimal', [string]$Batch = 'minimal-v1-20261009',
    [ValidateSet('initial','runtime')][string]$Scope = 'runtime',
    [string]$ProjectName = 'spotlink-next', [string]$EnvFile = '.env',
    [switch]$Build, [switch]$Resume, [switch]$NoPause
)
. "$PSScriptRoot/../lib/data.ps1"
$code = 0
try {
    Invoke-DataStep -Step $Step -Dataset $Dataset -Batch $Batch -Scope $Scope -ProjectName $ProjectName -EnvFile $EnvFile -Build:$Build
} catch {
    $code = if ($_.Exception.Data.Contains('ExitCode')) { [int]$_.Exception.Data['ExitCode'] } else { 4 }
    Write-Error $_.Exception.Message -ErrorAction Continue
    Write-Host '[未完成] 已提交阶段以数据库台账为准；当前失败阶段不会覆盖人工数据。修复后用相同入口重试。'
}
if ($code -ne 0 -and !$NoPause) { Read-Host '按回车关闭窗口' | Out-Null }
exit $code
