param(
    [string]$Task = '',
    [string]$Mode = 'task',
    [string]$Engine = 'both',
    [int]$Repeat = 1,
    [switch]$NoPause
)

. "$PSScriptRoot/lib/common.ps1"
. "$PSScriptRoot/lib/check-registry.ps1"

if ($Mode -notin $script:ValidCheckModes -or $Engine -notin @('spring-ai', 'langchain', 'both') -or $Repeat -lt 1) {
    Write-Error '参数无效：请使用已定义的模式、引擎和正整数 Repeat。' -ErrorAction Continue
    exit 2
}
if ($Task -and $Task -notmatch '^C(0[0-9]|[1-6][0-9]|7[0-4])$') {
    Write-Error '任务编号必须是 C00–C74。' -ErrorAction Continue
    exit 2
}
if ($Mode -ne 'full' -and !$Task) {
    Write-Error '单任务检查必须指定 -Task。' -ErrorAction Continue
    exit 2
}
if ($Task -and !$script:CheckRegistry.Contains($Task)) {
    Write-Error "$Task 的检查尚未实现，不能报告通过。" -ErrorAction Continue
    exit 2
}
if ($Repeat -ne 1 -and $Mode -ne 'ai-live') {
    Write-Error 'Repeat 仅供显式 ai-live 使用。' -ErrorAction Continue
    exit 2
}

try {
    Assert-ProjectRoot
    $tasks = @(if ($Mode -eq 'full') { $script:CheckRegistry.Keys } else { $Task })
    $executed = @{}
    foreach ($node in $tasks) {
        $registration = $script:CheckRegistry[$node]
        $modes = if ($Mode -in @('task', 'full')) { $registration.Required } else { @($Mode) }
        foreach ($checkMode in $modes) {
            if (!$registration.Checks.ContainsKey($checkMode)) {
                Write-Error "$node / $checkMode 尚未实现，未执行。" -ErrorAction Continue
                exit 2
            }
            $executor = $registration.Checks[$checkMode]
            if ($executed.ContainsKey($executor)) { continue }
            Write-Host "检查 $node / $checkMode（$executor），引擎范围 $Engine"
            switch ($executor) {
                'static' {
                    & "$PSScriptRoot/verify-plan.ps1"
                    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
                    & "$PSScriptRoot/git-guard.ps1" -WorkingTree
                    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
                }
                'entry-tests' {
                    & "$PSScriptRoot/tests/check-entry.tests.ps1"
                    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
                }
                'compose-config' {
                    $compose = Join-Path $script:ProjectRoot 'ops/compose.yml'
                    & docker compose -p spotlink-next-check --project-directory $script:ProjectRoot -f $compose config --quiet
                    if ($LASTEXITCODE -ne 0) { throw 'Compose 配置校验失败。' }
                    $tools = Join-Path $script:ProjectRoot 'ops/compose.tools.yml'
                    & docker compose -p spotlink-next-tools-check --project-directory $script:ProjectRoot -f $tools config --quiet
                    if ($LASTEXITCODE -ne 0) { throw '工具 Compose 配置校验失败。' }
                }
                'backend-package' {
                    & docker compose -p spotlink-next-tools --project-directory $script:ProjectRoot -f "$script:ProjectRoot/ops/compose.tools.yml" run --rm --no-deps backend-tools mvn -B -ntp -Punit-tests test
                    if ($LASTEXITCODE -ne 0) { throw '容器内后端单元测试失败。' }
                }
                'backend-integration' {
                    & docker compose -p spotlink-next-test --project-directory $script:ProjectRoot -f "$script:ProjectRoot/ops/compose.test.yml" run --rm backend-tests
                    $testCode = $LASTEXITCODE
                    & docker compose -p spotlink-next-test --project-directory $script:ProjectRoot -f "$script:ProjectRoot/ops/compose.test.yml" stop mysql-test redis-test
                    if ($testCode -ne 0) { throw "隔离后端集成测试失败，原始退出码 $testCode。" }
                }
                'frontend-build' {
                    & docker compose -p spotlink-next-tools --project-directory $script:ProjectRoot -f "$script:ProjectRoot/ops/compose.tools.yml" run --rm --no-deps frontend-tools sh -c 'npm ci && npm test && npm run build'
                    if ($LASTEXITCODE -ne 0) { throw '容器内前端测试或构建失败。' }
                }
                'compose-runtime' {
                    & "$PSScriptRoot/tests/compose-runtime.tests.ps1"
                    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
                }
                default {
                    Write-Error "执行器 $executor 不存在。" -ErrorAction Continue
                    exit 2
                }
            }
            $executed[$executor] = $true
        }
    }
    Write-Host "已登记范围检查通过；最高节点 $($tasks[-1])。真实模型未调用。"
    exit 0
} catch {
    Write-Error $_.Exception.Message -ErrorAction Continue
    exit 4
}
