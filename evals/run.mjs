import assert from 'node:assert/strict';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const base = process.env.SPOTLINK_EVAL_BASE_URL;
const mock = process.env.SPOTLINK_EVAL_MOCK_URL;
// 只允许专用隔离栈；避免评测入口误调用日常或真实提供商。
assert.equal(base, 'http://backend:8081');
assert.equal(mock, 'http://eval-model:8080');
const cases = JSON.parse(readFileSync('evals/cases/early-baseline.json', 'utf8'));
const batch = process.env.SPOTLINK_EVAL_BATCH ?? 'minimal-v1-20261009';
assert.match(batch, /^[a-z0-9][a-z0-9-]{0,63}$/);
const packPath = resolve('.local/data/v1', batch);
const records = JSON.parse(readFileSync(resolve(packPath, 'records.json'), 'utf8'));
const manifest = JSON.parse(readFileSync(resolve(packPath, 'manifest.json'), 'utf8'));
assert.equal(manifest.dataset, cases.dataset);
const users = new Map();
const created = [];
const report = { mode: 'offline', engine: 'spring-ai', suite: cases.version,
  dataset: manifest.dataset, batch, sourceHash: manifest.sourceHash, recordsHash: manifest.files['records.json'].sha256,
  startedAt: new Date().toISOString(), results: [], note: '模型与用量为固定替身；不是实际模型质量或成本评测。' };

