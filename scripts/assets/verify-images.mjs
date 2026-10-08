import { readFileSync, statSync } from 'node:fs';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';

// 普通检查只核验本地成品，绝不调用图片模型。
const manifest = JSON.parse(readFileSync('design/图片资源清单.json', 'utf8'));
const prompts = JSON.parse(readFileSync('design/商品图片提示词.json', 'utf8'));
assert.equal(manifest.length, 8);
for (const item of manifest) {
  const content = readFileSync(item.file);
  assert.equal(content.toString('ascii', 0, 4), 'RIFF');
  assert.equal(content.toString('ascii', 8, 12), 'WEBP');
  assert.equal(createHash('sha256').update(content).digest('hex'), item.sha256);
  assert.equal(statSync(item.file).size, item.bytes);
  assert.ok(item.bytes < (item.id.startsWith('commodity-') ? 150000 : 300000));
  assert.ok(prompts.assets.some(prompt => prompt.id === item.id));
  assert.ok(item.original.startsWith('.local/'));
  assert.ok(item.originalSha256.length === 64);
  assert.equal(item.model, 'gpt-image-2');
}
console.log(`本地 WebP、散列、来源及预算校验通过：${manifest.length} 张，总计 ${manifest.reduce((n, item) => n + item.bytes, 0)} 字节。`);
