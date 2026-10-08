# 仅登记实际实现的检查。新增节点时同时补充具体执行器及验收选择器。
$script:CheckRegistry = [ordered]@{
    C00 = @{ Required = @('static'); Checks = @{ static = 'static' } }
    C01 = @{ Required = @('static', 'unit'); Checks = @{ static = 'static'; unit = 'entry-tests' } }
    C02 = @{ Required = @('static', 'ops'); Checks = @{ static = 'static'; ops = 'compose-config' } }
    C03 = @{ Required = @('static', 'unit', 'integration'); Checks = @{ static = 'static'; unit = 'backend-package'; integration = 'backend-integration' } }
    C04 = @{ Required = @('static', 'unit'); Checks = @{ static = 'static'; unit = 'frontend-build' } }
    C05 = @{ Required = @('static', 'ops', 'e2e'); Checks = @{ static = 'static'; ops = 'compose-runtime'; e2e = 'compose-runtime' } }
    C06 = @{ Required = @('static', 'unit', 'dataset'); Checks = @{ static = 'static'; unit = 'data-unit'; dataset = 'dataset-generation' } }
    C07 = @{ Required = @('static', 'unit', 'integration', 'e2e'); Checks = @{ static = 'static'; unit = 'auth-unit'; integration = 'backend-integration'; e2e = 'auth-browser' } }
    C08 = @{ Required = @('static', 'unit', 'integration', 'dataset', 'e2e'); Checks = @{ static = 'static'; unit = 'auth-unit'; integration = 'backend-integration'; dataset = 'data-unit'; e2e = 'inventory-browser' } }
    C18 = @{ Required = @('static', 'unit', 'visual'); Checks = @{ static = 'static'; unit = 'frontend-build'; visual = 'image-browser' } }
}
$script:ValidCheckModes = @('task', 'full', 'static', 'unit', 'integration', 'dataset', 'ai-offline', 'rag', 'e2e', 'visual', 'ops', 'ai-live')
