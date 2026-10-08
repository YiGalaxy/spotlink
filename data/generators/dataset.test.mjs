import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdtempSync, rmSync, mkdirSync, symlinkSync, openSync, closeSync } from 'node:fs';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { root, buildDataset, generate, verifyDirectory, validateRecords, renderSql, decimal, sha256 } from './dataset.mjs';

const batch = label => `c06-test-${process.pid}-${label}`;
const clean = (name) => rmSync(join(root, '.local/data/v1', name), { recursive: true, force: true });

test('相同 seed 与时钟生成逐字一致；大 ID 和三位数量不经过浮点', () => {
  const first = buildDataset(), second = buildDataset();
  assert.deepEqual(first, second);
  assert.ok(BigInt(first.records[0].values.id) > BigInt(Number.MAX_SAFE_INTEGER));
  const different = buildDataset({ seed: 'another-seed' });
  assert.notDeepEqual(first.expected.quantityMillsByEnterprise, different.expected.quantityMillsByEnterprise);
  for (const row of first.records.filter(row => row.table === 't_inventory_note')) {
    assert.equal(decimal(row.values.total_quantity, 3), decimal(row.values.available_quantity, 3) + decimal(row.values.frozen_quantity, 3));
  }
});
test('拒绝不存在/未完成的数据包、非规范时间、无上限规模', () => {
  assert.throws(() => buildDataset({ dataset: 'unknown' }), /未知/);
  assert.throws(() => buildDataset({ dataset: 'demo' }), /C37/);
  assert.throws(() => buildDataset({ baseTime: '2026-10-08' }), /baseTime/);
  assert.throws(() => buildDataset({ inventoryCount: 10000 }), /规模/);
  assert.throws(() => buildDataset({ dataset: 'performance', inventoryCount: 10001 }), /规模/);
  assert.equal(buildDataset({ dataset: 'performance', inventoryCount: 50 }).expected.counts.t_inventory_note, 50);
});
test('非法数量、精度、单位和缺关联拒绝；额外规格键保留', () => {
  for (const [key, value, expected] of [['total_quantity', '-1.000', /小数/], ['available_quantity', '1.0001', /小数/], ['warehouse_id', '1', /关联/], ['unit', '千克', /单位/]]) {
    const records = structuredClone(buildDataset().records);
    records.at(-1).values[key] = value;
    assert.throws(() => validateRecords(records), expected);
  }
  assert.equal(buildDataset().records.at(-1).values.spec.custom_demo, '保留未知键');
});
test('ID/编号重复、任意 SQL 表和插入列拒绝', () => {
  const duplicate = buildDataset().records;
  duplicate.push(structuredClone(duplicate.at(-1)));
  assert.throws(() => validateRecords(duplicate), /重复 ID/);
  const numbers = buildDataset().records;
  numbers.at(-1).values.note_no = numbers.at(-2).values.note_no;
  assert.throws(() => validateRecords(numbers), /重复业务编号/);
  const table = buildDataset().records;
  table[0].table = 't_user; DROP TABLE t_user';
  assert.throws(() => validateRecords(table), /枚举/);
  const column = buildDataset().records;
  column[0].values.evil = 'x';
  assert.throws(() => validateRecords(column), /列不匹配/);
});
test('SQL 审阅文件编码危险引号和中文为 UTF-8 十六进制字面量', () => {
  const records = buildDataset().records;
  const text = "中文'; DROP TABLE t_user; --\\\n";
  records[0].values.name = text;
  const sql = renderSql(records);
  assert.ok(sql.includes(Buffer.from(text).toString('hex')));
  assert.ok(!sql.includes('DROP TABLE'));
});
test('实际文件 manifest/SQL/JSONL 同源；恢复不改时间锚或覆盖', () => {
  const name = batch('resume');
  try {
    const first = generate({ batch: name });
    assert.deepEqual(verifyDirectory(first.directory), first.manifest);
    const before = readFileSync(join(first.directory, 'manifest.json'));
    assert.throws(() => generate({ batch: name }), /批次已存在/);
    assert.equal(generate({ batch: name, resume: true }).resumed, true);
    assert.deepEqual(readFileSync(join(first.directory, 'manifest.json')), before);
    assert.throws(() => generate({ batch: name, resume: true, seed: 'changed' }), /恢复参数/);
    assert.throws(() => generate({ batch: name, resume: true, baseTime: '2026-10-09T00:00:00.000Z' }), /恢复参数/);
  } finally { clean(name); }
});
test('损坏文件与重算散列的伪造数据都拒绝', () => {
  for (const recompute of [false, true]) {
    const name = batch(`tamper-${recompute}`);
    try {
      const result = generate({ batch: name });
      const path = join(result.directory, 'records.json');
      const records = JSON.parse(readFileSync(path, 'utf8'));
      records[0].values.name = '篡改企业';
      const bytes = JSON.stringify(records, null, 2) + '\n';
      writeFileSync(path, bytes);
      if (recompute) {
        result.manifest.files['records.json'].sha256 = sha256(bytes);
        result.manifest.files['records.json'].bytes = Buffer.byteLength(bytes);
        writeFileSync(join(result.directory, 'manifest.json'), JSON.stringify(result.manifest));
      }
      assert.throws(() => verifyDirectory(result.directory), recompute ? /规则不一致/ : /文件损坏/);
    } finally { clean(name); }
  }
});
test('行数、版本与依赖不一致拒绝', () => {
  const name = batch('metadata');
  try {
    const result = generate({ batch: name });
    const path = join(result.directory, 'manifest.json');
    result.manifest.schema.requiredMigrations = [999];
    writeFileSync(path, JSON.stringify(result.manifest));
    assert.throws(() => verifyDirectory(result.directory), /迁移依赖/);
    result.manifest.ruleVersion = 'v999';
    writeFileSync(path, JSON.stringify(result.manifest));
    assert.throws(() => verifyDirectory(result.directory), /不兼容/);
  } finally { clean(name); }
});
test('目录穿越、符号链接、生成并发锁均拒绝', () => {
  assert.throws(() => generate({ batch: '../escape' }), /路径/);
  const name = batch('lock'), directory = join(root, '.local/data/v1', name);
  mkdirSync(join(root, '.local/data/v1'), { recursive: true });
  const handle = openSync(directory + '.lock', 'wx');
  try { assert.throws(() => generate({ batch: name }), /正在生成/); }
  finally { closeSync(handle); rmSync(directory + '.lock'); }
  const outside = mkdtempSync(join(root, '.local/c06-outside-'));
  try { symlinkSync(outside, directory); assert.throws(() => generate({ batch: name }), /符号链接/); }
  finally { rmSync(directory, { force: true }); rmSync(outside, { recursive: true }); }
});
test('命令入口失败退出码明确，禁止未知参数与不存在的恢复批次', () => {
  for (const args of [['--invalid'], ['--dataset'], ['--dataset', 'demo'], ['--batch', batch('missing'), '--resume']]) {
    const result = spawnSync(process.execPath, [join(root, 'data/generators/generate.mjs'), ...args], { encoding: 'utf8' });
    assert.equal(result.status, 4, result.stderr);
  }
});
