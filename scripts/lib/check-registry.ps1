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
    C09 = @{ Required = @('static', 'unit', 'integration', 'dataset'); Checks = @{ static = 'static'; unit = 'backend-package'; integration = 'backend-integration'; dataset = 'data-runtime' } }
    C10 = @{ Required = @('static', 'unit', 'integration', 'dataset'); Checks = @{ static = 'static'; unit = 'data-unit'; integration = 'backend-integration'; dataset = 'data-runtime' } }
    C11 = @{ Required = @('static', 'ops', 'dataset'); Checks = @{ static = 'static'; ops = 'compose-config'; dataset = 'data-runtime' } }
    C12 = @{ Required = @('static', 'unit', 'integration', 'ai-offline', 'e2e'); Checks = @{ static = 'static'; unit = 'auth-unit'; integration = 'backend-integration'; 'ai-offline' = 'advisor-offline'; e2e = 'advisor-browser' } }
    C13 = @{ Required = @('static', 'unit', 'integration', 'ai-offline'); Checks = @{ static = 'static'; unit = 'backend-package'; integration = 'advisor-memory'; 'ai-offline' = 'advisor-memory' } }
    C14 = @{ Required = @('static', 'unit', 'integration', 'ai-offline', 'dataset'); Checks = @{ static = 'static'; unit = 'backend-package'; integration = 'early-advisor'; 'ai-offline' = 'early-advisor'; dataset = 'data-unit' } }
    C15 = @{ Required = @('static', 'ai-offline', 'e2e', 'visual'); Checks = @{ static = 'static'; 'ai-offline' = 'early-advisor'; e2e = 'early-advisor'; visual = 'early-advisor' } }
    C16 = @{ Required = @('static', 'ai-offline'); Checks = @{ static = 'static'; 'ai-offline' = 'early-advisor' } }
    C17 = @{ Required = @('static', 'unit', 'visual'); Checks = @{ static = 'static'; unit = 'frontend-build'; visual = 'admin-browser' } }
    C18 = @{ Required = @('static', 'unit', 'visual'); Checks = @{ static = 'static'; unit = 'frontend-build'; visual = 'image-browser' } }
    C19 = @{ Required = @('static', 'unit', 'e2e', 'visual'); Checks = @{ static = 'static'; unit = 'frontend-build'; e2e = 'image-browser'; visual = 'advisor-browser' } }
    C20 = @{ Required = @('static', 'unit', 'integration', 'e2e', 'dataset'); Checks = @{ static = 'static'; unit = 'backend-package'; integration = 'admin-authority'; e2e = 'admin-browser'; dataset = 'data-unit' } }
    C21 = @{ Required = @('static', 'unit', 'integration', 'e2e'); Checks = @{ static = 'static'; unit = 'backend-package'; integration = 'inventory-maintenance'; e2e = 'inventory-browser' } }
}
$script:ValidCheckModes = @('task', 'full', 'static', 'unit', 'integration', 'dataset', 'ai-offline', 'rag', 'e2e', 'visual', 'ops', 'ai-live')
