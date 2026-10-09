import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync, lstatSync, realpathSync, renameSync, rmSync, openSync, closeSync } from 'node:fs';
import { resolve, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateSchema } from './schema.mjs';

export const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const data = join(root, 'data');
const readJson = (path) => JSON.parse(readFileSync(path, 'utf8').replace(/^\uFEFF/, ''));
const stableJson = (value) => JSON.stringify(value, null, 2) + '\n';
export const sha256 = (value) => createHash('sha256').update(value).digest('hex');
const rules = readJson(join(data, 'rules/v1.json'));
const templates = readJson(join(data, 'sql/insert-templates.json'));
const recordsSchema = readJson(join(data, 'schemas/records.schema.json'));
const manifestSchema = readJson(join(data, 'schemas/manifest.schema.json'));
const files = ['records.json', 'records.jsonl', 'rendered.sql', 'expected.json'];
const dependencies = { t_user: ['t_enterprise'], t_user_role: ['t_user', 't_role'], t_inventory_note: ['t_enterprise', 't_commodity_category', 't_warehouse'], t_knowledge_chunk: ['t_knowledge_doc'] };

export function sourceHash() {
  const visit = (path) => readdirSync(path).sort().flatMap(name => {
    const full = join(path, name);
    if (lstatSync(full).isSymbolicLink()) throw new Error('数据定义不能包含符号链接');
    return lstatSync(full).isDirectory() ? visit(full) : [relative(data, full).split(sep).join('/') + ':' + sha256(readFileSync(full))];
  });
  return sha256(visit(data).join('\n'));
}

export function decimal(value, scale) {
  if (typeof value !== 'string' || !new RegExp(`^(0|[1-9][0-9]*)\\.[0-9]{${scale}}$`).test(value)) throw new Error(`小数必须为非负且固定 ${scale} 位字符串`);
  const integer = BigInt(value.replace('.', ''));
  if (integer > 999999999999999999n) throw new Error('数量超过 DECIMAL(18,3) 上限');
  return integer;
}
export function validateRecords(records) {
  validateSchema(records, recordsSchema);
  const ids = new Map(), businessNumbers = new Set();
  for (const row of records) {
    const columns = templates[row.table], v = row.values;
    if (!columns || Object.keys(v).sort().join() !== [...columns].sort().join()) throw new Error(`插入列不匹配 ${row.table}`);
    if (BigInt(v.id) > 9223372036854775807n) throw new Error('ID 超过 BIGINT 上限');
    const key = `${row.table}:${v.id}`;
    if (ids.has(key)) throw new Error('重复 ID');
    ids.set(key, row);
    const number = v.enterprise_code ?? v.code ?? v.note_no ?? v.username ?? v.doc_code ?? (row.table === 't_user_role' ? `${v.user_id}:${v.role_id}` : `${v.doc_id}:${v.chunk_index}`);
    if (businessNumbers.has(`${row.table}:${number}`)) throw new Error('重复业务编号');
    businessNumbers.add(`${row.table}:${number}`);
  }
  for (const row of records.filter(row => row.table === 't_user')) {
    if (row.values.enterprise_id !== null && !ids.has(`t_enterprise:${row.values.enterprise_id}`)) throw new Error('账号企业关联缺失');
    if (row.values.password !== 'LOCAL_DEMO_BCRYPT') throw new Error('演示密码必须由导入器编码');
  }
  for (const row of records.filter(row => row.table === 't_user_role')) {
    if (!ids.has(`t_user:${row.values.user_id}`) || !['9100', '9101'].includes(row.values.role_id)) throw new Error('账号角色关联缺失');
  }
  for (const row of records.filter(row => row.table === 't_knowledge_chunk')) {
    if (!ids.has(`t_knowledge_doc:${row.values.doc_id}`) || !row.values.content.trim()) throw new Error('知识文档关联缺失或空分块');
  }
  for (const row of records.filter(row => row.table === 't_inventory_note')) {
    const v = row.values;
    const category = ids.get(`t_commodity_category:${v.category_id}`);
    if (!ids.has(`t_enterprise:${v.enterprise_id}`) || !category || !ids.has(`t_warehouse:${v.warehouse_id}`)) throw new Error('库存关联缺失');
    if (category.values.unit !== v.unit) throw new Error('库存单位与品类不匹配');
    if (decimal(v.total_quantity, 3) <= 0n || decimal(v.available_quantity, 3) + decimal(v.frozen_quantity, 3) !== decimal(v.total_quantity, 3)) throw new Error('库存不守恒或为零');
    if (v.status !== 2 || v.frozen_quantity !== '0.000' || v.version !== 0) throw new Error('基础库存必须处于全可用状态');
    for (const field of category.values.spec_schema) {
      if (field.required && !(field.key in v.spec)) throw new Error('规格缺少必填字段');
    }
  }
  return true;
}

