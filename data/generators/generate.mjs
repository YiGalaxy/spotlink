import { generate, verifyDirectory } from './dataset.mjs';
import { resolve } from 'node:path';

try {
  const args = process.argv.slice(2), options = {};
  const names = { '--dataset': 'dataset', '--batch': 'batch', '--seed': 'seed', '--base-time': 'baseTime', '--inventory-count': 'inventoryCount' };
  if (args[0] === '--verify') {
    if (args.length !== 2) throw new Error('--verify 只接受一个生成目录');
    const manifest = verifyDirectory(resolve(args[1]));
    console.log(`数据校验通过：${manifest.dataset}/${manifest.batch}，总行数 ${Object.values(manifest.counts).reduce((a, b) => a + b, 0)}`);
  } else {
    for (let i = 0; i < args.length; i++) {
      if (args[i] === '--resume') { if (options.resume) throw new Error('参数重复'); options.resume = true; continue; }
      const name = names[args[i]];
      if (!name || options[name] !== undefined || !args[i + 1] || args[i + 1].startsWith('--')) throw new Error('参数缺失、未知或重复');
      options[name] = name === 'inventoryCount' ? Number(args[++i]) : args[++i];
    }
    const result = generate(options);
    console.log(`[${result.resumed ? '沿用' : '已生成'}] ${result.directory}；数据包 ${result.manifest.dataset}，规则 ${result.manifest.ruleVersion}；尚未导入数据库。`);
  }
} catch (error) { console.error(`[校验失败] ${error.message}`); process.exitCode = 4; }
