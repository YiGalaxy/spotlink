# 仅登记实际实现的检查。新增节点时同时补充具体执行器及验收选择器。
$script:CheckRegistry = [ordered]@{
    C00 = @{ Required = @('static'); Checks = @{ static = 'static' } }
    C01 = @{ Required = @('static', 'unit'); Checks = @{ static = 'static'; unit = 'entry-tests' } }
    C02 = @{ Required = @('static', 'ops'); Checks = @{ static = 'static'; ops = 'compose-config' } }
    C03 = @{ Required = @('static', 'unit'); Checks = @{ static = 'static'; unit = 'backend-package' } }
    C04 = @{ Required = @('static', 'unit'); Checks = @{ static = 'static'; unit = 'frontend-build' } }
}
$script:ValidCheckModes = @('task', 'full', 'static', 'unit', 'integration', 'dataset', 'ai-offline', 'rag', 'e2e', 'visual', 'ops', 'ai-live')
