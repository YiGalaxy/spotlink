import http from 'node:http';

// 固定替身只按已声明题目选工具，绝不访问数据库或读取预期答案。
// 验证真实 Spring AI 协议、工具、上下文和持久化，不评估真实模型选工具的能力。
let requests = 0;
const reply = (res, status, data) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(data)); };
http.createServer(async (req, res) => {
  if (req.url === '/stats') return reply(res, 200, { requests });
  if (req.url !== '/v1/chat/completions' || req.method !== 'POST') return reply(res, 404, {});
  try {
    let body = '';
    for await (const part of req) { body += part; if (body.length > 256000) return reply(res, 413, {}); }
    const request = JSON.parse(body);
    requests++;
    const userMessages = request.messages.filter(m => m.role === 'user');
    const question = userMessages.at(-1)?.content ?? '';
    const is = text => question === text.normalize('NFKC');
    const results = request.messages.filter(m => m.role === 'tool');
    let name, args = {}, answer;
    if (results.length) {
      // 只呈现实际工具结果，历史是否存在由真实输入消息决定。
      const history = request.messages.some(m => m.role === 'assistant' && !m.tool_calls && m.content);
      answer = `已核对本次查询依据。${history ? '已收到本会话历史。' : '本轮没有会话历史。'}\n\n` + results.map(m => {
        try { const v = JSON.parse(m.content); return typeof v === 'string' ? v : JSON.stringify(v); }
        catch { return m.content; }
      }).join('\n\n');
    } else if (is('请复述我保存的采购需求。')) {
      const context = userMessages.find(m => m.content?.startsWith('用户保存的采购需求（'));
      answer = context ? `本会话保存的采购需求：${context.content.split('\n').slice(1).join('\n')}。` : '本会话未保存采购需求。';
    } else if (is('按品类汇总我的库存。')) {
      name = 'summarise_my_inventory';
    } else if (is('电子库存单可以质押吗？') || question.startsWith('鼷鼹龖麤')) {
      name = 'search_platform_rules';
      args = { question: question.startsWith('鼷鼹龖麤') ? '鼷鼹龖麤' : '电子库存单 质押' };
    } else if (is('我有哪些示例电解铜库存？') || is('只看被冻结的库存。')) {
      name = 'query_my_inventory';
      args = { commodityKeyword: '示例电解铜', onlyFrozen: is('只看被冻结的库存。') };
    } else return reply(res, 400, { error: { message: '固定离线题集不支持该问题' } });
    if (name && !request.tools?.some(t => t.function.name === name)) return reply(res, 400, { error: { message: '所需工具未注册' } });
    const message = name
      ? { role: 'assistant', content: null, tool_calls: [{ id: `baseline-${requests}`, type: 'function', function: { name, arguments: JSON.stringify(args) } }] }
      : { role: 'assistant', content: answer };
    reply(res, 200, { id: `offline-${requests}`, object: 'chat.completion', created: 1, model: 'offline-early-v1',
      choices: [{ index: 0, finish_reason: name ? 'tool_calls' : 'stop', message }],
      usage: { prompt_tokens: 12, completion_tokens: 6, total_tokens: 18 } });
  } catch { reply(res, 400, { error: { message: '离线请求结构无效' } }); }
}).listen(8080, '0.0.0.0');
