param([switch]$NoPause)
. "$PSScriptRoot/lib/common.ps1"
. "$PSScriptRoot/lib/check-registry.ps1"
try {
    Assert-ProjectRoot
    $plan = [IO.File]::ReadAllText((Join-Path $script:ProjectRoot 'docs/SpotLink实施计划-2026-10-08.md'))
    $expected = @(0..74 | ForEach-Object { 'C{0:D2}' -f $_ })
    foreach ($section in @('## 十二、逐功能实施与提交清单', '### 14.6 75 个节点的文件范围与检查模式')) {
        $start = $plan.IndexOf($section)
        if ($start -lt 0) { throw "缺少节点表：$section" }
        $end = $plan.IndexOf("`n##", $start + $section.Length)
        if ($end -lt 0) { $end = $plan.Length }
        $content = $plan.Substring($start, $end - $start)
        $nodes = @([regex]::Matches($content, '(?m)^\| (C\d{2}) \|') | ForEach-Object { $_.Groups[1].Value })
        if (($nodes -join ',') -ne ($expected -join ',')) { throw "$section 节点不唯一或不连续。" }
    }
    foreach ($node in $script:CheckRegistry.Keys) {
        $entry = $script:CheckRegistry[$node]
        foreach ($mode in $entry.Required) {
            if ($mode -notin $script:ValidCheckModes -or !$entry.Checks.ContainsKey($mode)) {
                throw "$node 的必需检查 $mode 缺少登记。"
            }
        }
    }
    foreach ($path in @('check.cmd', 'scripts/check.ps1', 'scripts/project-context.ps1', 'scripts/git-guard.ps1', 'scripts/tests/check-entry.tests.ps1')) {
        if (!(Test-Path -LiteralPath (Join-Path $script:ProjectRoot $path))) { throw "已登记入口缺失：$path" }
    }
    foreach ($path in @('README.md', 'docs/来源记录.md', 'docs/开发规范.md')) {
        $file = Join-Path $script:ProjectRoot $path
        if (!(Test-Path -LiteralPath $file)) { continue }
        foreach ($link in [regex]::Matches([IO.File]::ReadAllText($file), '\]\(([^)#]+)(?:#[^)]*)?\)')) {
            $target = $link.Groups[1].Value
            if ($target -match '^[a-z]+://' -or $target.StartsWith('#')) { continue }
            $resolved = Join-Path (Split-Path -Parent $file) $target
            if (!(Test-Path -LiteralPath $resolved)) { throw "$path 引用不存在的文件：$target" }
        }
    }
    Write-Host "两张任务表各 75 节点唯一连续，已登记 $($script:CheckRegistry.Count) 个节点；其余待实现。"
    exit 0
} catch {
    Write-Error $_.Exception.Message -ErrorAction Continue
    exit 4
}
