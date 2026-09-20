import { useEffect, useRef, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Collapse,
  Empty,
  Input,
  Popconfirm,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd'
import { DeleteOutlined, PlusOutlined, SendOutlined } from '@ant-design/icons'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs from 'dayjs'
import {
  createConversation,
  deleteConversation,
  fetchAdvisorStatus,
  getConversation,
  listConversations,
  sendMessage,
} from '@/api/advisor'
import MarkdownText from '@/components/MarkdownText'
import type { EntityId, MessageView } from '@/types/api'

const SAMPLE_QUESTIONS = [
  '我们公司叫什么名字？交易席位号是多少？审核通过了吗？',
  '我们公司下面有几个账号？分别是谁？',
  '平台支持哪几种交易方式？',
]

/** Placeholder turn shown while waiting, and on failure. Never persisted. */
function localTurn(role: 'user' | 'assistant', content: string): MessageView {
  return {
    id: null,
    role,
    content,
    toolCalls: [],
    iterations: null,
    usage: null,
    createdAt: new Date().toISOString(),
  }
}

export default function AdvisorPage() {
  const [activeId, setActiveId] = useState<EntityId | null>(null)
  const [messages, setMessages] = useState<MessageView[]>([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [loadingHistory, setLoadingHistory] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)
  const queryClient = useQueryClient()

  const { data: conversations = [] } = useQuery({
    queryKey: ['conversations'],
    queryFn: listConversations,
  })

  const { data: status } = useQuery({
    queryKey: ['advisor-status'],
    queryFn: fetchAdvisorStatus,
  })

  useEffect(() => {
    const node = scrollRef.current
    if (node) {
      node.scrollTop = node.scrollHeight
    }
  }, [messages, sending])

  const refreshList = () => queryClient.invalidateQueries({ queryKey: ['conversations'] })

  const openConversation = async (id: EntityId) => {
    setActiveId(id)
    setLoadingHistory(true)
    try {
      const detail = await getConversation(id)
      setMessages(detail.messages)
    } catch {
      setMessages([])
    } finally {
      setLoadingHistory(false)
    }
  }

  /**
   * Clears the view instead of creating a row. The conversation is created on
   * the first question, so clicking here and then changing your mind leaves
   * nothing behind in the list.
   */
  const handleNewConversation = () => {
    setActiveId(null)
    setMessages([])
    setInput('')
  }

  const handleDeleteConversation = async (id: EntityId) => {
    await deleteConversation(id)
    await refreshList()
    if (activeId === id) {
      setActiveId(null)
      setMessages([])
    }
  }

  const send = async (text?: string) => {
    const content = (text ?? input).trim()
    if (!content || sending) return

    // A conversation is created lazily on the first question, so clicking
    // "新建对话" does not litter the list with empty sessions.
    let conversationId = activeId
    if (conversationId === null) {
      const detail = await createConversation()
      conversationId = detail.id
      setActiveId(conversationId)
    }

    setInput('')
    setMessages((prev) => [...prev, localTurn('user', content)])
    setSending(true)

    try {
      const reply = await sendMessage(conversationId, content)
      setMessages((prev) => [...prev, reply])
      await refreshList()
    } catch {
      setMessages((prev) => [...prev, localTurn('assistant', '本轮请求失败，请稍后重试。')])
    } finally {
      setSending(false)
    }
  }

  return (
    <div style={{ display: 'flex', height: 'calc(100vh - 56px)' }}>
      {/* ---------- conversation list ---------- */}
      <div className="conv-sidebar">
        <div style={{ padding: 12 }}>
          <Button type="primary" block icon={<PlusOutlined />} onClick={handleNewConversation}>
            新建对话
          </Button>
        </div>

        <div className="conv-list">
          {conversations.length === 0 ? (
            <Typography.Text type="secondary" style={{ fontSize: 12, padding: '0 14px' }}>
              还没有对话记录
            </Typography.Text>
          ) : (
            conversations.map((conversation) => (
              <div
                key={conversation.id}
                className={`conv-item${conversation.id === activeId ? ' active' : ''}`}
                onClick={() => void openConversation(conversation.id)}
              >
                <div className="conv-item-body">
                  <div className="conv-item-title">{conversation.title}</div>
                  <div className="conv-item-meta">
                    {conversation.messageCount} 条 ·{' '}
                    {conversation.lastMessageAt
                      ? dayjs(conversation.lastMessageAt).format('MM-DD HH:mm')
                      : '—'}
                  </div>
                </div>
                <div onClick={(event) => event.stopPropagation()}>
                  <Popconfirm
                    title="删除这个对话？"
                    description="删除后不会出现在列表中。"
                    okText="删除"
                    cancelText="取消"
                    okButtonProps={{ danger: true }}
                    onConfirm={() => void handleDeleteConversation(conversation.id)}
                  >
                    <Button type="text" size="small" icon={<DeleteOutlined />} />
                  </Popconfirm>
                </div>
              </div>
            ))
          )}
        </div>
      </div>

      {/* ---------- chat ---------- */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
        <div className="chat-header">
          <Typography.Text strong>AI 交易顾问</Typography.Text>
          {status?.available ? (
            <>
              <Tag color="green">已就绪</Tag>
              <Tag>{status.model}</Tag>
              <Tag color="blue">{status.registeredTools.length} 个工具</Tag>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                回答下方的调用链可以展开，看到它到底查了什么
              </Typography.Text>
            </>
          ) : status ? (
            <Tag color="orange">未配置 API 密钥</Tag>
          ) : (
            <Spin size="small" />
          )}
        </div>

        <div ref={scrollRef} className="chat-scroll" style={{ flex: 1 }}>
          {loadingHistory ? (
            <div style={{ textAlign: 'center', paddingTop: 80 }}>
              <Spin />
            </div>
          ) : messages.length === 0 ? (
            <div style={{ maxWidth: 560, margin: '50px auto 0' }}>
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
                    onClick={() => void send(question)}
                    styles={{ body: { padding: '10px 14px' } }}
                  >
                    <Typography.Text>{question}</Typography.Text>
                  </Card>
                ))}
              </Space>
            </div>
          ) : (
            <div style={{ maxWidth: 880, margin: '0 auto' }}>
              <Space direction="vertical" size={18} style={{ width: '100%' }}>
                {messages.map((message, index) => (
                  <div key={message.id ?? `local-${index}`}>
                    {message.role === 'user' ? (
                      <div className="chat-bubble user">{message.content}</div>
                    ) : (
                      <>
                        <div className="chat-bubble assistant">
                          <MarkdownText content={message.content} />
                        </div>

                        {message.toolCalls.length > 0 && (
                          <Collapse
                            size="small"
                            ghost
                            style={{ marginTop: 4 }}
                            items={[
                              {
                                key: 'tools',
                                label: (
                                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                    查看调用链（{message.toolCalls.length} 次工具调用
                                    {message.iterations ? `，${message.iterations} 轮` : ''}）
                                  </Typography.Text>
                                ),
                                children: (
                                  <Space direction="vertical" size={10} style={{ width: '100%' }}>
                                    {message.toolCalls.map((call, callIndex) => (
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

                        {message.usage && (
                          <Typography.Text
                            type="secondary"
                            style={{ fontSize: 12, display: 'block', marginTop: 4 }}
                          >
                            输入 {message.usage.inputTokens} / 输出 {message.usage.outputTokens} tokens
                          </Typography.Text>
                        )}
                      </>
                    )}
                  </div>
                ))}

                {sending && (
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

        <div className="chat-composer">
          <div style={{ maxWidth: 880, margin: '0 auto' }}>
            <Space.Compact style={{ width: '100%' }}>
              <Input.TextArea
                value={input}
                onChange={(event) => setInput(event.target.value)}
                placeholder="输入问题，Enter 发送，Shift+Enter 换行"
                autoSize={{ minRows: 1, maxRows: 4 }}
                disabled={sending}
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
                loading={sending}
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
    </div>
  )
}
