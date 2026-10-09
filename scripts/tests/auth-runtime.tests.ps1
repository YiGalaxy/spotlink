param([switch]$Mall, [switch]$Inventory, [switch]$Admin, [switch]$Trading)
. "$PSScriptRoot/../lib/compose.ps1"
# Windows PowerShell 的原生程序管道默认 ASCII；SQL 输入必须保留中文。
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$projectName = 'spotlink-next-c07-test'
$envFile = '.local/c07-test.env'
$context = Get-ComposeContext -ProjectName $projectName -EnvFile $envFile -Initialize
$config = [IO.File]::ReadAllText($context.EnvFile).Replace('BACKEND_PORT=18081', 'BACKEND_PORT=38081').Replace('FRONTEND_PORT=18080', 'FRONTEND_PORT=38080')
Write-Utf8 $context.EnvFile $config
$context = Get-ComposeContext -ProjectName $projectName -EnvFile $envFile
try {
    & docker @($context.Arguments) build backend frontend
    if ($LASTEXITCODE -ne 0) { throw '认证检查源码镜像构建失败。' }
    & "$script:ProjectRoot/scripts/start.ps1" -ProjectName $projectName -EnvFile $envFile -NoPause
    if ($LASTEXITCODE -ne 0) { throw '认证隔离环境未就绪。' }
    # 专用验收库内固定场景；幂等写入，不触碰开发库或其他项目。
    $sql = @'
INSERT INTO t_inventory_note(id,note_no,enterprise_id,category_id,warehouse_id,commodity_name,total_quantity,available_quantity,frozen_quantity,unit,status)
SELECT 8700000000000000701,'AUTH-E2E-SELLER',u.enterprise_id,(SELECT id FROM t_commodity_category WHERE deleted=0 LIMIT 1),(SELECT id FROM t_warehouse WHERE deleted=0 LIMIT 1),'卖方隔离库存',1,1,0,'吨',2 FROM t_user u WHERE username='seller01'
ON DUPLICATE KEY UPDATE note_no=note_no;
INSERT INTO t_inventory_note(id,note_no,enterprise_id,category_id,warehouse_id,commodity_name,total_quantity,available_quantity,frozen_quantity,unit,status)
SELECT 8700000000000000702,'AUTH-E2E-BUYER',u.enterprise_id,(SELECT id FROM t_commodity_category WHERE deleted=0 LIMIT 1),(SELECT id FROM t_warehouse WHERE deleted=0 LIMIT 1),'买方隔离库存',1,1,0,'吨',2 FROM t_user u WHERE username='buyer01'
ON DUPLICATE KEY UPDATE note_no=note_no;
'@
    $sql | & docker @($context.Arguments) exec -T mysql sh -c 'MYSQL_PWD=$MYSQL_PASSWORD mysql --default-character-set=utf8mb4 -u$MYSQL_USER $MYSQL_DATABASE'
    if ($LASTEXITCODE -ne 0) { throw '认证检查库存场景准备失败。' }
    if ($Mall) {
        $mallSql = @'
INSERT INTO t_inventory_note(id,note_no,enterprise_id,category_id,warehouse_id,commodity_name,total_quantity,available_quantity,frozen_quantity,unit,status)
SELECT 8700000000000010000+c.id,CONCAT('MALL-QA-',c.code),u.enterprise_id,c.id,(SELECT id FROM t_warehouse WHERE deleted=0 LIMIT 1),CONCAT('界面验收·',c.name),100,100,0,c.unit,2
FROM t_commodity_category c JOIN t_user u ON u.username='seller01' WHERE c.deleted=0 AND c.level=2
ON DUPLICATE KEY UPDATE commodity_name=VALUES(commodity_name);
UPDATE t_listing l JOIN t_inventory_note n ON l.category_id=n.category_id AND l.enterprise_id=n.enterprise_id
SET l.commodity_name=n.commodity_name WHERE n.note_no LIKE 'MALL-QA-%' AND l.brand='隔离验收' AND l.origin='测试环境';
'@
        $mallSql | & docker @($context.Arguments) exec -T mysql sh -c 'MYSQL_PWD=$MYSQL_PASSWORD mysql --default-character-set=utf8mb4 -u$MYSQL_USER $MYSQL_DATABASE'
        if ($LASTEXITCODE -ne 0) { throw '商城隔离库存场景准备失败。' }
    }
    [IO.Directory]::CreateDirectory("$script:ProjectRoot/.local/browser") | Out-Null
    & docker compose -p spotlink-next-tools --project-directory $script:ProjectRoot -f "$script:ProjectRoot/ops/compose.tools.yml" build browser-tools
    if ($LASTEXITCODE -ne 0) { throw '容器浏览器构建失败。' }
    & docker compose -p spotlink-next-tools --project-directory $script:ProjectRoot -f "$script:ProjectRoot/ops/compose.tools.yml" run --rm --no-deps browser-tools sh /workspace/scripts/tests/auth-browser.sh
    if ($LASTEXITCODE -ne 0) { throw '认证浏览器回归失败。' }
    if ($Mall) {
        & docker compose -p spotlink-next-tools --project-directory $script:ProjectRoot -f "$script:ProjectRoot/ops/compose.tools.yml" run --rm --no-deps browser-tools sh /workspace/scripts/tests/mall-browser.sh
        if ($LASTEXITCODE -ne 0) { throw '商城浏览器回归失败。' }
    }
    if ($Inventory) {
        & docker compose -p spotlink-next-tools --project-directory $script:ProjectRoot -f "$script:ProjectRoot/ops/compose.tools.yml" run --rm --no-deps browser-tools sh /workspace/scripts/tests/inventory-browser.sh
        if ($LASTEXITCODE -ne 0) { throw '库存浏览器回归失败。' }
    }
    if ($Admin) {
        & docker compose -p spotlink-next-tools --project-directory $script:ProjectRoot -f "$script:ProjectRoot/ops/compose.tools.yml" run --rm --no-deps browser-tools sh /workspace/scripts/tests/admin-browser.sh
        if ($LASTEXITCODE -ne 0) { throw '企业与权限后台浏览器回归失败。' }
    }
    if ($Trading) {
        & docker compose -p spotlink-next-tools --project-directory $script:ProjectRoot -f "$script:ProjectRoot/ops/compose.tools.yml" run --rm --no-deps browser-tools sh /workspace/scripts/tests/listing-browser.sh
        if ($LASTEXITCODE -ne 0) { throw '可信库存挂牌浏览器回归失败。' }
    }
    Write-Host '认证容器浏览器回归通过。'
    exit 0
} catch { Write-Error $_.Exception.Message -ErrorAction Continue; exit 4 }
finally { & docker @($context.Arguments) down }
