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
  const choice = tool && !hasResult
    ? { index: 0, finish_reason: 'tool_calls', message: { role: 'assistant', content: null, tool_calls: [{ id: 'offline-call', type: 'function', function: { name: 'platform_connection_probe', arguments: '{}' } }] } }
    : { index: 0, finish_reason: 'stop', message: { role: 'assistant', content: '离线模型连接正常。' } }
  // 不记录请求体、Authorization 或业务内容。
  res.end(JSON.stringify({ id: 'offline-completion', object: 'chat.completion', created: 1, model: request.model,
    choices: [choice], usage: { prompt_tokens: 12, completion_tokens: 6, total_tokens: 18 } }))
}).listen(8080, '0.0.0.0')
