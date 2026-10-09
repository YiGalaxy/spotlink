import { useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Input,
  List,
  Row,
  Space,
  Statistic,
  Tag,
  Typography,
  Result,
  message,
} from 'antd'
import { SearchOutlined, ThunderboltOutlined } from '@ant-design/icons'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { embedPending, fetchKnowledgeStats, searchKnowledge } from '@/api/knowledge'
import type { KnowledgeHit } from '@/api/knowledge'
import { identityKey, useAuthStore } from '@/store/auth'
import { can } from '@/utils/permissions'

const SAMPLES = ['磅差怎么算', '保证金比例是多少', '交易时间是什么时候', '电子库存单是什么']

export default function KnowledgePage() {
  const [question, setQuestion] = useState('')
  const [hits, setHits] = useState<KnowledgeHit[] | null>(null)
  const [searching, setSearching] = useState(false)
  const [embedding, setEmbedding] = useState(false)
  const queryClient = useQueryClient()
  // 知识库检索测试和嵌入维护都属于运营后台，服务端还会按权限码拦截。
  const user = useAuthStore(state => state.user)
  const signedIn = can(user, 'admin:knowledge:embed')

  const { data: stats } = useQuery({
    queryKey: identityKey('knowledge-stats'),
    queryFn: fetchKnowledgeStats,
    enabled: can(user, 'admin:knowledge'),
  })

  const runSearch = async (q: string) => {
    const text = q.trim()
    if (!text) return
    setQuestion(text)
    setSearching(true)
    try {
      setHits(await searchKnowledge(text, 5))
    } finally {
      setSearching(false)
    }
  }

  const runEmbed = async () => {
    setEmbedding(true)
    try {
      const result = await embedPending(200)
      void message.success(`已补算 ${result.embedded} 个分块的向量`)
      void queryClient.invalidateQueries({ queryKey: identityKey('knowledge-stats') })
    } finally {
      setEmbedding(false)
    }
  }

  const pending = stats?.pending ?? 0
  const noVector = (stats?.chunks ?? 0) > 0 && (stats?.embedded ?? 0) === 0

  if (!can(user, 'admin:knowledge')) return <Result status="403" title="无权访问知识库管理" />

  return (
    <div className="business-page">
      <div style={{ marginBottom: 16 }}>
        <Typography.Title level={4} style={{ margin: 0 }}>
          知识库
        </Typography.Title>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          AI 顾问回答「平台规则」类问题的依据。数据问答不走这里，走工具调用查数据库。
        </Typography.Text>
      </div>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic title="知识分块" value={stats?.chunks ?? 0} suffix="条" />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic
              title="已生成向量"
              value={stats?.embedded ?? 0}
              suffix="条"
              valueStyle={{ color: (stats?.embedded ?? 0) > 0 ? '#389e0d' : undefined }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic
              title="待补算"
              value={pending}
              suffix="条"
              valueStyle={{ color: pending > 0 ? '#d46b08' : undefined }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic title="嵌入模型" value={stats?.model ?? '—'} valueStyle={{ fontSize: 18 }} />
          </Card>
        </Col>
      </Row>

      {/* 只有拥有知识库维护权限的运营账号才能触发补算；服务端会做最终校验。 */}
      {signedIn && noVector && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="还没有任何分块生成向量"
          description="向量检索当前不可用，只能用关键词匹配召回，对同义改写的问法效果会差很多。确认本地嵌入服务（Ollama）已启动并拉取了对应模型，然后点「补算向量」。"
          action={
            <Button size="small" icon={<ThunderboltOutlined />} loading={embedding} onClick={() => void runEmbed()}>
              补算向量
            </Button>
          }
        />
      )}

      {signedIn && !noVector && pending > 0 && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message={`还有 ${pending} 个分块没有向量`}
          description="这些分块仍可通过关键词检索命中，但同义改写的问法会漏。"
          action={
            <Button size="small" icon={<ThunderboltOutlined />} loading={embedding} onClick={() => void runEmbed()}>
              补算向量
            </Button>
          }
        />
      )}

      <Card
        size="small"
        title="检索测试"
        extra={
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            混合检索：向量召回 + 关键词召回，按 RRF 融合排序
          </Typography.Text>
        }
      >
        <Space.Compact style={{ width: '100%', marginBottom: 12 }}>
          <Input
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            placeholder="输入一个关于平台规则的问题，看命中了哪些段落"
            prefix={<SearchOutlined />}
            onPressEnter={() => void runSearch(question)}
          />
          <Button type="primary" loading={searching} onClick={() => void runSearch(question)}>
            检索
          </Button>
        </Space.Compact>

        <Space size={6} wrap style={{ marginBottom: 16 }}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            试试：
          </Typography.Text>
          {SAMPLES.map((sample) => (
            <Tag
              key={sample}
              style={{ cursor: 'pointer' }}
              onClick={() => void runSearch(sample)}
            >
              {sample}
            </Tag>
          ))}
        </Space>

        {hits === null ? (
          <Empty description="输入问题或点上面的示例" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : hits.length === 0 ? (
          <Empty description="没有命中任何段落" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <List
            size="small"
            dataSource={hits}
            renderItem={(hit, index) => (
              <List.Item>
                <List.Item.Meta
                  title={
                    <Space size={8}>
                      <Tag color="blue">#{index + 1}</Tag>
                      <Typography.Text strong>{hit.title}</Typography.Text>
                      <Typography.Text code style={{ fontSize: 12 }}>
                        {hit.docCode}
                      </Typography.Text>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        融合得分 {hit.score}
                      </Typography.Text>
                    </Space>
                  }
                  description={
                    <Typography.Paragraph style={{ marginBottom: 0, fontSize: 13 }}>
                      {hit.content}
                    </Typography.Paragraph>
                  }
                />
              </List.Item>
            )}
          />
        )}
      </Card>

    </div>
  )
}
