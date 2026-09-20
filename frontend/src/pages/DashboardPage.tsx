import { Card, Col, Row, Statistic, Tag, Typography, List, Space } from 'antd'
import { CheckCircleFilled, ClockCircleOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { fetchAdvisorStatus } from '@/api/advisor'
import { useAuthStore } from '@/store/auth'

const ROADMAP = [
  { title: '工程骨架 / 数据库迁移 / 认证授权', done: true },
  { title: 'AI 顾问（工具调用型 Agent）', done: true },
  { title: '品类树与电子库存单', done: false },
  { title: '挂牌 / 摘牌 / 协议交易', done: false },
  { title: '合同签署与资金冻结', done: false },
  { title: '行情聚合与实时推送', done: false },
]

export default function DashboardPage() {
  const user = useAuthStore((state) => state.user)

  const { data: advisor } = useQuery({
    queryKey: ['advisor-status'],
    queryFn: fetchAdvisorStatus,
  })

  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        你好，{user?.realName || user?.username}
      </Typography.Title>
      <Typography.Text type="secondary">
        {user?.platformOperator
          ? '当前是平台运营账号，未绑定企业'
          : `${user?.enterpriseName ?? ''} · 交易席位 ${user?.traderCode ?? '—'}`}
      </Typography.Text>

      <Row gutter={16} style={{ marginTop: 24 }}>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic
              title="后端服务"
              value="运行中"
              valueStyle={{ color: '#389e0d', fontSize: 22 }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic
              title="AI 顾问"
              value={advisor ? (advisor.available ? '已就绪' : '未配置密钥') : '检测中'}
              valueStyle={{
                color: advisor?.available ? '#389e0d' : '#d46b08',
                fontSize: 22,
              }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic
              title="已注册顾问工具"
              value={advisor?.registeredTools.length ?? 0}
              suffix="个"
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col xs={24} lg={12}>
          <Card title="开发进度" size="small">
            <List
              size="small"
              dataSource={ROADMAP}
              renderItem={(item) => (
                <List.Item>
                  <Space>
                    {item.done ? (
                      <CheckCircleFilled style={{ color: '#52c41a' }} />
                    ) : (
                      <ClockCircleOutlined style={{ color: '#bfbfbf' }} />
                    )}
                    <Typography.Text type={item.done ? undefined : 'secondary'}>
                      {item.title}
                    </Typography.Text>
                  </Space>
                  {item.done && <Tag color="green">已完成</Tag>}
                </List.Item>
              )}
            />
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card title="顾问配置" size="small">
            {advisor ? (
              <Space direction="vertical" size={6} style={{ width: '100%' }}>
                <div>
                  <Typography.Text type="secondary">模型：</Typography.Text>
                  <Typography.Text code>{advisor.model}</Typography.Text>
                </div>
                <div>
                  <Typography.Text type="secondary">端点：</Typography.Text>
                  <Typography.Text code style={{ fontSize: 12 }}>
                    {advisor.endpoint}
                  </Typography.Text>
                </div>
                <div>
                  <Typography.Text type="secondary">已注册工具：</Typography.Text>
                  <Space size={4} wrap style={{ marginTop: 4 }}>
                    {advisor.registeredTools.map((tool) => (
                      <Tag key={tool} color="blue">
                        {tool}
                      </Tag>
                    ))}
                  </Space>
                </div>
                <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 8, marginBottom: 0 }}>
                  顾问不直接访问数据库。它只能调用上面这些工具，而工具内部从登录态取企业 ID，
                  因此无法读取其它企业的数据。
                </Typography.Paragraph>
              </Space>
            ) : (
              <Typography.Text type="secondary">读取中…</Typography.Text>
            )}
          </Card>
        </Col>
      </Row>
    </div>
  )
}
