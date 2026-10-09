import { identityKey } from '@/store/auth'
import { useEffect, useRef, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Collapse,
  Empty,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd'
import { DeleteOutlined, PlusOutlined, SendOutlined } from '@ant-design/icons'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import dayjs from 'dayjs'
import {
  createConversation,
  cancelAdvisorRun,
  deleteConversation,
  fetchAdvisorStatus,
  getConversation,
  listConversations,
  sendMessage,
  updateConversationContext,
} from '@/api/advisor'
import MarkdownText from '@/components/MarkdownText'
import type { AdvisorEngine, AdvisorKnowledgeReference, EntityId, MessageView } from '@/types/api'

const SAMPLE_QUESTIONS = [
  '找电解铜，对比挂牌单价、剩余数量和交收仓库，给我查看挂牌入口。',
  '采购20吨电解铜，优先上海交收，按单价从低到高筛选。',
  '自提和送到有什么区别？运费、磅差和质量异议应该怎么约定？',
  '我有哪些订单或合同需要处理？',
]

const SOURCE_NAMES: Record<string, string> = {
  find_purchase_options: '在售货物筛选', get_listing_details: '挂牌详情',
  query_market_listings: '公开挂牌', estimate_delivery_cost: '运输费用估算',
  query_market_price: '平台成交行情', query_price_trend: '成交价格趋势',
  search_platform_rules: '平台规则', query_my_inventory: '本企业库存',
  summarise_my_inventory: '本企业库存汇总', list_my_listings: '本企业挂牌',
  list_my_orders: '本企业订单', get_order_detail: '本企业订单详情',
  list_my_contracts: '本企业合同', get_contract_detail: '合同条款',
  list_my_tasks: '本企业待办', query_my_enterprise: '本企业资料', query_team_members: '本企业成员',
}

function readableEvidence(output: string) {
  try { return JSON.stringify(JSON.parse(output), null, 2) } catch { return output }
}

  /** 等待期间和失败时显示的占位回合。永不落库。 */
function localTurn(role: 'user' | 'assistant', content: string): MessageView {
  return {
    id: null,
    role,
    content,
    products: [],
    knowledge: [],
    toolCalls: [],
    iterations: null,
    usage: null,
    createdAt: new Date().toISOString(),
  }
}

export default function AdvisorPage() {
  const [engine, setEngine] = useState<AdvisorEngine>('spring-ai')
  const [activeId, setActiveId] = useState<EntityId | null>(null)
  const [messages, setMessages] = useState<MessageView[]>([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [loadingHistory, setLoadingHistory] = useState(false)
  const [contextNote, setContextNote] = useState('')
  const [contextDraft, setContextDraft] = useState('')
  const [contextOpen, setContextOpen] = useState(false)
  const [savingContext, setSavingContext] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [knowledgeSource, setKnowledgeSource] = useState<AdvisorKnowledgeReference | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const queryClient = useQueryClient()

  /**
   * 防止在 React 用新的 `sending` 状态重新渲染之前，第二次提交挤进来。
   *
   * <p>状态更新是异步的，所以在 `await createConversation()` 期间 `sending` 仍是
   * false，第二次点击会通过检查。结果就是创建出两个会话、同一个问题被问了两次。
   * ref 是同步变更的，所以它把这个窗口彻底关上。
   */
  const sendingRef = useRef(false)

  /** 镜像 activeId，供那些活得比发起它们的那次渲染更久的回调使用。 */
  const activeIdRef = useRef<EntityId | null>(null)
  const engineRef = useRef<AdvisorEngine>('spring-ai')
  const draftEngineChosenRef = useRef(false)
  const pendingIdRef = useRef<EntityId | null>(null)

  const { data: conversations = [] } = useQuery({
    queryKey: identityKey('conversations'),
    queryFn: listConversations,
  })

  const { data: status } = useQuery({
    queryKey: identityKey('advisor-status'),
    queryFn: fetchAdvisorStatus,
    refetchInterval: 30_000,
  })

  useEffect(() => {
    if (!status?.defaultEngine || activeIdRef.current !== null || sendingRef.current || draftEngineChosenRef.current) return
    engineRef.current = status.defaultEngine
    setEngine(status.defaultEngine)
  }, [status?.defaultEngine])

  useEffect(() => {
    const node = scrollRef.current
    if (node) {
      node.scrollTop = node.scrollHeight
    }
  }, [messages, sending])

  const refreshList = () => queryClient.invalidateQueries({ queryKey: identityKey('conversations') })

  const openConversation = async (id: EntityId) => {
    setHistoryOpen(false)
    setActiveId(id)
    activeIdRef.current = id
    setLoadingHistory(true)
    try {
      const detail = await getConversation(id)
      if (activeIdRef.current === id) {
        setEngine(detail.engine ?? 'spring-ai')
        engineRef.current = detail.engine ?? 'spring-ai'
        setMessages(detail.messages)
        setContextNote(detail.contextNote ?? '')
      }
    } catch {
      if (activeIdRef.current === id) setMessages([])
    } finally {
      if (activeIdRef.current === id) setLoadingHistory(false)
    }
  }

  /**
   * 清空视图，而不是建一行记录。会话是在第一个问题时才创建的，所以在这里点一下又
   * 改主意，列表里什么都不会留下。
   */
  const handleNewConversation = () => {
    const next = status?.defaultEngine ?? 'spring-ai'
    draftEngineChosenRef.current = false
    engineRef.current = next
    setEngine(next)
    setHistoryOpen(false)
    setActiveId(null)
    activeIdRef.current = null
    setMessages([])
    setInput('')
    setContextNote('')
    setLoadingHistory(false)
  }

  const handleDeleteConversation = async (id: EntityId) => {
    await deleteConversation(id)
    await refreshList()
    if (activeIdRef.current === id) {
      handleNewConversation()
    }
  }

  const changeEngine = (next: AdvisorEngine) => {
    handleNewConversation()
    draftEngineChosenRef.current = true
    engineRef.current = next
    setEngine(next)
  }
  const engineStatus = status?.engines?.find(item => item.engine === engine)
  const engineAvailable = engineStatus?.available ?? (engine === 'spring-ai' && status?.available)
  const engineLabel = engine === 'langchain' ? 'LangChain' : 'Spring AI'

  const send = async (text?: string) => {
    // 那道同步守卫，在任何其他事情发生之前先检查。
    if (sendingRef.current) return

    const content = (text ?? input).trim()
    if (!content) return
    if (content.length > 4000) return

    sendingRef.current = true
    setSending(true)

    let conversationId = activeId
    try {
      // 会话是在第一个问题时才懒创建的，所以点「新建对话」不会往列表里塞一堆空会话。
      if (conversationId === null) {
        const detail = await createConversation(undefined, engineRef.current)
        conversationId = detail.id
        setActiveId(conversationId)
        activeIdRef.current = conversationId
        if (contextNote) await updateConversationContext(conversationId, contextNote)
      }

      setInput('')
      setMessages((prev) => [...prev, localTurn('user', content)])
      pendingIdRef.current = conversationId

      await sendMessage(conversationId, content)
      await refreshList()

      // 从服务端重新拉取对话记录，而不是在本地把回复追加进去。这样列表永远和实际
      // 存储的内容一致，而且用户切走之后才到达的回复不会追加到另一个会话的消息里。
      if (activeIdRef.current === conversationId) {
        const detail = await getConversation(conversationId)
        setMessages(detail.messages)
        setContextNote(detail.contextNote ?? '')
      }
    } catch {
      if (conversationId !== null && activeIdRef.current === conversationId) {
        // 这个回合失败了，所以什么都没存下，也不会有答案来了。把那句乐观追加的问题
        // 删掉并说明原因，而不是留下一个永远不会被回答的问题。
        setMessages((prev) => [
          ...prev.slice(0, -1),
          localTurn('assistant', '本轮请求失败，请稍后重试。'),
        ])
      }
    } finally {
      sendingRef.current = false
      pendingIdRef.current = null
      setSending(false)
    }
  }

  const saveContext = async () => {
    setSavingContext(true)
    try {
      if (activeId) await updateConversationContext(activeId, contextDraft.trim())
      setContextNote(contextDraft.trim())
      setContextOpen(false)
    } finally { setSavingContext(false) }
  }

  return (
    <div style={{ display: 'flex', height: 'calc(100vh - 56px)' }}>
      {/* ---------- 会话列表 ---------- */}
      {historyOpen && <button className="advisor-history-backdrop" aria-label="关闭会话列表" onClick={() => setHistoryOpen(false)} />}
      <div className={`conv-sidebar${historyOpen ? ' is-open' : ''}`}>
        <div style={{ padding: 12 }}>
          <Button type="primary" block disabled={sending} icon={<PlusOutlined />} onClick={handleNewConversation}>
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
              >
                <button className="conv-item-body conv-open" type="button" disabled={sending} aria-label={`打开会话：${conversation.title}`} aria-current={conversation.id === activeId ? 'true' : undefined} onClick={() => void openConversation(conversation.id)}>
                  <div className="conv-item-title">{conversation.title}</div>
                  <div className="conv-item-meta">
                    {conversation.engine === 'langchain' ? 'LangChain' : 'Spring AI'} · {conversation.messageCount} 条 ·{' '}
                    {conversation.lastMessageAt
                      ? dayjs(conversation.lastMessageAt).format('MM-DD HH:mm')
                      : '—'}
                  </div>
                </button>
                <div onClick={(event) => event.stopPropagation()}>
                  <Popconfirm
                    title="删除这个对话？"
                    description="删除后不会出现在列表中。"
                    okText="删除"
                    cancelText="取消"
                    okButtonProps={{ danger: true }}
                    onConfirm={() => void handleDeleteConversation(conversation.id)}
                  >
                    <Button type="text" size="small" icon={<DeleteOutlined />} aria-label={`删除会话：${conversation.title}`} />
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
          <Button className="advisor-history-button" size="small" onClick={() => setHistoryOpen(true)}>会话记录</Button>
          <Typography.Text strong>AI 交易顾问</Typography.Text>
          <Select aria-label="顾问引擎" value={engine} disabled={sending || loadingHistory} onChange={changeEngine}
            options={[{ value: 'spring-ai', label: 'Spring AI' }, { value: 'langchain', label: 'LangChain' }]} style={{ width: 130 }} />
          {engineAvailable ? (
            <>
              <Tag color="green">已启用</Tag>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                查货 · 比价 · 交付 · 订单与合同
              </Typography.Text>
            </>
          ) : status ? (
            <Tag color="orange">{engineStatus && !engineStatus.ready ? '引擎暂不可用' : status.enabled ? '平台模型待配置' : '顾问已停用'}</Tag>
          ) : (
            <Spin size="small" />
          )}
          <Button size="small" disabled={sending || loadingHistory} onClick={() => { setContextDraft(contextNote); setContextOpen(true) }}>采购需求{contextNote ? ' · 已保存' : ''}</Button>
        </div>
        {contextNote && <div className="advisor-context-note"><Typography.Text type="secondary" ellipsis>本会话需求：{contextNote}</Typography.Text></div>}

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
                        <div className="advisor-answer-meta">现货通顾问 · {engineLabel} · {dayjs(message.createdAt).format('HH:mm')}</div>
                        <div className="chat-bubble assistant">
                          <MarkdownText content={message.content} allowedLinks={(message.products ?? []).map(product => `/trading?listing=${product.id}`)} />
                        </div>
                        {(message.products ?? []).length > 0 && <div className="advisor-products" aria-label="查询到的挂牌">
                          {message.products.map(product => <article className="advisor-product" key={product.id}>
                            <div className="advisor-product-top"><Typography.Text strong>{product.title}</Typography.Text><Tag>{product.delivery}</Tag></div>
                            <strong className="advisor-product-price">{product.price}</strong>
                            <div>可摘牌 {product.quantity}</div>
                            <Typography.Text type="secondary">{product.seller}</Typography.Text>
                            <Typography.Text type="secondary">{product.warehouse}</Typography.Text>
                            <Link className="advisor-product-link" to={`/trading?listing=${product.id}`}>查看挂牌 →</Link>
                          </article>)}
                        </div>}

                        {(message.knowledge ?? []).length > 0 && <div className="advisor-knowledge" aria-label="检索原文依据">
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>本轮检索依据 · 点击核对原文</Typography.Text>
                          <Space wrap size={[6, 6]}>
                            {message.knowledge.map(reference => <Button key={reference.chunkId} size="small"
                              onClick={() => setKnowledgeSource(reference)}>{reference.title} · 第 {reference.chunkIndex + 1} 段</Button>)}
                          </Space>
                        </div>}
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
                                    查看数据依据（{message.toolCalls.length} 项查询）
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
                                            <Tag>{SOURCE_NAMES[call.name] ?? '平台业务查询'}</Tag>
                                          </Space>
                                        }
                                        styles={{ body: { padding: 10 } }}
                                      >
                                        <pre className="tool-output">{readableEvidence(call.output)}</pre>
                                      </Card>
                                    ))}
                                  </Space>
                                ),
                              },
                            ]}
                          />
                        )}

                      </>
                    )}
                  </div>
                ))}

                {sending && (
                  <div className="chat-bubble assistant" style={{ display: 'flex', gap: 10 }}>
                    <Spin size="small" />
                    <Typography.Text type="secondary">
                      正在核对货物和平台记录，请稍候…
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
                maxLength={4000}
                showCount
                aria-label="向交易顾问提问"
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
              {sending && engine === 'langchain' && <Button onClick={() => { if (pendingIdRef.current) void cancelAdvisorRun(pendingIdRef.current) }}>
                停止本轮
              </Button>}
            </Space.Compact>
            <div className="advisor-composer-hint">会话固定使用创建时的引擎，切换引擎会开始新对话。长期条件可存入“采购需求”。报价与余量以挂牌详情为准。</div>

            {status && !engineAvailable && (
              <Alert
                type="warning"
                showIcon
                style={{ marginTop: 10 }}
                message={engineStatus && !engineStatus.ready ? `${engineLabel} 引擎暂不可用` : status.enabled ? '平台模型待配置' : 'AI 顾问已停用'}
                description="请联系平台管理员在管理后台配置模型服务。本地推理服务和 OpenAI 兼容云端 API 均可使用。"
              />
            )}
          </div>
        </div>
      </div>
      <Modal title="本会话采购需求" open={contextOpen} onCancel={() => setContextOpen(false)} onOk={() => void saveContext()} confirmLoading={savingContext} okText="保存需求">
        <p>填写商品、规格、数量、预算和目的地，顾问会在本会话中参考这些条件。新问题中的条件优先。留空保存可清除。</p>
        <Input.TextArea aria-label="采购需求内容" value={contextDraft} onChange={event => setContextDraft(event.target.value)} maxLength={2000} showCount rows={6} placeholder="例如：电解铜20吨，交收到上海，优先送到；请比较单价和总费用。" />
      </Modal>
      <Modal title={knowledgeSource?.title ?? '检索原文'} open={knowledgeSource !== null}
        onCancel={() => setKnowledgeSource(null)} footer={<Button onClick={() => setKnowledgeSource(null)}>关闭</Button>}>
        {knowledgeSource && <>
          <Typography.Paragraph type="secondary">
            {knowledgeSource.docCode} · {knowledgeSource.version} · 第 {knowledgeSource.chunkIndex + 1} 段
            <br />来源：{knowledgeSource.source}
          </Typography.Paragraph>
          <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{knowledgeSource.content}</Typography.Paragraph>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>这是本次回答检索时保存的原文快照。</Typography.Text>
        </>}
      </Modal>
    </div>
  )
}
