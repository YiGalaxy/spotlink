import { useEffect, useRef, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Collapse,
  Empty,
  Input,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd'
import { SendOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { askAdvisor, fetchAdvisorStatus } from '@/api/advisor'
import type { ChatUsage, ToolCall } from '@/types/api'

interface ChatTurn {
  role: 'user' | 'assistant'
  content: string
  toolCalls?: ToolCall[]
  usage?: ChatUsage
  iterations?: number
  failed?: boolean
}

const SAMPLE_QUESTIONS = [
  '我们公司叫什么名字？交易席位号是多少？审核通过了吗？',
  '我们公司下面有几个账号？分别是谁？',
  '平台支持哪几种交易方式？',
]

export default function AdvisorPage() {
  const [turns, setTurns] = useState<ChatTurn[]>([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)

  const { data: status } = useQuery({
    queryKey: ['advisor-status'],
    queryFn: fetchAdvisorStatus,
  })

  useEffect(() => {
    const node = scrollRef.current
    if (node) {
      node.scrollTop = node.scrollHeight
    }
  }, [turns, loading])

  const send = async (text?: string) => {
    const message = (text ?? input).trim()
    if (!message || loading) return

    setInput('')
    setTurns((prev) => [...prev, { role: 'user', content: message }])
    setLoading(true)

    try {
      const result = await askAdvisor(message)
      setTurns((prev) => [
        ...prev,
        {
          role: 'assistant',
          content: result.answer,
          toolCalls: result.toolCalls,
          usage: result.usage,
          iterations: result.iterations,
        },
      ])
    } catch {
      // The axios interceptor already reported the reason; leave a marker so
      // the transcript shows that a turn failed rather than silently ending.
      setTurns((prev) => [
        ...prev,
        { role: 'assistant', content: '本轮请求失败，请稍后重试。', failed: true },
      ])
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ height: 'calc(100vh - 56px)', display: 'flex', flexDirection: 'column' }}>
      <div
        style={{
          padding: '12px 20px',
          background: '#fff',
          borderBottom: '1px solid #eceef2',
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          flexWrap: 'wrap',
        }}
      >
        <Typography.Text strong>AI 交易顾问</Typography.Text>
        {status ? (
          status.available ? (
            <>
              <Tag color="green">已就绪</Tag>
              <Tag>{status.model}</Tag>
              <Tag color="blue">{status.registeredTools.length} 个工具</Tag>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                顾问会自行决定调用哪个工具取数，回答下方可展开查看完整调用链
              </Typography.Text>
            </>
          ) : (
            <Tag color="orange">未配置 API 密钥</Tag>
          )
        ) : (
          <Spin size="small" />
        )}
      </div>

      <div ref={scrollRef} className="chat-scroll" style={{ flex: 1 }}>
        {turns.length === 0 ? (
          <div style={{ maxWidth: 560, margin: '60px auto 0' }}>
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="问点什么试试"
              style={{ marginBottom: 16 }}
            />
            <Space direction="vertical" style={{ width: '100%' }} size={8}>
              {SAMPLE_QUESTIONS.map((question) => (
                <Card
                  key={question}
                  size="small"
                  hoverable
                  onClick={() => send(question)}
                  styles={{ body: { padding: '10px 14px' } }}
                >
                  <Typography.Text>{question}</Typography.Text>
                </Card>
              ))}
            </Space>
          </div>
        ) : (
          <div style={{ maxWidth: 860, margin: '0 auto' }}>
            <Space direction="vertical" size={16} style={{ width: '100%' }}>
              {turns.map((turn, index) => (
                <div key={index}>
                  <div className={`chat-bubble ${turn.role}`}>{turn.content}</div>

                  {turn.role === 'assistant' && !turn.failed && (
                    <div style={{ marginTop: 6 }}>
                      <Space size={8} wrap>
                        {turn.toolCalls && turn.toolCalls.length > 0 && (
                          <Collapse
                            size="small"
                            ghost
                            style={{ width: '100%' }}
                            items={[
                              {
                                key: 'tools',
                                label: (
                                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                    查看调用链（{turn.toolCalls.length} 次工具调用，
                                    {turn.iterations} 轮）
                                  </Typography.Text>
                                ),
                                children: (
                                  <Space direction="vertical" size={10} style={{ width: '100%' }}>
                                    {turn.toolCalls.map((call, callIndex) => (
                                      <Card
                                        key={callIndex}
                                        size="small"
                                        title={
                                          <Space size={6}>
                                            <Tag color="blue">{call.name}</Tag>
                                            <Typography.Text
                                              type="secondary"
                                              style={{ fontSize: 12, fontWeight: 400 }}
                                            >
                                              入参 {call.input}
                                            </Typography.Text>
                                          </Space>
                                        }
                                        styles={{ body: { padding: 10 } }}
                                      >
                                        <pre className="tool-output">{call.output}</pre>
                                      </Card>
                                    ))}
                                  </Space>
                                ),
                              },
                            ]}
                          />
                        )}
                        {turn.usage && (
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            输入 {turn.usage.inputTokens} / 输出 {turn.usage.outputTokens} tokens
                          </Typography.Text>
                        )}
                      </Space>
                    </div>
                  )}
                </div>
              ))}

              {loading && (
                <div className="chat-bubble assistant" style={{ display: 'flex', gap: 10 }}>
                  <Spin size="small" />
                  <Typography.Text type="secondary">
                    正在思考，可能需要调用平台工具取数…
                  </Typography.Text>
                </div>
              )}
            </Space>
          </div>
        )}
      </div>

      <div
        style={{
          padding: 16,
          background: '#fff',
          borderTop: '1px solid #eceef2',
        }}
      >
        <div style={{ maxWidth: 860, margin: '0 auto' }}>
          <Space.Compact style={{ width: '100%' }}>
            <Input.TextArea
              value={input}
              onChange={(event) => setInput(event.target.value)}
              placeholder="输入问题，Enter 发送，Shift+Enter 换行"
              autoSize={{ minRows: 1, maxRows: 4 }}
              disabled={loading}
              onPressEnter={(event) => {
                if (!event.shiftKey) {
                  event.preventDefault()
                  void send()
                }
              }}
            />
            <Button
              type="primary"
              icon={<SendOutlined />}
              loading={loading}
              onClick={() => void send()}
              style={{ height: 'auto' }}
            >
              发送
            </Button>
          </Space.Compact>

          {status && !status.available && (
            <Alert
              type="warning"
              showIcon
              style={{ marginTop: 10 }}
              message="AI 顾问未配置 API 密钥"
              description="设置环境变量 BULK_ADVISOR_API_KEY 后重启后端即可启用。"
            />
          )}
        </div>
      </div>
    </div>
  )
}