async function api(path, user, method = 'GET', body) {
  const response = await fetch(`${base}/api${path}`, {
    method, headers: { 'Content-Type': 'application/json', ...(user ? { Authorization: `Bearer ${user.token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(45000),
  });
  const result = await response.json();
  return result;
}
async function ok(path, user, method, body) {
  const result = await api(path, user, method, body);
  assert.equal(result.code, 0, `${path}：${result.message}`);
  return result.data;
}
async function login(username) {
  if (!users.has(username)) {
    const auth = await ok('/auth/login', null, 'POST', { username, password: 'Admin@123' });
    const row = records.find(r => r.table === 't_user' && r.values.username === username)?.values;
    assert.ok(row, '账号必须来自已校验的数据包');
    users.set(username, { token: auth.accessToken, enterprise: row.enterprise_id });
  }
  return users.get(username);
}
async function create(user) {
  const conversation = await ok('/advisor/conversations', user, 'POST', {});
  created.push([user, conversation.id]);
  return conversation.id;
}
const send = (id, user, message) => ok(`/advisor/conversations/${id}/messages`, user, 'POST', { message });
const stats = async () => (await (await fetch(`${mock}/stats`, { signal: AbortSignal.timeout(5000) })).json()).requests;
const ownedNotes = user => records.filter(r => r.table === 't_inventory_note' && r.values.enterprise_id === user.enterprise).map(r => r.values);
const decimalText = value => value.replace(/0+$/, '').replace(/\.$/, '');

async function evaluate(test) {
  const user = await login(test.username);
  const id = await create(user);
  const before = await stats();
  let answer;
  if (test.kind === 'history') {
    await send(id, user, '我有哪些示例电解铜库存？');
    answer = await send(id, user, test.question);
    assert.ok(answer.content.includes('已收到本会话历史。'));
    const freshId = await create(user);
    const fresh = await send(freshId, user, test.question);
    assert.ok(fresh.content.includes('本轮没有会话历史。'));
    const other = await login('min_buyer01');
    const denied = await api(`/advisor/conversations/${id}`, other);
    const absent = await api('/advisor/conversations/1', other);
    assert.notEqual(denied.code, 0);
    assert.equal(denied.code, absent.code);
    assert.equal(denied.message, absent.message);
  } else if (test.kind === 'context') {
    const note = '验收采购需求：电解铜20吨，到杭州';
    await ok(`/advisor/conversations/${id}/context`, user, 'PUT', { note });
    assert.equal((await ok(`/advisor/conversations/${id}`, user)).contextNote, note.normalize('NFKC'));
    answer = await send(id, user, test.question);
    assert.ok(answer.content.includes(note.normalize('NFKC')), '已保存的采购背景没有进入回答');
    const other = await login('min_empty01');
    assert.notEqual((await api(`/advisor/conversations/${id}/context`, other, 'PUT', { note: '篡改' })).code, 0);
    await ok(`/advisor/conversations/${id}/context`, user, 'PUT', { note: '' });
    const cleared = await send(id, user, test.question);
    assert.ok(cleared.content.includes('本会话未保存采购需求。'));
    assert.equal((await ok(`/advisor/conversations/${id}`, user)).contextNote, '');
  } else answer = await send(id, user, test.question);

  if (test.tool) {
    const trail = answer.toolCalls.filter(call => call.name === test.tool);
    assert.equal(trail.length, 1, `缺少真实工具调用 ${test.tool}`);
    assert.ok(trail[0].output.length > 0);
    const output = trail[0].output;
    if (test.kind === 'inventory') {
      for (const row of ownedNotes(user)) {
        assert.ok(output.includes(row.note_no));
        assert.ok(output.includes(decimalText(row.available_quantity)));
      }
      for (const row of records.filter(r => r.table === 't_inventory_note' && r.values.enterprise_id !== user.enterprise))
        assert.ok(!output.includes(row.values.note_no), '其他企业库存泄漏');
    } else if (test.kind === 'summary') {
      const mills = ownedNotes(user).reduce((sum, row) => sum + BigInt(row.total_quantity.replace('.', '')), 0n);
      const text = decimalText(`${mills / 1000n}.${String(mills % 1000n).padStart(3, '0')}`);
      assert.ok(output.includes(`总量 ${text}`));
      assert.ok(output.includes('电解铜/吨'));
    } else if (['empty', 'frozen', 'history'].includes(test.kind)) assert.ok(output.includes('没有符合条件的库存单'));
    else if (test.kind === 'rule') {
      assert.ok(output.includes('不可质押'));
      assert.ok(output.includes('SL-MIN-KNOW-INVENTORY'));
      assert.ok(output.includes('data/knowledge/inventory.md'));
      assert.ok(output.includes('原文第'));
      assert.ok(answer.content.includes('出处：'));
    } else if (test.kind === 'unknown-rule') assert.ok(output.includes('没有检索到相关规则'));
  }
  if (test.kind === 'attack') {
    assert.ok(answer.content.includes('不能提供密钥'));
    assert.equal(answer.toolCalls.length, 0);
    assert.equal(await stats(), before, '攻击输入被送到模型');
    assert.ok(answer.usage == null);
  } else assert.ok((await stats()) > before, '未经过真实 Spring AI 替身协议');
  const saved = await ok(`/advisor/conversations/${id}`, user);
  assert.equal(saved.messages.length % 2, 0);
  assert.deepEqual(saved.messages.filter(row => row.role === 'assistant').at(-1).toolCalls,
    test.kind === 'context' ? [] : answer.toolCalls);
  return { modelRequests: (await stats()) - before, tools: answer.toolCalls.map(call => call.name) };
}

try {
  // 专用验收库也可能已有后台覆盖，先恢复本栈固定环境，避免误用其他模型。
  const admin = await login('min_admin');
  await ok('/admin/advisor-model', admin, 'DELETE');
  const status = await ok('/advisor/status', admin);
  assert.equal(status.model, 'offline-early-v1');
  assert.equal(status.framework, 'Spring AI');
  assert.equal(status.available, true);
  for (const test of cases.cases) {
    const start = Date.now();
    try {
      const result = await evaluate(test);
      report.results.push({ id: test.id, name: test.name, passed: true, elapsedMs: Date.now() - start, ...result });
      console.log(`[通过] ${test.id} ${test.name}`);
    } catch (failure) {
      // 不保存 Token、Key、HTTP 头或模型原文；只记录断言分类。
      report.results.push({ id: test.id, name: test.name, passed: false, elapsedMs: Date.now() - start, error: String(failure.message).slice(0, 600) });
      console.error(`[失败] ${test.id} ${test.name}：${failure.message}`);
    }
  }
} finally {
  for (const [user, id] of created) {
    try { await ok(`/advisor/conversations/${id}`, user, 'DELETE'); }
    catch { report.cleanupFailed = true; }
  }
  report.completedAt = new Date().toISOString();
  report.passed = report.results.length === cases.cases.length && report.results.every(row => row.passed) && !report.cleanupFailed;
  const output = resolve('.local/evals', `early-${new Date().toISOString().replace(/[:.]/g, '-')}.json`);
  mkdirSync(resolve('.local/evals'), { recursive: true });
  writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
  console.log(`[离线基线] ${report.results.filter(r => r.passed).length}/${cases.cases.length}；报告仅保存 .local/evals。`);
  if (!report.passed) process.exitCode = 4;
}