// 这是供审阅的 SQL。实际导入作业必须使用相同白名单列做参数绑定，不直接执行此文本。
function sqlLiteral(value) {
  if (value === null) return 'NULL';
  if (typeof value === 'number') { if (!Number.isSafeInteger(value)) throw new Error('SQL 非安全整数'); return String(value); }
  const raw = typeof value === 'object' ? JSON.stringify(value) : String(value);
  return `CONVERT(0x${Buffer.from(raw, 'utf8').toString('hex')} USING utf8mb4)`;
}
export function renderSql(records) {
  return '-- 容器生成的审阅文件；业务导入使用参数化模板绑定值。\n' + records.map(row => {
    const columns = templates[row.table];
    return `INSERT INTO ${row.table} (${columns.join(',')}) VALUES (${columns.map(column => sqlLiteral(row.values[column])).join(',')});`;
  }).join('\n') + '\n';
}

export function buildDataset({ dataset = 'minimal', seed, baseTime, inventoryCount } = {}) {
  const profile = rules.datasets[dataset];
  if (!profile) throw new Error('未知数据包');
  if (!profile.enabled) throw new Error(profile.reason);
  seed ??= profile.seed;
  if (typeof seed !== 'string' || seed.length < 1 || seed.length > 128) throw new Error('seed 无效');
  baseTime ??= profile.baseTime;
  if (!baseTime || new Date(baseTime).toISOString() !== baseTime) throw new Error('baseTime 必须是含毫秒的 UTC ISO 时间');
  inventoryCount ??= profile.inventoryCount;
  if (!Number.isSafeInteger(inventoryCount) || inventoryCount < 3 || inventoryCount > (profile.maxInventoryCount ?? 3)) throw new Error('库存规模无效；仅 performance 允许扩展规模');
  const namespace = BigInt(rules.identityNamespace), id = n => String(namespace + BigInt(n));
  const timestamp = baseTime.slice(0, 19).replace('T', ' ');
  const records = [], add = (table, scenarioId, values) => records.push({ scenarioId, table, values: { ...values, created_at: timestamp, ...(templates[table].includes('updated_at') ? { updated_at: timestamp } : {}) } });
  const fakeCompanies = ['示例华东金属贸易有限公司', '示例华南材料采购有限公司', '示例空白企业有限公司'];
  fakeCompanies.forEach((name, i) => add('t_enterprise', 'IDENTITY-BASE', { id: id(100 + i), enterprise_code: `SL-MIN-ENT-${i + 1}`, name, short_name: `示例企业${i + 1}`, unified_social_credit_code: `SYNTHETIC-SL-${i + 1}`, contact_name: `虚构联系人${i + 1}`, contact_phone: '000-0000-0000', contact_email: `demo${i + 1}@example.invalid`, trader_code: `SL${i + 1}`, status: 1, qualifications: [] }));
  ['min_seller01', 'min_buyer01', 'min_empty01', 'min_admin', 'min_auditor'].forEach((username, i) => add('t_user', 'IDENTITY-BASE', { id: id(400 + i), enterprise_id: i < 3 ? id(100 + i) : null, username, password: 'LOCAL_DEMO_BCRYPT', real_name: `虚构演示用户${i + 1}`, user_type: i < 3 ? 1 : 2, status: 1 }));
  [0, 1].forEach(i => add('t_user_role', 'IDENTITY-BASE', { id: id(500 + i), user_id: id(403 + i), role_id: String(9100 + i) }));
  add('t_warehouse', 'INVENTORY-BASE', { id: id(200), code: 'SL-MIN-WH-1', name: '示例华东交收仓库', province: '示例省', city: '示例市', address: '虚构地址，仅用于本地演示', contact_name: '虚构仓管', contact_phone: '000-0000-0000', status: 1 });
  add('t_commodity_category', 'INVENTORY-BASE', { id: id(300), code: 'SL-MIN-COPPER', name: '电解铜', path: `/${id(300)}/`, spec_schema: [{ key: 'grade', label: '牌号', type: 'string', required: true }], unit: '吨', status: 1 });
  for (let i = 0; i < inventoryCount; i++) {
    // SHA-256 派生整数，不使用浮点随机数计算小数，seed 改变只影响明确字段。
    const mills = 1000n + BigInt('0x' + sha256(`${seed}:${i}`).slice(0, 12)) % 99000n;
    const quantity = `${mills / 1000n}.${String(mills % 1000n).padStart(3, '0')}`;
    add('t_inventory_note', 'INVENTORY-BASE', { id: id(1000 + i), note_no: `SL-MIN-IN-${String(i + 1).padStart(5, '0')}`, enterprise_id: id(i === 2 ? 101 : 100), category_id: id(300), warehouse_id: id(200), commodity_name: '示例电解铜', spec: { grade: 'Cu-CATH-1', custom_demo: '保留未知键' }, total_quantity: quantity, available_quantity: quantity, frozen_quantity: '0.000', unit: '吨', status: 2, version: 0, remark: '合成基础库存，仅用于本地演示' });
  }
  ['procurement', 'inventory', 'trading'].forEach((name, index) => {
    const original = readFileSync(join(data, `knowledge/${name}.md`), 'utf8');
    const text = original.replace(/\r\n?/g, '\n').normalize('NFC').trim();
    const title = text.split('\n')[0].replace(/^#\s+/, '');
    const docId = id(600 + index);
    add('t_knowledge_doc', 'KNOWLEDGE-BASE', { id: docId, doc_code: `SL-MIN-KNOW-${name.toUpperCase()}`, title, category: 'GUIDE', source: `data/knowledge/${name}.md`, version: 'v1', status: 1, remark: JSON.stringify({ visibility: 'PUBLIC', scope: 'local-demo', originalSha256: sha256(original), normalizedSha256: sha256(text), chunking: rules.chunking }) });
    const sections = text.split(/\n(?=## )/);
    let chunkIndex = 0;
    for (const section of sections) {
      const body = section.trim();
      // 保留标题、章节和原文行号；按字符切块，配置与散列进入文档元数据。
      const heading = body.split('\n')[0];
      const prefix = `${title}\n来源：data/knowledge/${name}.md · v1 · 原文第${text.slice(0, text.indexOf(section)).split('\n').length}行\n`;
      const capacity = rules.chunking.maxCharacters - prefix.length - heading.length - 1;
      if (capacity < 100) throw new Error('分块标题超长');
      const content = body.slice(heading.length).trim();
      for (let offset = 0; offset < Math.max(content.length, 1); offset += capacity) {
        add('t_knowledge_chunk', 'KNOWLEDGE-BASE', { id: id(700 + index * 100 + chunkIndex), doc_id: docId, chunk_index: chunkIndex++, content: `${prefix}${heading}\n${content.slice(offset, offset + capacity)}`, token_count: 0 });
      }
    }
  });
  validateRecords(records);
  const counts = Object.fromEntries(Object.keys(templates).map(table => [table, records.filter(row => row.table === table).length]));
  const totals = {};
  for (const row of records.filter(row => row.table === 't_inventory_note')) {
    const v = row.values;
    totals[v.enterprise_id] = (BigInt(totals[v.enterprise_id] ?? '0') + decimal(v.total_quantity, 3)).toString();
  }
  const expected = { scope: 'initial', counts, quantityMillsByEnterprise: totals, emptyEnterpriseId: id(102), coverage: readJson(join(data, 'coverage/minimal.json')), rejectedRequests: dataset === 'acceptance' ? [{ caseId: 'NEGATIVE-QUANTITY', quantity: '-1.000' }, { caseId: 'EXCESS-PRECISION', quantity: '1.0001' }, { caseId: 'MISSING-WAREHOUSE', warehouseId: null }] : [] };
  return { records, expected, dataset, seed, baseTime, timeRule: profile.clock };
}

function safeBatchPath(ruleVersion, batch) {
  if (ruleVersion !== rules.ruleVersion || !/^[a-z0-9][a-z0-9-]{0,63}$/.test(batch)) throw new Error('规则版本或批次路径无效');
  const local = join(root, '.local');
  mkdirSync(local, { recursive: true });
  const target = join(local, 'data', ruleVersion, batch);
  let cursor = root;
  for (const component of relative(root, target).split(sep)) {
    cursor = join(cursor, component);
    if (existsSync(cursor) && lstatSync(cursor).isSymbolicLink()) throw new Error('输出目录不允许符号链接');
  }
  if (realpathSync(local) !== resolve(local)) throw new Error('输出目录被重定向');
  return target;
}

export function verifyDirectory(directory) {
  const manifest = readJson(join(directory, 'manifest.json'));
  validateSchema(manifest, manifestSchema);
  if (manifest.ruleVersion !== rules.ruleVersion || manifest.generatorVersion !== rules.generatorVersion || manifest.sourceHash !== sourceHash()) throw new Error('生成规则版本/内容不兼容');
  if (Object.keys(manifest.files).sort().join() !== [...files].sort().join()) throw new Error('manifest 文件列表不完整');
  for (const name of files) {
    const payload = readFileSync(join(directory, name));
    if (sha256(payload) !== manifest.files[name].sha256 || payload.length !== manifest.files[name].bytes) throw new Error(`文件损坏 ${name}`);
  }
  const fresh = buildDataset({ dataset: manifest.dataset, seed: manifest.seed, baseTime: manifest.baseTime, inventoryCount: manifest.counts.t_inventory_note });
  if (stableJson(fresh.records) !== readFileSync(join(directory, 'records.json'), 'utf8') || renderSql(fresh.records) !== readFileSync(join(directory, 'rendered.sql'), 'utf8') || stableJson(fresh.expected) !== readFileSync(join(directory, 'expected.json'), 'utf8')) throw new Error('数据/SQL/期望与规则不一致');
  if (fresh.records.map(row => JSON.stringify(row)).join('\n') + '\n' !== readFileSync(join(directory, 'records.jsonl'), 'utf8')) throw new Error('JSONL 与 JSON 不一致');
  if (manifest.timeRule !== fresh.timeRule || stableJson(manifest.dependencies) !== stableJson(dependencies)) throw new Error('时间规则或表依赖不一致');
  if (stableJson(fresh.expected.counts) !== stableJson(manifest.counts) || stableJson(manifest.schema) !== stableJson(rules.schema)) throw new Error('行数或迁移依赖不一致');
  for (const name of files) if (manifest.files[name].rows !== (name === 'expected.json' ? 1 : fresh.records.length)) throw new Error('文件行数声明不一致');
  return manifest;
}

export function generate(options = {}) {
  const batch = options.batch ?? 'minimal-default';
  const target = safeBatchPath(rules.ruleVersion, batch);
  mkdirSync(resolve(target, '..'), { recursive: true });
  const lock = target + '.lock';
  let handle;
  try { handle = openSync(lock, 'wx'); } catch { throw new Error('该批次正在生成或存在中断锁；核验运行状态后处理锁'); }
  const temporary = target + `.tmp-${process.pid}`;
  try {
    if (existsSync(target)) {
      const manifest = verifyDirectory(target);
      if (!options.resume) throw new Error('批次已存在；使用 --resume 核验并沿用，禁止覆盖');
      for (const key of ['dataset', 'seed', 'baseTime']) if (options[key] != null && options[key] !== manifest[key]) throw new Error('恢复参数与原批次不一致');
      if (options.inventoryCount != null && options.inventoryCount !== manifest.counts.t_inventory_note) throw new Error('恢复规模与原批次不一致');
      return { directory: target, manifest, resumed: true };
    }
    if (options.resume) throw new Error('恢复批次不存在；先准备数据');
    const built = buildDataset(options);
    mkdirSync(temporary);
    const content = { 'records.json': stableJson(built.records), 'records.jsonl': built.records.map(row => JSON.stringify(row)).join('\n') + '\n', 'rendered.sql': renderSql(built.records), 'expected.json': stableJson(built.expected) };
    const manifest = { formatVersion: 1, ruleVersion: rules.ruleVersion, generatorVersion: rules.generatorVersion, sourceHash: sourceHash(), dataset: built.dataset, batch, seed: built.seed, baseTime: built.baseTime, timeRule: built.timeRule, schema: structuredClone(rules.schema), dependencies, counts: built.expected.counts, files: Object.fromEntries(Object.entries(content).map(([name, value]) => [name, { sha256: sha256(value), bytes: Buffer.byteLength(value), rows: name === 'expected.json' ? 1 : built.records.length }])) };
    validateSchema(manifest, manifestSchema);
    for (const [name, value] of Object.entries(content)) writeFileSync(join(temporary, name), value, 'utf8');
    writeFileSync(join(temporary, 'manifest.json'), stableJson(manifest), 'utf8');
    verifyDirectory(temporary);
    renameSync(temporary, target);
    return { directory: target, manifest, resumed: false };
  } finally {
    if (existsSync(temporary)) rmSync(temporary, { recursive: true });
    closeSync(handle); rmSync(lock);
  }
}
