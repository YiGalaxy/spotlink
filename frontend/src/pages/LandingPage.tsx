import { Button, Card, Col, Empty, Row, Space, Statistic, Table, Tag, Typography } from 'antd'
import {
  ArrowRightOutlined,
  BookOutlined,
  LineChartOutlined,
  SwapOutlined,
} from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import { fetchPublicStats } from '@/api/public'
import { fetchQuotes } from '@/api/market'
import { fetchMarket } from '@/api/trading'
import { identityKey, useAuthStore } from '@/store/auth'
import type { ListingView, QuoteRow } from '@/types/api'

const PRIMARY = '#1f4e79'

/**
 * 公开首页。
 *
 * <p>照着真实大宗商品交易场所对外亮相的方式来做：市场规模有多大、最近成交在什么
 * 价位、眼下有什么在挂、规则放在哪里。这四件事都是访客在决定要不要申请入会之前
 * 有权知道的——而它们没有一样描述任何单个会员。
 *
 * <p>已登录用户也会落到这里，菜单就在旁边。让首页在登录后消失，等于说交易场所
 * 对自身的介绍只给那些已经不再需要它的人看。
 */
export default function LandingPage() {
  const navigate = useNavigate()
  const accessToken = useAuthStore((state) => state.accessToken)

  const { data: stats } = useQuery({
    queryKey: identityKey('public-stats'),
    queryFn: fetchPublicStats,
  })

  const { data: quotes = [] } = useQuery({
    queryKey: identityKey('market-quotes'),
    queryFn: () => fetchQuotes(180),
  })

  const { data: listings = [] } = useQuery({
    queryKey: identityKey('market-listings'),
    queryFn: () => fetchMarket(),
  })

  // 最近发布的几笔挂牌。首页不是市场本身——它是让你走进市场的理由。
  const featured = [...listings]
    .sort((a, b) => dayjs(b.createdAt).valueOf() - dayjs(a.createdAt).valueOf())
    .slice(0, 6)

  const traded = quotes.filter((q) => q.latestPrice !== null).slice(0, 8)

  return (
    <div>
      {/* ---------- 头条 ---------- */}
      <div
        style={{
          background: `linear-gradient(135deg, ${PRIMARY} 0%, #2c6da3 100%)`,
          color: '#fff',
          padding: '56px 24px 64px',
        }}
      >
        <div style={{ maxWidth: 1200, margin: '0 auto' }}>
          <Typography.Title level={2} style={{ color: '#fff', margin: 0, fontSize: 34 }}>
            现货通 SpotLink
          </Typography.Title>
          <Typography.Paragraph
            style={{ color: 'rgba(255,255,255,.85)', fontSize: 16, marginTop: 12, maxWidth: 620 }}
          >
            大宗商品现货挂牌交易平台。挂牌即要约，摘牌即承诺——没有撮合引擎，
            价格由买卖双方一对一约定。货权与资金全程留痕，每一步都可追溯。
          </Typography.Paragraph>
          <Space size={12} style={{ marginTop: 20 }}>
            <Button
              type="primary"
              size="large"
              icon={<SwapOutlined />}
              onClick={() => navigate('/trading')}
              style={{ background: '#fff', color: PRIMARY, borderColor: '#fff', fontWeight: 600 }}
            >
              进入挂牌大厅
            </Button>
            {accessToken ? (
              <Button
                size="large"
                ghost
                icon={<ArrowRightOutlined />}
                onClick={() => navigate('/dashboard')}
              >
                回到工作台
              </Button>
            ) : (
              <Button size="large" ghost onClick={() => navigate('/login')}>
                登录 / 注册
              </Button>
            )}
          </Space>
        </div>
      </div>

      {/* ---------- 平台数据 ---------- */}
      <div style={{ maxWidth: 1200, margin: '-32px auto 0', padding: '0 24px' }}>
        <Card styles={{ body: { padding: '20px 8px' } }}>
          <Row gutter={[8, 16]}>
            <Col xs={12} md={4}>
              <Statistic title="入驻企业" value={stats?.enterpriseCount ?? 0} suffix="家" />
            </Col>
            <Col xs={12} md={4}>
              <Statistic title="在挂挂牌" value={stats?.openListingCount ?? 0} suffix="笔" />
            </Col>
            <Col xs={12} md={4}>
              <Statistic title="累计成交" value={stats?.tradeCount ?? 0} suffix="笔" />
            </Col>
            <Col xs={12} md={4}>
              <Statistic
                title="累计成交量"
                value={stats?.tradedQuantity ?? 0}
                precision={0}
                suffix="吨"
              />
            </Col>
            <Col xs={12} md={4}>
              <Statistic title="累计成交额" value={stats?.tradedAmountText ?? '0 元'} />
            </Col>
            <Col xs={12} md={4}>
              <Statistic
                title="在库总量"
                value={stats?.inventoryQuantity ?? 0}
                precision={0}
                suffix="吨"
              />
            </Col>
          </Row>
        </Card>
      </div>

      <div style={{ maxWidth: 1200, margin: '0 auto', padding: '32px 24px 48px' }}>
        <Row gutter={[24, 24]}>
          {/* ---------- 最新行情 ---------- */}
          <Col xs={24} lg={10}>
            <Card
              size="small"
              title="最新行情"
              extra={
                <Button type="link" size="small" onClick={() => navigate('/market')}>
                  查看走势 <ArrowRightOutlined />
                </Button>
              }
              styles={{ body: { padding: 0 } }}
            >
              {traded.length === 0 ? (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="暂无成交"
                  style={{ padding: 32 }}
                />
              ) : (
                <Table<QuoteRow>
                  rowKey="categoryId"
                  size="small"
                  pagination={false}
                  dataSource={traded}
                  columns={[
                    { title: '品种', dataIndex: 'categoryName' },
                    {
                      title: '最新价',
                      align: 'right',
                      render: (_, r) => (
                        <span>
                          {r.latestPrice}
                          <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                            {' '}
                            元/{r.unit}
                          </Typography.Text>
                        </span>
                      ),
                    },
                    {
                      title: '涨跌',
                      align: 'right',
                      width: 96,
                      render: (_, r) => {
                        if (r.changePercent === null || r.changePercent === undefined) {
                          return <Typography.Text type="secondary">—</Typography.Text>
                        }
                        const up = r.changePercent >= 0
                        return (
                          <Typography.Text type={up ? 'danger' : 'success'}>
                            {up ? '+' : ''}
                            {r.changePercent.toFixed(2)}%
                          </Typography.Text>
                        )
                      },
                    },
                  ]}
                />
              )}
            </Card>
          </Col>

          {/* ---------- 精选挂牌 ---------- */}
          <Col xs={24} lg={14}>
            <Card
              size="small"
              title="最新挂牌"
              extra={
                <Button type="link" size="small" onClick={() => navigate('/trading')}>
                  进入大厅 <ArrowRightOutlined />
                </Button>
              }
              styles={{ body: { padding: 0 } }}
            >
              {featured.length === 0 ? (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="当前没有在挂的挂牌"
                  style={{ padding: 32 }}
                />
              ) : (
                <Table<ListingView>
                  rowKey="id"
                  size="small"
                  pagination={false}
                  dataSource={featured}
                  columns={[
                    {
                      title: '商品',
                      render: (_, r) => (
                        <div>
                          <Typography.Text strong>{r.commodityName}</Typography.Text>
                          <div>
                            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                              {[r.brand, r.origin].filter(Boolean).join(' · ') || r.categoryName}
                            </Typography.Text>
                          </div>
                        </div>
                      ),
                    },
                    { title: '挂牌方', dataIndex: 'enterpriseName', width: 170 },
                    {
                      title: '价格',
                      align: 'right',
                      width: 130,
                      render: (_, r) =>
                        r.priceType === 'NEGOTIABLE' ? (
                          <Tag>面议</Tag>
                        ) : (
                          <span>
                            {r.price}
                            <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                              {' '}
                              元/{r.unit}
                            </Typography.Text>
                          </span>
                        ),
                    },
                    {
                      title: '可摘',
                      align: 'right',
                      width: 100,
                      render: (_, r) => `${r.remainingQuantity} ${r.unit}`,
                    },
                    {
                      title: '成交方式',
                      width: 110,
                      render: (_, r) => (
                        <Tag color={r.confirmMode === 'MANUAL' ? 'orange' : 'default'}>
                          {r.confirmModeText}
                        </Tag>
                      ),
                    },
                  ]}
                />
              )}
            </Card>
          </Col>
        </Row>

        {/* ---------- 规则在哪里 ---------- */}
        <Row gutter={[16, 16]} style={{ marginTop: 24 }}>
          {[
            {
              icon: <SwapOutlined style={{ fontSize: 22, color: PRIMARY }} />,
              title: '挂牌交易',
              body: '发布挂牌即是发出要约，摘牌即作出承诺。支持「摘牌即成交」与「需挂牌方确认」两种成交方式。',
              action: () => navigate('/trading'),
              actionText: '浏览挂牌',
            },
            {
              icon: <LineChartOutlined style={{ fontSize: 22, color: PRIMARY }} />,
              title: '行情走势',
              body: '平台内成交均价、成交量、挂牌量与在库量，每个点标出背后的成交笔数。',
              action: () => navigate('/market'),
              actionText: '查看行情',
            },
            {
              icon: <BookOutlined style={{ fontSize: 22, color: PRIMARY }} />,
              title: '交易规则',
              body: '挂牌、摘牌、签约、交收、结算的完整流程与各方义务，以条文形式公布，并作为 AI 顾问回答规则类问题的依据。',
              action: () => navigate('/knowledge'),
              actionText: '查阅规则',
            },
          ].map((item) => (
            <Col xs={24} md={8} key={item.title}>
              <Card size="small" style={{ height: '100%' }}>
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  <Space size={10}>
                    {item.icon}
                    <Typography.Text strong style={{ fontSize: 15 }}>
                      {item.title}
                    </Typography.Text>
                  </Space>
                  <Typography.Text type="secondary" style={{ fontSize: 13 }}>
                    {item.body}
                  </Typography.Text>
                  <Button type="link" size="small" style={{ paddingLeft: 0 }} onClick={item.action}>
                    {item.actionText} <ArrowRightOutlined />
                  </Button>
                </Space>
              </Card>
            </Col>
          ))}
        </Row>

        {!accessToken && (
          <Card
            size="small"
            style={{ marginTop: 24, background: '#f6f8fb', borderColor: '#e3e9f2' }}
          >
            <Space
              style={{ width: '100%', justifyContent: 'space-between', flexWrap: 'wrap' }}
              size={12}
            >
              <Typography.Text type="secondary" style={{ fontSize: 13 }}>
                浏览无需登录。挂牌、摘牌、查看库存与使用 AI 顾问需要企业账号。
              </Typography.Text>
              <Button type="primary" size="small" onClick={() => navigate('/login')}>
                登录 / 注册
              </Button>
            </Space>
          </Card>
        )}
      </div>
    </div>
  )
}
