param([string]$MessageFile = '', [switch]$WorkingTree)
. "$PSScriptRoot/lib/common.ps1"
try {
    Assert-ProjectRoot
    Push-Location $script:ProjectRoot
    try {
        $name = & git config --get user.name
        $email = & git config --get user.email
        foreach ($kind in @('AUTHOR', 'COMMITTER')) {
            $identity = & git var "GIT_${kind}_IDENT"
            if ($LASTEXITCODE -ne 0 -or !$identity.StartsWith("$name <$email> ") -or $name -match '(?i)bot|codex|openai') {
                throw '有效提交身份与仓库人工身份不一致。'
            }
        }
        if ($MessageFile) {
            $title = ([IO.File]::ReadAllText((Join-Path $script:ProjectRoot $MessageFile)) -split "`n")[0].Trim()
            if ($title -notmatch '^(初始化|规范|部署|设计|资源|功能|修复|整理|测试|文档|性能)（[^）]+）：\S.+$') {
                throw '提交标题须使用：类型（范围）：具体动作与结果。'
            }
            $body = [IO.File]::ReadAllText((Join-Path $script:ProjectRoot $MessageFile))
            if ($body -match '(?i)Co-authored-by:|Generated-by:') { throw '提交不应含工具署名。' }
        } else {
            $paths = if ($WorkingTree) { @(& git ls-files --cached --others --exclude-standard) } else { @(& git diff --cached --name-only) }
            foreach ($path in $paths) {
                if ($path -match '(^|/)(\.local|node_modules|target|dist|__pycache__|\.venv|test-results|playwright-report)(/|$)' -or ($path -match '(^|/)coverage(/|$)' -and $path -notmatch '^data/coverage/') -or $path -match '\.(log|dump)$' -or ($path -match '(^|/)\.env($|\.)' -and $path -notmatch '\.example$')) {
                    throw "运行数据或敏感配置不能提交：$path"
                }
                if ($path -match '^data/.*\.(jsonl|csv)$' -or $path -match '^data/.*(manifest.generated|rendered|coverage-report)') {
                    throw "生成数据应写入 .local：$path"
                }
                $absolute = Join-Path $script:ProjectRoot $path
                if (!(Test-Path -LiteralPath $absolute -PathType Leaf)) { continue }
                if ($path -match '\.(md|ps1|java|ts|tsx|py|yml|json|xml|sql)$') {
                    $content = [IO.File]::ReadAllText($absolute)
                    if ($content -match '(?:sk-(?:proj-|ant-)?[A-Za-z0-9_-]{24,}|-----BEGIN (?:RSA |EC )?PRIVATE KEY-----)') {
                        throw "疑似凭证出现在输入文件：$path（内容未打印）"
                    }
                }
            }
            $trackedLocal = @(& git ls-files -- .local)
            if ($trackedLocal.Count -gt 0) { throw '.local 已有受跟踪文件。' }
            & git diff --check
            if ($LASTEXITCODE -ne 0) { throw '工作区格式检查失败。' }
            & git diff --cached --check
            if ($LASTEXITCODE -ne 0) { throw '暂存区格式检查失败。' }
        }
    } finally { Pop-Location }
    Write-Host 'Git 身份、格式及文件范围核验通过。'
    exit 0
} catch {
    Write-Error $_.Exception.Message -ErrorAction Continue
    exit 4
}
