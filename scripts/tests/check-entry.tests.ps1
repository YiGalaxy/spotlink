. "$PSScriptRoot/../lib/common.ps1"
$cases = @(
    @{ Args = @('-Task', 'C99', '-Mode', 'task'); Expected = 2 },
    @{ Args = @('-Task', 'C00', '-Mode', 'unknown'); Expected = 2 },
    @{ Args = @('-Task', 'C74', '-Mode', 'task'); Expected = 2 },
    @{ Args = @('-Task', 'C00', '-Mode', 'ai-live'); Expected = 2 },
    @{ Args = @('-Task', 'C00', '-Engine', 'unknown'); Expected = 2 },
    @{ Args = @('-Task', 'C00', '-Repeat', '3'); Expected = 2 },
    @{ Args = @('-Mode', 'task'); Expected = 2 }
)
foreach ($case in $cases) {
    $output = & "$script:ProjectRoot/scripts/check.ps1" @($case.Args) -NoPause 2>&1
    $actualExitCode = $LASTEXITCODE
    Write-Host "入口拒绝：$($case.Args -join ' ') -> $actualExitCode"
    if ($actualExitCode -ne $case.Expected) { throw "入口拒绝测试失败：$($case.Args -join ' ')，退出码 $actualExitCode" }
}
Write-Host "入口拒绝测试 $($cases.Count) 项通过，使用系统 PowerShell，无第三方依赖。"
exit 0
