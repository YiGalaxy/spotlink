import http from 'node:http'

let count = 0
http.createServer(async (req, res) => {
  res.setHeader('Content-Type', 'application/json')
  if (req.url === '/stats') return res.end(JSON.stringify({ count }))
  if (req.url !== '/v1/chat/completions') { res.statusCode = 404; return res.end('{}') }
  let body = ''
  for await (const part of req) body += part
  const request = JSON.parse(body)
  count++
  const hasResult = request.messages.some(m => m.role === 'tool')
  const tool = request.tools?.find(t => t.function.name === 'platform_connection_probe')
  const procurement = request.tools?.find(t => t.function.name === 'find_purchase_options')
  const rules = request.tools?.find(t => t.function.name === 'search_platform_rules')
  const userText = request.messages.filter(m => m.role === 'user').at(-1)?.content ?? ''
  const ruleQuestion = /库存单.*质押/.test(userText)
  const functionName = tool ? 'platform_connection_probe' : ruleQuestion && rules ? 'search_platform_rules' : procurement ? 'find_purchase_options' : null
  const args = tool ? '{}' : ruleQuestion ? JSON.stringify({ question: userText }) : JSON.stringify({ keyword: '电解铜', location: null, minQuantity: null, maxPrice: null, unit: '吨', deliveryMethod: null, sortBy: 'PRICE_ASC' })
  let answer = '离线模型连接正常。'
  if (hasResult && ruleQuestion) {
    answer = '电子库存单不能用于质押。请核对本轮提供的库存操作说明原文；未知的金融服务需要另行核实。'
  } else if (hasResult && request.messages.some(m => m.role === 'tool' && m.name !== 'platform_connection_probe')) {
    const output = request.messages.filter(m => m.role === 'tool').at(-1)?.content
    try {
      let data = JSON.parse(output)
      if (typeof data === 'string') data = JSON.parse(data)
      const item = data['挂牌'][0]
      answer = `已查到真实挂牌，可查看以下商品卡片。\n\n[查看挂牌](${item['详情']})\n\n[伪造挂牌](/trading?listing=99999999999999) [外部链接](https://example.invalid/tracker) ![外部图片](https://example.invalid/image.png)`
    } catch { answer = '当前未查询到符合条件的挂牌。' }
  }
  const choice = functionName && !hasResult
    ? { index: 0, finish_reason: 'tool_calls', message: { role: 'assistant', content: null, tool_calls: [{ id: 'offline-call', type: 'function', function: { name: functionName, arguments: args } }] } }
    : { index: 0, finish_reason: 'stop', message: { role: 'assistant', content: answer } }
  // 不记录请求体、Authorization 或业务内容。
  res.end(JSON.stringify({ id: 'offline-completion', object: 'chat.completion', created: 1, model: request.model,
    choices: [choice], usage: { prompt_tokens: 12, completion_tokens: 6, total_tokens: 18 } }))
}).listen(8080, '0.0.0.0')
