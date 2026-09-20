import { useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Badge,
  Card,
  Col,
  Empty,
  Row,
  Segmented,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'antd'
import { useQuery } from '@tanstack/react-query'
import dayjs from 'dayjs'
import type { EChartsCoreOption } from 'echarts/core'
import EChart from '@/components/EChart'
import { fetchQuotes, fetchSeries, type SeriesType } from '@/api/market'
import { fetchCategoryTree } from '@/api/inventory'
import { useAuthStore } from '@/store/auth'
import type { CategoryNode, EntityId, QuoteRow } from '@/types/api'

function flattenLeaves(nodes: CategoryNode[], depth = 0): { id: EntityId; label: string }[] {
  const options: { id: EntityId; label: string }[] = []
  for (const node of nodes) {
    if (node.children.length === 0) {
      options.push({ id: node.id, label: `${'　'.repeat(depth)}${node.name}` })
    } else {
      options.push(...flattenLeaves(node.children, depth + 1))
    }
  }
  return options
}

const SERIES_OPTIONS = [
  { label: '成交均价', value: 'TRADE_PRICE' },
  { label: '成交量', value: 'TRADE_VOLUME' },
  { label: '挂牌量', value: 'LISTING_VOLUME' },
  { label: '在库量', value: 'INVENTORY' },
]

export default function MarketPage() {
  const accessToken = useAuthStore((state) => state.accessToken)
  const [seriesType, setSeriesType] = useState<SeriesType>('TRADE_PRICE')
  const [categoryId, setCategoryId] = useState<EntityId | undefined>(undefined)
  const [days, setDays] = useState(30)
  const [liveCount, setLiveCount] = useState(0)
  const [lastEvent, setLastEvent] = useState<string | null>(null)

  const { data: quotes = [], isLoading: quotesLoading } = useQuery({
    queryKey: ['market-quotes'],
    queryFn: () => fetchQuotes(180),
  })

  const { data: series, isLoading: seriesLoading } = useQuery({
    queryKey: ['market-series', seriesType, categoryId, days],
    queryFn: () => fetchSeries(seriesType, categoryId, days),
  })

  const { data: categories = [] } = useQuery({
    queryKey: ['category-tree'],
    queryFn: fetchCategoryTree,
  })

  const categoryOptions = useMemo(() => flattenLeaves(categories), [categories])

  /**
   * Live trade feed.
   *
   * <p>EventSource rather than WebSocket: this is a one-way stream, and SSE
   * reconnects on its own. The token goes in the query string because
   * EventSource cannot set headers — the trade-off is recorded here rather than
   * hidden, since a token in a URL can end up in access logs.
   */
  useEffect(() => {
    if (!accessToken) return
    const source = new EventSource(`/api/market/stream?token=${encodeURIComponent(accessToken)}`)

    source.addEventListener('connected', () => setLiveCount((n) => n + 1))
    source.addEventListener('trade', (event) => {
      const payload = JSON.parse((event as MessageEvent).data) as Record<string, unknown>
      setLastEvent(
        `${payload.commodityName} ${payload.quantity}${payload.unit} @ ${payload.price}`,
      )
    })
    source.onerror = () => {
      // EventSource retries by itself; nothing to do but let it.
    }
    return () => source.close()
  }, [accessToken])

  const chartOption = useMemo<EChartsCoreOption>(() => {
    const points = series?.points ?? []
    const dates = points.map((p) => dayjs(p.time).format('MM-DD'))
    const values = points.map((p) => p.value)
    const volumes = points.map((p) => p.volume ?? 0)
    const isBar = series?.kind === 'bar'

    return {
      tooltip: {
        trigger: 'axis',
        formatter: (params: unknown) => {
          const list = params as { dataIndex: number }[]
          if (!list?.length) return ''
          const point = points[list[0].dataIndex]
          if (!point || point.value === null) {
            return `${dates[list[0].dataIndex]}<br/>当日无成交`
          }
          return [
            dayjs(point.time).format('YYYY-MM-DD'),
            `${series?.label}：<b>${point.value}</b> ${series?.unit}`,
            `成交笔数：${point.tradeCount}`,
            `成交量：${point.volume ?? 0}`,
          ].join('<br/>')
        },
      },
      grid: { left: 70, right: 60, top: 40, bottom: 60 },
      xAxis: { type: 'category', data: dates, boundaryGap: isBar },
      yAxis: [
        { type: 'value', name: series?.unit ?? '', scale: true },
        { type: 'value', name: '成交量', position: 'right', splitLine: { show: false } },
      ],
      dataZoom: [
        { type: 'inside' },
        { type: 'slider', height: 18, bottom: 12 },
      ],
      series: [
        isBar
          ? {
              name: series?.label,
              type: 'bar',
              data: values,
              itemStyle: { color: '#1f5eff' },
            }
          : {
              name: series?.label,
              type: 'line',
              data: values,
              // connectNulls false: a day with no trades must break the line
              // rather than be bridged, or the chart implies trading that
              // never happened.
              connectNulls: false,
              symbolSize: 6,
              lineStyle: { width: 2, color: '#1f5eff' },
              itemStyle: { color: '#1f5eff' },
              areaStyle: { opacity: 0.06 },
            },
        isBar
          ? { name: '笔数', type: 'line', yAxisIndex: 1, data: points.map((p) => p.tradeCount), symbolSize: 4 }
          : { name: '成交量', type: 'bar', yAxisIndex: 1, data: volumes, itemStyle: { color: '#d6e0ff' } },
      ],
    }
  }, [series])

  const columns = [
    { title: '品种', dataIndex: 'categoryName', width: 120 },
    {
      title: '最新价',
      dataIndex: 'latestPrice',
      width: 130,
      align: 'right' as const,
      render: (value: number | null, row: QuoteRow) =>
        value === null ? (
          <Typography.Text type="secondary">尚无成交</Typography.Text>
        ) : (
          <Space size={4}>
            <Typography.Text strong>{value}</Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: 11 }}>
              {row.unit}
            </Typography.Text>
          </Space>
        ),
    },
    {
      title: '较上一笔',
      dataIndex: 'change',
      width: 120,
      align: 'right' as const,
      render: (value: number | null) => {
        if (value === null || value === 0) return <Typography.Text type="secondary">—</Typography.Text>
        const up = value > 0
        return (
          <Typography.Text style={{ color: up ? '#cf1322' : '#389e0d' }}>
            {up ? '▲' : '▼'} {Math.abs(value).toFixed(2)}
          </Typography.Text>
        )
      },
    },
    {
      title: '涨跌幅',
      dataIndex: 'changePercent',
      width: 100,
      align: 'right' as const,
      render: (value: number | null) => {
        if (value === null || value === 0) return '—'
        const up = value > 0
        return (
          <span style={{ color: up ? '#cf1322' : '#389e0d' }}>
            {up ? '+' : ''}
            {value}%
          </span>
        )
      },
    },
    {
      title: '成交笔数',
      dataIndex: 'tradeCount',
      width: 100,
      align: 'right' as const,
      render: (value: number) => (
        <Tag color={value <= 2 ? 'warning' : 'default'}>{value} 笔</Tag>
      ),
    },
    {
      title: '累计成交量',
      dataIndex: 'volume',
      width: 120,
      align: 'right' as const,
      render: (value: number | null, row: QuoteRow) =>
        value === null ? '—' : `${value} ${row.unit}`,
    },
    {
      title: '最近成交',
      dataIndex: 'lastTradedAt',
      width: 150,
      render: (value: string | null) =>
        value ? (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {dayjs(value).format('YYYY-MM-DD HH:mm')}
          </Typography.Text>
        ) : (
          '—'
        ),
    },
  ]

  return (
    <div style={{ padding: 24, maxWidth: 1500, margin: '0 auto' }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          marginBottom: 16,
        }}
      >
        <div>
          <Typography.Title level={4} style={{ margin: 0 }}>
            行情
          </Typography.Title>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            现货市场成交稀疏，展示的是成交均价与成交量，不是 K 线
          </Typography.Text>
        </div>
        <Space>
          <Badge status="processing" text="实时推送已连接" />
          {lastEvent && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              最新成交：{lastEvent}
            </Typography.Text>
          )}
        </Space>
      </div>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="为什么不是 K 线"
        description="期货每分钟都有成交，K 线密集且有意义。现货一单一议，冷门品种一天可能只有两三笔，
          画成蜡烛图会得到一张几乎全空的格子。这里用均价线加成交量柱，并能看到每天的成交笔数——
          一个由 1 笔成交算出的均价和一个由 40 笔算出的均价，在图上必须能区分开。"
      />

      <Card size="small" title="品种行情" style={{ marginBottom: 16 }}>
        <Table
          rowKey="categoryId"
          size="small"
          loading={quotesLoading}
          dataSource={quotes}
          columns={columns}
          pagination={false}
          locale={{ emptyText: <Empty description="还没有成交记录" /> }}
        />
      </Card>

      <Card
        size="small"
        title={
          <Space wrap>
            <span>{series?.label ?? '价格曲线'}</span>
            <Segmented
              size="small"
              value={seriesType}
              onChange={(value) => setSeriesType(value as SeriesType)}
              options={SERIES_OPTIONS}
            />
            <Select
              size="small"
              allowClear
              placeholder="全部品种"
              style={{ width: 160 }}
              value={categoryId}
              onChange={setCategoryId}
              options={categoryOptions.map((o) => ({ value: o.id, label: o.label }))}
            />
            <Segmented
              size="small"
              value={days}
              onChange={(value) => setDays(value as number)}
              options={[
                { label: '7天', value: 7 },
                { label: '30天', value: 30 },
                { label: '90天', value: 90 },
              ]}
            />
          </Space>
        }
      >
        {series && series.points.some((p) => p.value !== null) ? (
          <>
            <EChart option={chartOption} height={360} loading={seriesLoading} />
            <Row gutter={16} style={{ marginTop: 12 }}>
              <Col span={6}>
                <Statistic
                  title="区间成交笔数"
                  value={series.points.reduce((sum, p) => sum + p.tradeCount, 0)}
                  suffix="笔"
                  valueStyle={{ fontSize: 20 }}
                />
              </Col>
              <Col span={6}>
                <Statistic
                  title="区间成交量"
                  value={series.points.reduce((sum, p) => sum + Number(p.volume ?? 0), 0)}
                  suffix="吨"
                  valueStyle={{ fontSize: 20 }}
                />
              </Col>
              <Col span={12}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  折线中断处表示当日无成交。鼠标悬停可看到每个点的成交笔数——
                  均价背后有几笔成交，决定了这个数字有多可信。
                </Typography.Text>
              </Col>
            </Row>
          </>
        ) : (
          <Empty description={seriesLoading ? '加载中' : '该区间内没有成交数据，先去做一笔交易'} />
        )}
      </Card>
    </div>
  )
}
