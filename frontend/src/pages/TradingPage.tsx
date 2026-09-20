import { useMemo, useState } from 'react'
import {
  Alert,
  Badge,
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Radio,
  Row,
  Segmented,
  Select,
  Space,
  Steps,
  Table,
  Tabs,
  type TableColumnsType,
  Tag,
  Timeline,
  Typography,
  message,
} from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import { useAuthStore } from '@/store/auth'
import {
  acceptListing,
  cancelOrder,
  closeListing,
  completeOrder,
  confirmOrder,
  draftContract,
  fetchMarket,
  fetchMyListings,
  fetchMyOrders,
  fetchOrderContract,
  fetchOrderHistory,
  publishListing,
  rejectOrder,
  signContract,
  startDelivery,
  type PublishListingPayload,
} from '@/api/trading'
import { fetchCategoryTree, fetchWarehouses, listInventoryNotes } from '@/api/inventory'
import { LIST_PAGINATION, byNumberNullsLast, byTime } from '@/utils/table'
import type { CategoryNode, EntityId, ListingView, OrderView } from '@/types/api'

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

const ORDER_STEPS = ['PENDING_CONFIRM', 'CONFIRMED', 'CONTRACTED', 'DELIVERING', 'COMPLETED']

/** Mirrors OrderStatus.text on the server; used for the filter chips. */
const ORDER_STAGE_TEXT: Record<string, string> = {
  PENDING_CONFIRM: '待挂牌方确认',
  CONFIRMED: '已确认',
  CONTRACTED: '已签约',
  DELIVERING: '交收中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
}
const ORDER_COLOURS: Record<string, string> = {
  PENDING_CONFIRM: 'processing',
  CONFIRMED: 'processing',
  CONTRACTED: 'blue',
  DELIVERING: 'warning',
  COMPLETED: 'success',
  CANCELLED: 'default',
}

export default function TradingPage() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const accessToken = useAuthStore((state) => state.accessToken)
  const user = useAuthStore((state) => state.user)
  const signedIn = Boolean(accessToken)

  /**
   * Whether this account has an enterprise to trade as.
   *
   * <p>Distinct from being signed in. A platform operator is a legitimate
   * account with no tenant, so every tenant-scoped query returns "未绑定企业" —
   * which meant the member tabs appeared, and then errored when opened. Gate
   * on what the tabs need, not on the looser thing that happens to correlate.
   */
  const isMember = signedIn && Boolean(user?.enterpriseId)
  const [acceptTarget, setAcceptTarget] = useState<ListingView | null>(null)
  const [publishOpen, setPublishOpen] = useState(false)
  const [detailOrder, setDetailOrder] = useState<OrderView | null>(null)
  const [sideFilter, setSideFilter] = useState<string | undefined>(undefined)
  const [keyword, setKeyword] = useState('')
  /** 'ACTIVE' | 'FINISHED' | 'ALL' — the top-level split on 我的订单. */
  const [orderPhase, setOrderPhase] = useState<'ACTIVE' | 'FINISHED' | 'ALL'>('ACTIVE')
  /** '' means every status within the chosen phase. */
  const [orderStage, setOrderStage] = useState('')
  const [acceptForm] = Form.useForm()
  const [publishForm] = Form.useForm()
  const publishSide = Form.useWatch('side', publishForm)

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['market'] })
    void queryClient.invalidateQueries({ queryKey: ['my-listings'] })
    void queryClient.invalidateQueries({ queryKey: ['my-orders'] })
    void queryClient.invalidateQueries({ queryKey: ['inventory-notes'] })
    void queryClient.invalidateQueries({ queryKey: ['market-quotes'] })
  }

  const { data: market = [], isLoading: marketLoading } = useQuery({
    queryKey: ['market', sideFilter, keyword],
    // The server has always supported keyword search — browse() has taken one
    // since it was written — but nothing asked for it. With a hall that only
    // grows, filtering is the difference between a list and a haystack.
    queryFn: () => fetchMarket(undefined, sideFilter, keyword || undefined),
  })

  // The marketplace is public; everything below is one enterprise's own
  // business, so a visitor must not even ask. Firing these unauthenticated
  // would 401, and the client's 401 handler treats that as an expired session
  // and bounces the visitor to the login page — turning "browsing" into
  // "ejected".
  const { data: myListings = [] } = useQuery({
    queryKey: ['my-listings'],
    queryFn: fetchMyListings,
    enabled: isMember,
  })

  const { data: myOrders = [] } = useQuery({
    queryKey: ['my-orders'],
    queryFn: () => fetchMyOrders(),
    enabled: isMember,
  })

  const { data: notes = [] } = useQuery({
    queryKey: ['inventory-notes'],
    queryFn: () => listInventoryNotes(),
    enabled: isMember,
  })

  const { data: categories = [] } = useQuery({
    queryKey: ['category-tree'],
    queryFn: fetchCategoryTree,
  })

  const { data: warehouses = [] } = useQuery({
    queryKey: ['warehouses'],
    queryFn: fetchWarehouses,
    enabled: isMember,
  })

  /**
   * How many of this enterprise's orders are waiting on it.
   *
   * <p>Derived from `allowedActions`, the same field the server enforces, so
   * the number on the tab and the buttons inside it cannot disagree. A count
   * computed any other way — by status, say — would be a second opinion about
   * what "pending" means.
   */
  const pendingOrderCount = useMemo(
    () => myOrders.filter((o) => o.allowedActions.length > 0).length,
    [myOrders],
  )

  /**
   * The unfinished statuses, in the order the trade flow goes through them.
   *
   * <p>Written out rather than derived from the data, because the order is the
   * point: 待确认 → 已确认 → 已签约 → 交收中 is the sequence a deal moves
   * through, and a list sorted by whatever statuses happen to be present would
   * put them in an order that means nothing. A stage nobody is in is still
   * shown, with a zero, so the reader can see it was considered.
   */
  const ACTIVE_STAGES = ['PENDING_CONFIRM', 'CONFIRMED', 'CONTRACTED', 'DELIVERING']
  const FINISHED_STAGES = ['COMPLETED', 'CANCELLED']

  const ordersInPhase = useMemo(() => {
    if (orderPhase === 'ACTIVE') {
      return myOrders.filter((o) => ACTIVE_STAGES.includes(o.status))
    }
    if (orderPhase === 'FINISHED') {
      return myOrders.filter((o) => FINISHED_STAGES.includes(o.status))
    }
    return myOrders
  }, [myOrders, orderPhase])

  /**
   * Counts per status, then the rows to show.
   *
   * <p>Two levels on purpose. 「已结束」 and 「进行中」 answer "is this deal still
   * mine to worry about", and the individual statuses answer "which step is it
   * on" — mixing them into one flat list of seven chips is a filter nobody
   * reads. Which statuses belong to which phase comes from the server's own
   * lifecycle rather than from a guess about the sort order.
   */
  const stageCounts = useMemo(() => {
    const counts: Record<string, number> = {}
    for (const order of ordersInPhase) {
      counts[order.status] = (counts[order.status] ?? 0) + 1
    }
    return counts
  }, [ordersInPhase])

  const visibleOrders = useMemo(
    () => (orderStage ? ordersInPhase.filter((o) => o.status === orderStage) : ordersInPhase),
    [ordersInPhase, orderStage],
  )

  const activeCount = useMemo(
    () => myOrders.filter((o) => ACTIVE_STAGES.includes(o.status)).length,
    [myOrders],
  )

  const categoryOptions = useMemo(() => flattenLeaves(categories), [categories])
  /** Only notes with something free can back a SELL listing. */
  const sellableNotes = useMemo(
    () => notes.filter((n) => Number(n.availableQuantity) > 0 && [2, 3, 4].includes(n.status)),
    [notes],
  )

  // ---- mutations ----

  const { mutateAsync: doAccept, isPending: accepting } = useMutation({
    mutationFn: (vars: { id: EntityId; quantity: number; remark?: string }) =>
      acceptListing(vars.id, vars.quantity, vars.remark),
    onSuccess: (order) => {
      void message.success(order.status === 'PENDING_CONFIRM'
        ? `已摘牌，订单 ${order.orderNo} 等待挂牌方确认`
        : `摘牌成功，订单 ${order.orderNo} 已生成`)
      setAcceptTarget(null)
      acceptForm.resetFields()
      invalidate()
    },
  })

  const { mutateAsync: doPublish, isPending: publishing } = useMutation({
    mutationFn: (payload: PublishListingPayload) => publishListing(payload),
    onSuccess: (listing) => {
      void message.success(`挂牌成功：${listing.listingNo}`)
      setPublishOpen(false)
      publishForm.resetFields()
      invalidate()
    },
  })

  const runOrderAction = async (action: string, order: OrderView) => {
    switch (action) {
      case 'confirm':
        await confirmOrder(order.id)
        break
      case 'reject':
        await rejectOrder(order.id, '挂牌方拒绝摘牌')
        break
      case 'deliver':
        await startDelivery(order.id)
        break
      case 'complete':
        await completeOrder(order.id)
        break
      case 'cancel':
        await cancelOrder(order.id, '用户取消')
        break
      default:
        return
    }
    void message.success('操作成功')
    invalidate()
    setDetailOrder(null)
  }

  const handlePublish = async (values: Record<string, unknown>) => {
    const noteId = values.inventoryNoteId as EntityId | undefined
    const note = sellableNotes.find((n) => n.id === noteId)
    await doPublish({
      side: values.side as 'SELL' | 'BUY',
      inventoryNoteId: values.side === 'SELL' ? noteId : undefined,
      categoryId: (values.categoryId as EntityId) ?? note?.categoryId,
      commodityName: (values.commodityName as string) ?? note?.commodityName,
      brand: (values.brand as string) ?? note?.brand ?? undefined,
      origin: (values.origin as string) ?? note?.origin ?? undefined,
      quantity: values.quantity as number,
      unit: values.unit as string | undefined,
      price: values.priceType === 'FIXED' ? (values.price as number) : undefined,
      priceType: values.priceType as 'FIXED' | 'NEGOTIABLE',
      // The server refuses MANUAL on a BUY listing, so the form does not offer
      // it there rather than letting the request fail.
      confirmMode: values.side === 'SELL'
        ? (values.confirmMode as 'AUTO' | 'MANUAL' | undefined)
        : undefined,
      warehouseId: (values.warehouseId as EntityId) ?? note?.warehouseId,
      deliveryMethod: values.deliveryMethod as string,
      validUntil: (values.validUntil as dayjs.Dayjs).toISOString(),
      remark: values.remark as string | undefined,
    })
  }

  // ---- columns ----

  const marketColumns: TableColumnsType<ListingView> = [
    { title: '方向', dataIndex: 'sideText', width: 96,
      // Sorts by the code, not the label, so the order is stable regardless of
      // how the two sides happen to be worded.
      sorter: (a: ListingView, b: ListingView) => a.side.localeCompare(b.side),
      render: (v: string, r: ListingView) => (
        <Tag color={r.side === 'SELL' ? 'green' : 'blue'}>{v}</Tag>) },
    {
      title: '商品',
      sorter: (a: ListingView, b: ListingView) => a.commodityName.localeCompare(b.commodityName, 'zh'),
      render: (_: unknown, r: ListingView) => (
        <div>
          <Typography.Text strong>{r.commodityName}</Typography.Text>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {[r.categoryName, r.brand, r.origin].filter(Boolean).join(' · ')}
            </Typography.Text>
          </div>
        </div>
      ),
    },
    { title: '挂牌方', dataIndex: 'enterpriseName', width: 180,
      sorter: (a: ListingView, b: ListingView) => a.enterpriseName.localeCompare(b.enterpriseName, 'zh'),
      render: (v: string, r: ListingView) => (
        <Space size={4}>
          <span>{v}</span>
          {r.mine && <Tag color="gold">我的</Tag>}
        </Space>) },
    {
      title: '价格 / 成交方式',
      width: 170,
      // 面议 rows have no price; byNumberNullsLast keeps them from sorting as
      // zeros and burying the real figures.
      sorter: byNumberNullsLast<ListingView>((r) => (r.price == null ? null : Number(r.price))),
      render: (_: unknown, r: ListingView) => (
        <Space direction="vertical" size={2}>
          {r.priceType === 'NEGOTIABLE' ? (
            <Tag>面议</Tag>
          ) : (
            <Space size={4}>
              <Typography.Text strong style={{ fontSize: 15 }}>{r.price}</Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>元/{r.unit}</Typography.Text>
            </Space>
          )}
          {/* Which of the two conventions applies is the fact a buyer most
              needs before pressing 摘牌, so it is shown on the market row
              rather than discovered afterwards. */}
          <Tag color={r.confirmMode === 'MANUAL' ? 'orange' : 'default'}
            style={{ fontSize: 11, marginInlineEnd: 0 }}>
            {r.confirmModeText}
          </Tag>
        </Space>
      ),
    },
    {
      title: '剩余 / 总量',
      width: 150,
      sorter: (a: ListingView, b: ListingView) =>
        Number(a.remainingQuantity) - Number(b.remainingQuantity),
      render: (_: unknown, r: ListingView) => (
        <Space direction="vertical" size={0}>
          <Typography.Text strong style={{ fontSize: 15 }}>{r.remainingQuantity}</Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            / {r.quantity} {r.unit}
          </Typography.Text>
        </Space>
      ),
    },
    { title: '交收', dataIndex: 'warehouseName', width: 160,
      render: (v: string, r: ListingView) => (
        <div>
          <div style={{ fontSize: 13 }}>{v}</div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{r.deliveryMethodText}</Typography.Text>
        </div>) },
    { title: '有效期至', dataIndex: 'validUntil', width: 130,
      sorter: (a: ListingView, b: ListingView) =>
        dayjs(a.validUntil).valueOf() - dayjs(b.validUntil).valueOf(),
      render: (v: string) => (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {dayjs(v).format('MM-DD HH:mm')}
        </Typography.Text>) },
    {
      title: '发布时间',
      dataIndex: 'createdAt',
      width: 140,
      // The default sort, and the reason the column exists: without a visible
      // timestamp the only thing a long list can be ordered by is the order the
      // server happened to return.
      sorter: byTime,
      defaultSortOrder: 'descend',
      render: (v: string) => (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {dayjs(v).format('MM-DD HH:mm')}
        </Typography.Text>),
    },
    {
      title: '操作',
      width: 110,
      render: (_: unknown, r: ListingView) =>
        r.mine ? (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>自己的挂牌</Typography.Text>
        ) : !signedIn ? (
          // Not a disabled button: a disabled button says "you cannot", and the
          // visitor can — after logging in. So it says that instead.
          <Button size="small" onClick={() => navigate('/login')}>登录后摘牌</Button>
        ) : (
          <Button type="primary" size="small" disabled={Number(r.remainingQuantity) <= 0}
            onClick={() => { setAcceptTarget(r); acceptForm.setFieldsValue({ quantity: r.remainingQuantity }) }}>
            摘牌
          </Button>
        ),
    },
  ]

  const orderColumns: TableColumnsType<OrderView> = [
    { title: '订单号', dataIndex: 'orderNo', width: 190,
      sorter: (a: OrderView, b: OrderView) => a.orderNo.localeCompare(b.orderNo),
      render: (v: string) => <Typography.Text code style={{ fontSize: 12 }}>{v}</Typography.Text> },
    {
      title: '商品',
      sorter: (a: OrderView, b: OrderView) => a.commodityName.localeCompare(b.commodityName, 'zh'),
      render: (_: unknown, r: OrderView) => (
        <div>
          <Typography.Text strong>{r.commodityName}</Typography.Text>
          <div><Typography.Text type="secondary" style={{ fontSize: 12 }}>{r.categoryName}</Typography.Text></div>
        </div>
      ),
    },
    {
      title: '我的角色',
      width: 100,
      sorter: (a: OrderView, b: OrderView) => a.myRole.localeCompare(b.myRole),
      render: (_: unknown, r: OrderView) => (
        <Tag color={r.myRole === 'BUYER' ? 'blue' : 'green'}>
          {r.myRole === 'BUYER' ? '买方' : '卖方'}
        </Tag>),
    },
    { title: '对手方', dataIndex: 'counterpartyName', width: 180,
      sorter: (a: OrderView, b: OrderView) => a.counterpartyName.localeCompare(b.counterpartyName, 'zh') },
    {
      title: '数量 / 金额',
      width: 180,
      sorter: (a: OrderView, b: OrderView) => Number(a.amount) - Number(b.amount),
      render: (_: unknown, r: OrderView) => (
        <Space direction="vertical" size={0}>
          <span>{r.quantity} {r.unit} × {r.price}</span>
          <Typography.Text strong>{r.amountText} 元</Typography.Text>
        </Space>),
    },
    {
      title: '状态',
      width: 150,
      // Three tiers, and the middle one is the point: your move first, then
      // orders still in flight that are waiting on the other party, then
      // everything finished. Two tiers was not enough — an order the
      // counterparty is sitting on is not done, and burying it under the
      // finished ones hides exactly the deal that is going stale.
      //
      // Sorted on `statusHint`, not on the status code, because "pending" is
      // not a status: the same 已确认 order is your move while its contract is
      // undrafted and nobody's move once it exists.
      sorter: (a: OrderView, b: OrderView) => {
        const tier = (o: OrderView) =>
          o.statusHintMine ? 0 : o.statusHint ? 1 : 2
        const byTier = tier(a) - tier(b)
        if (byTier !== 0) return byTier
        return a.status.localeCompare(b.status)
      },
      defaultSortOrder: 'ascend' as const,
      // The tag is the state; the line under it is whose move it is. One tag
      // still — a second tag beside it read as a second status, and a row has
      // exactly one. But "已签约" alone does not say whether the seller or the
      // buyer is being waited on, and that is the question someone scanning
      // their orders is actually asking.
      render: (_: unknown, r: OrderView) => (
        <Space direction="vertical" size={2}>
          <Tag color={ORDER_COLOURS[r.status]} style={{ marginInlineEnd: 0 }}>
            {r.statusText}
          </Tag>
          {r.statusHint && (
            <Typography.Text
              style={{ fontSize: 12 }}
              type={r.statusHintMine ? undefined : 'secondary'}
              strong={r.statusHintMine}
            >
              {r.statusHintMine ? '● ' : ''}{r.statusHint}
            </Typography.Text>
          )}
        </Space>
      ),
    },
    // Deliberately not the default sort: Ant Design applies only one, and
    // pending-first is the more useful opening view. Newest-first is one click
    // away.
    { title: '创建时间', dataIndex: 'createdAt', width: 140,
      sorter: byTime,
      render: (v: string) => (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {dayjs(v).format('MM-DD HH:mm')}
        </Typography.Text>) },
    {
      title: '操作',
      width: 100,
      render: (_: unknown, r: OrderView) => (
        <Button type={r.allowedActions.length > 0 ? 'primary' : 'link'} size="small"
          onClick={() => setDetailOrder(r)}>
          {r.allowedActions.length > 0 ? '去处理' : '查看'}
        </Button>),
    },
  ]

  return (
    <div style={{ padding: 24, maxWidth: 1500, margin: '0 auto' }}>
      <Typography.Title level={4} style={{ marginTop: 0, marginBottom: 16 }}>
        挂牌交易
      </Typography.Title>

      <Tabs
        defaultActiveKey="market"
        // A visitor gets the hall and nothing else. "我的挂牌" and "我的订单"
        // have no "my" to speak of without an enterprise, and rendering them
        // empty would look like a broken page rather than an unauthenticated
        // one.
        items={[
          {
            key: 'market',
            label: `挂牌大厅 (${market.length})`,
            children: (
              <>
                <Card size="small" style={{ marginBottom: 12 }} styles={{ body: { padding: '10px 16px' } }}>
                  <Space wrap size={12}>
                    <Segmented
                      value={sideFilter ?? 'all'}
                      onChange={(v) => setSideFilter(v === 'all' ? undefined : (v as string))}
                      options={[
                        { label: '全部', value: 'all' },
                        { label: '卖方挂牌', value: 'SELL' },
                        { label: '买方挂牌', value: 'BUY' },
                      ]}
                    />
                    <Input.Search allowClear placeholder="按商品名称搜索"
                      style={{ width: 240 }}
                      onSearch={setKeyword}
                      onClear={() => setKeyword('')} />
                    {(sideFilter || keyword) && (
                      <Button type="link" size="small"
                        onClick={() => { setSideFilter(undefined); setKeyword('') }}>
                        重置筛选
                      </Button>
                    )}
                  </Space>
                </Card>
                <Table rowKey="id" size="middle" loading={marketLoading} dataSource={market}
                  columns={marketColumns} pagination={LIST_PAGINATION}
                  locale={{
                    emptyText: (
                      <Empty description={
                        sideFilter || keyword ? '没有符合筛选条件的挂牌' : '当前没有有效挂牌'
                      } />
                    ),
                  }} />
              </>
            ),
          },
          ...(isMember
            ? [
                {
                  key: 'mine',
                  label: `我的挂牌 (${myListings.length})`,
                  children: (
                    <>
                      <Button type="primary" icon={<PlusOutlined />} style={{ marginBottom: 12 }}
                        onClick={() => setPublishOpen(true)}>
                        发布挂牌
                      </Button>
                      <Table rowKey="id" size="middle" dataSource={myListings}
                        pagination={LIST_PAGINATION}
                        columns={[
                          { title: '挂牌号', dataIndex: 'listingNo', width: 190,
                            sorter: (a: ListingView, b: ListingView) => a.listingNo.localeCompare(b.listingNo),
                            render: (v: string) => <Typography.Text code style={{ fontSize: 12 }}>{v}</Typography.Text> },
                          { title: '方向', dataIndex: 'sideText', width: 90,
                            sorter: (a: ListingView, b: ListingView) => a.side.localeCompare(b.side),
                            render: (v: string, r: ListingView) => (
                              <Tag color={r.side === 'SELL' ? 'green' : 'blue'}>{v}</Tag>) },
                          { title: '商品', dataIndex: 'commodityName',
                            sorter: (a: ListingView, b: ListingView) => a.commodityName.localeCompare(b.commodityName, 'zh') },
                          { title: '价格', dataIndex: 'priceText', width: 110,
                            sorter: byNumberNullsLast<ListingView>((r) => (r.price == null ? null : Number(r.price))) },
                          { title: '剩余 / 总量', width: 150,
                            sorter: (a: ListingView, b: ListingView) =>
                              Number(a.remainingQuantity) - Number(b.remainingQuantity),
                            render: (_: unknown, r: ListingView) => `${r.remainingQuantity} / ${r.quantity} ${r.unit}` },
                          { title: '成交方式', dataIndex: 'confirmModeText', width: 120,
                            sorter: (a: ListingView, b: ListingView) => a.confirmMode.localeCompare(b.confirmMode),
                            render: (v: string, r: ListingView) => (
                              <Tag color={r.confirmMode === 'MANUAL' ? 'orange' : 'default'}>{v}</Tag>) },
                          { title: '状态', dataIndex: 'statusText', width: 110,
                            sorter: (a: ListingView, b: ListingView) => a.status.localeCompare(b.status),
                            render: (v: string) => <Tag>{v}</Tag> },
                          { title: '发布时间', dataIndex: 'createdAt', width: 140,
                            sorter: byTime, defaultSortOrder: 'descend' as const,
                            render: (v: string) => (
                              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                {dayjs(v).format('MM-DD HH:mm')}
                              </Typography.Text>) },
                          {
                            title: '操作',
                            width: 100,
                            render: (_: unknown, r: ListingView) =>
                              ['OPEN', 'PARTIALLY_FILLED'].includes(r.status) ? (
                                <Popconfirm title="撤牌并解冻剩余货物？" okText="撤牌" cancelText="取消"
                                  onConfirm={async () => { await closeListing(r.id); void message.success('已撤牌'); invalidate() }}>
                                  <Button type="link" size="small" danger>撤牌</Button>
                                </Popconfirm>
                              ) : (
                                <Typography.Text type="secondary" style={{ fontSize: 12 }}>—</Typography.Text>
                              ),
                          },
                        ]}
                        locale={{ emptyText: <Empty description="还没有发布过挂牌" /> }} />
                    </>
                  ),
                },
                {
                  key: 'orders',
                  // The count people actually scan for. A tab that says "21"
                  // when eleven of them are waiting on you is a number that
                  // hides the only thing worth knowing.
                  label: (
                    <Space size={6}>
                      <span>{`我的订单 (${myOrders.length})`}</span>
                      {pendingOrderCount > 0 && (
                        <Badge count={pendingOrderCount} size="small" />
                      )}
                    </Space>
                  ),
                  children: (
                    <>
                      <Card size="small" style={{ marginBottom: 12 }}
                        styles={{ body: { padding: '10px 16px' } }}>
                        <Space direction="vertical" size={8} style={{ width: '100%' }}>
                          <Segmented
                            value={orderPhase}
                            onChange={(v) => {
                              setOrderPhase(v as 'ACTIVE' | 'FINISHED' | 'ALL')
                              // The stage filter belongs to a phase; carrying it
                              // across would show an empty table under a chip
                              // that is no longer on screen.
                              setOrderStage('')
                            }}
                            options={[
                              { label: `进行中 (${activeCount})`, value: 'ACTIVE' },
                              {
                                label: `已结束 (${myOrders.length - activeCount})`,
                                value: 'FINISHED',
                              },
                              { label: `全部 (${myOrders.length})`, value: 'ALL' },
                            ]}
                          />
                          {orderPhase !== 'ALL' && (
                            <Segmented
                              size="small"
                              value={orderStage}
                              onChange={(v) => setOrderStage(v as string)}
                              options={[
                                {
                                  label: `全部 ${orderPhase === 'ACTIVE' ? '进行中' : '已结束'} (${ordersInPhase.length})`,
                                  value: '',
                                },
                                ...(orderPhase === 'ACTIVE' ? ACTIVE_STAGES : FINISHED_STAGES).map(
                                  (stage) => ({
                                    label: `${ORDER_STAGE_TEXT[stage]} (${stageCounts[stage] ?? 0})`,
                                    value: stage,
                                  }),
                                ),
                              ]}
                            />
                          )}
                        </Space>
                      </Card>
                      <Table rowKey="id" size="middle" dataSource={visibleOrders} columns={orderColumns}
                        pagination={LIST_PAGINATION}
                        locale={{
                          emptyText: (
                            <Empty
                              description={
                                myOrders.length === 0
                                  ? '还没有订单'
                                  : orderStage
                                    ? `没有「${ORDER_STAGE_TEXT[orderStage]}」状态的订单`
                                    : orderPhase === 'ACTIVE'
                                      ? '没有进行中的订单'
                                      : '没有已结束的订单'
                              }
                            />
                          ),
                        }} />
                    </>
                  ),
                },
              ]
            : []),
        ]}
      />

      {/* ---------- accept ---------- */}
      <Modal
        title={acceptTarget ? `摘牌：${acceptTarget.commodityName}` : ''}
        open={acceptTarget !== null}
        onCancel={() => setAcceptTarget(null)}
        onOk={() => acceptForm.submit()}
        confirmLoading={accepting}
        okText="确认摘牌"
        cancelText="取消"
      >
        {acceptTarget && (
          <>
            {acceptTarget.confirmMode === 'MANUAL' ? (
              <Alert type="warning" showIcon style={{ marginBottom: 16 }}
                message="摘牌后需挂牌方确认"
                description="这张挂牌约定「需挂牌方确认」：摘牌只是把成交条件提交给挂牌方，
                  货权不会立即转移，要以挂牌方的答复为准。挂牌方确认后才转移货权；
                  若拒绝或逾期未答复，货物原样退回挂牌，你不会获得库存。" />
            ) : (
              <Alert type="info" showIcon style={{ marginBottom: 16 }}
                message="摘牌即承诺"
                description="接受对方的挂牌就是作出承诺，货权当场转移：卖方库存减少，你会获得等量的电子库存单。价格与交收条款按挂牌内容执行。" />
            )}
            <Descriptions column={1} size="small" bordered style={{ marginBottom: 16 }}>
              <Descriptions.Item label="挂牌方">{acceptTarget.enterpriseName}</Descriptions.Item>
              <Descriptions.Item label="单价">{acceptTarget.price} 元/{acceptTarget.unit}</Descriptions.Item>
              <Descriptions.Item label="可摘数量">
                {acceptTarget.remainingQuantity} {acceptTarget.unit}
              </Descriptions.Item>
              <Descriptions.Item label="成交方式">
                <Tag color={acceptTarget.confirmMode === 'MANUAL' ? 'orange' : 'default'}>
                  {acceptTarget.confirmModeText}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="交收仓库">
                {acceptTarget.warehouseName}（{acceptTarget.deliveryMethodText}）
              </Descriptions.Item>
            </Descriptions>
            <Form form={acceptForm} layout="vertical"
              onFinish={(v) => acceptTarget && void doAccept({
                id: acceptTarget.id, quantity: v.quantity as number, remark: v.remark as string })}>
              <Form.Item name="quantity" label="摘牌数量"
                rules={[{ required: true, message: '请填写数量' }]}>
                <InputNumber min={0.001} max={Number(acceptTarget.remainingQuantity)} step={1}
                  style={{ width: '100%' }} addonAfter={acceptTarget.unit} />
              </Form.Item>
              <Form.Item name="remark" label="备注">
                <Input.TextArea rows={2} maxLength={512} />
              </Form.Item>
            </Form>
          </>
        )}
      </Modal>

      {/* ---------- publish ---------- */}
      <Modal
        title="发布挂牌"
        open={publishOpen}
        onCancel={() => setPublishOpen(false)}
        onOk={() => publishForm.submit()}
        confirmLoading={publishing}
        okText="确认发布"
        cancelText="取消"
        width={620}
      >
        <Alert type="warning" showIcon style={{ marginBottom: 16 }}
          message="发布挂牌即是发出要约"
          description="卖方挂牌会立即冻结对应库存，冻结期间这部分货物不能再挂牌或注销。
            挂牌在有效期内持续有效，期满未成交自动失效并解冻。" />
        <Form form={publishForm} layout="vertical" onFinish={handlePublish}
          initialValues={{ side: 'SELL', priceType: 'FIXED', confirmMode: 'AUTO', deliveryMethod: 'SELF_PICKUP' }}>
          <Form.Item name="side" label="挂牌方向">
            <Radio.Group optionType="button" buttonStyle="solid">
              <Radio.Button value="SELL">卖方挂牌（我卖货）</Radio.Button>
              <Radio.Button value="BUY">买方挂牌（我买货）</Radio.Button>
            </Radio.Group>
          </Form.Item>

          {publishSide !== 'BUY' && (
            <Form.Item name="inventoryNoteId" label="对应库存单"
              rules={[{ required: true, message: '卖方挂牌必须指定库存单' }]}>
              <Select placeholder="选择要出售的库存单" optionFilterProp="label" showSearch
                options={sellableNotes.map((n) => ({
                  value: n.id,
                  label: `${n.commodityName} · 可用 ${n.availableQuantity} ${n.unit} · ${n.warehouseName}`,
                }))} />
            </Form.Item>
          )}

          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="categoryId" label="品类"
                rules={[{ required: publishSide === 'BUY', message: '请选择品类' }]}>
                <Select placeholder="选择品类" showSearch optionFilterProp="label"
                  options={categoryOptions.map((o) => ({ value: o.id, label: o.label }))} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="commodityName" label="商品名称"
                rules={[{ required: publishSide === 'BUY', message: '请填写商品名称' }]}>
                <Input placeholder="留空则取库存单的商品名" />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={12}>
            <Col span={8}>
              <Form.Item name="quantity" label="数量" rules={[{ required: true, message: '请填写数量' }]}>
                <InputNumber min={0.001} step={1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="priceType" label="价格方式">
                <Radio.Group>
                  <Radio value="FIXED">定价</Radio>
                  <Radio value="NEGOTIABLE">面议</Radio>
                </Radio.Group>
              </Form.Item>
            </Col>
            <Col span={8}>
              {publishSide !== 'BUY' && publishForm.getFieldValue('priceType') === 'FIXED' && (
                <Form.Item name="price" label="单价（元）"
                  rules={[{ required: true, message: '请填写单价' }]}>
                  <InputNumber min={0.01} step={100} style={{ width: '100%' }} />
                </Form.Item>
              )}
            </Col>
          </Row>

          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="warehouseId" label="交收仓库">
                <Select placeholder="留空则取库存单所在交收仓库"
                  options={warehouses.map((w) => ({ value: w.id, label: w.name }))} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="deliveryMethod" label="交收方式">
                <Select options={[
                  { value: 'SELF_PICKUP', label: '自提' },
                  { value: 'DELIVERED', label: '送到' },
                ]} />
              </Form.Item>
            </Col>
          </Row>

          {publishSide !== 'BUY' && (
            <Form.Item name="confirmMode" label="成交方式"
              extra="决定摘牌意味着什么：直接成交，还是先问过你。买方挂牌只能是「摘牌即成交」。">
              <Radio.Group>
                <Radio value="AUTO">摘牌即成交（挂牌即要约，摘牌即承诺）</Radio>
                <Radio value="MANUAL">需我确认（摘牌后等我答复，确认前货权不动）</Radio>
              </Radio.Group>
            </Form.Item>
          )}

          <Form.Item name="validUntil" label="有效期至"
            rules={[{ required: true, message: '请选择有效期' }]}>
            <DatePicker showTime style={{ width: '100%' }}
              disabledDate={(d) => d && d < dayjs().startOf('day')}
              defaultValue={dayjs().add(7, 'day')} />
          </Form.Item>

          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={512} showCount />
          </Form.Item>
        </Form>
      </Modal>

      {/* ---------- order detail ---------- */}
      <OrderDetailModal
        order={detailOrder}
        onClose={() => setDetailOrder(null)}
        onAction={runOrderAction}
        onChanged={invalidate}
      />
    </div>
  )
}

/** Order detail: lifecycle rail, actions, contract signing and transition history. */
function OrderDetailModal({
  order,
  onClose,
  onAction,
  onChanged,
}: {
  order: OrderView | null
  onClose: () => void
  onAction: (action: string, order: OrderView) => Promise<void>
  onChanged: () => void
}) {
  const [signing, setSigning] = useState(false)
  const orderId = order?.id

  const { data: history = [] } = useQuery({
    queryKey: ['order-history', orderId],
    queryFn: () => fetchOrderHistory(orderId as EntityId),
    enabled: Boolean(orderId),
  })

  const { data: contract, refetch: refetchContract } = useQuery({
    queryKey: ['order-contract', orderId],
    queryFn: () => fetchOrderContract(orderId as EntityId),
    enabled: Boolean(orderId),
    retry: false,
  })

  const currentStep = order ? ORDER_STEPS.indexOf(order.status) : 0

  const handleSign = async () => {
    if (!contract) return
    setSigning(true)
    try {
      const result = await signContract(contract.id)
      void message.success(result.status === 'SIGNED' ? '合同已生效' : '已签署，等待对方签署')
      await refetchContract()
      onChanged()
      if (result.status === 'SIGNED') onClose()
    } finally {
      setSigning(false)
    }
  }

  const handleDraft = async () => {
    if (!order) return
    await draftContract(order.id)
    void message.success('合同已起草')
    await refetchContract()
  }

  return (
    <Modal title={order ? `订单 ${order.orderNo}` : ''} open={order !== null}
      onCancel={onClose} footer={null} width={720}>
      {order && (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          {order.status !== 'CANCELLED' ? (
            <Steps size="small" current={currentStep}
              items={[
                { title: '待挂牌方确认' }, { title: '已确认' }, { title: '已签约' },
                { title: '交收中' }, { title: '已完成' },
              ]} />
          ) : (
            <Alert type="warning" showIcon message={`订单已取消${order.cancelReason ? `：${order.cancelReason}` : ''}`} />
          )}

          {order.status === 'PENDING_CONFIRM' && (
            <Alert type="warning" showIcon
              message={order.myRole === 'SELLER'
                ? '这笔摘牌在等您答复'
                : '已提交摘牌，等待挂牌方确认'}
              description={
                <>
                  货权尚未转移。挂牌方确认后才转移货权；
                  拒绝或逾期未答复则挂牌数量原样恢复。
                  {order.confirmDeadline && (
                    <> 答复截止 <Typography.Text strong>
                      {dayjs(order.confirmDeadline).format('MM-DD HH:mm')}</Typography.Text>。</>
                  )}
                </>
              } />
          )}

          <Descriptions column={2} size="small" bordered>
            <Descriptions.Item label="商品" span={2}>{order.commodityName}</Descriptions.Item>
            <Descriptions.Item label="我的角色">
              {order.myRole === 'BUYER' ? '买方' : '卖方'}
            </Descriptions.Item>
            <Descriptions.Item label="对手方">{order.counterpartyName}</Descriptions.Item>
            <Descriptions.Item label="数量">{order.quantity} {order.unit}</Descriptions.Item>
            <Descriptions.Item label="单价">{order.price} 元</Descriptions.Item>
            <Descriptions.Item label="总额" span={2}>
              <Typography.Text strong style={{ fontSize: 16 }}>{order.amountText} 元</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="交收仓库" span={2}>
              {order.warehouseName}（{order.deliveryMethodText}）
            </Descriptions.Item>
          </Descriptions>

          {/* Contract */}
          <Card size="small" title="合同"
            extra={
              !contract ? (
                ['CONFIRMED'].includes(order.status) ? (
                  <Button size="small" type="primary" onClick={() => void handleDraft()}>起草合同</Button>
                ) : order.status === 'PENDING_CONFIRM' ? (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>挂牌方确认后可起草</Typography.Text>
                ) : null
              ) : contract.status === 'PENDING_SIGN' ? (
                <Button size="small" type="primary" loading={signing}
                  disabled={contract.mySigned} onClick={() => void handleSign()}>
                  {contract.mySigned ? '等待对方签署' : '签署合同'}
                </Button>
              ) : null
            }>
            {contract ? (
              <Space direction="vertical" size={8} style={{ width: '100%' }}>
                <Space>
                  <Typography.Text code>{contract.contractNo}</Typography.Text>
                  <Tag color={contract.status === 'SIGNED' ? 'success' : 'processing'}>
                    {contract.statusText}
                  </Tag>
                </Space>
                <Space size={16}>
                  <Typography.Text type={contract.mySigned ? 'success' : 'secondary'}>
                    {contract.mySigned ? '✓ 我已签署' : '○ 待我签署'}
                  </Typography.Text>
                  <Typography.Text type={contract.counterpartySigned ? 'success' : 'secondary'}>
                    {contract.counterpartySigned ? '✓ 对方已签署' : '○ 待对方签署'}
                  </Typography.Text>
                </Space>
                <Descriptions column={1} size="small" bordered style={{ marginTop: 4 }}>
                  <Descriptions.Item label="磅差容差">{contract.weightTolerance}%</Descriptions.Item>
                  {Object.entries(contract.terms)
                    .filter(([key]) => ['settlementBasis', 'qualityDispute', 'disputeResolution'].includes(key))
                    .map(([key, value]) => (
                      <Descriptions.Item key={key} label={
                        { settlementBasis: '结算基准', qualityDispute: '质量异议', disputeResolution: '争议解决' }[key]
                      }>
                        <Typography.Text style={{ fontSize: 12 }}>{String(value)}</Typography.Text>
                      </Descriptions.Item>
                    ))}
                </Descriptions>
              </Space>
            ) : (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                尚未起草。合同双方签署后订单才会进入「已签约」，之后才能开始交收。
              </Typography.Text>
            )}
          </Card>

          <Card size="small" title="可执行操作">
            <Space wrap>
              {order.allowedActions.includes('CONFIRMED') && (
                <Button type="primary" onClick={() => void onAction('confirm', order)}>确认成交</Button>
              )}
              {/* Only the lister ever reaches this: the server strips CONFIRMED
                  from the counterparty's action list, so the button they must
                  not press is never rendered for them. */}
              {order.status === 'PENDING_CONFIRM' && order.myRole === 'SELLER' && (
                <Popconfirm title="拒绝这笔摘牌？" description="货权尚未转移，拒绝后挂牌数量原样恢复。"
                  okText="拒绝" cancelText="再想想" okButtonProps={{ danger: true }}
                  onConfirm={() => void onAction('reject', order)}>
                  <Button danger>拒绝摘牌</Button>
                </Popconfirm>
              )}
              {/* Labels come from the server rather than being written here.
                  Who starts delivery and who finishes it depends on the
                  delivery term and on which party is reading, and a second copy
                  of that rule in the client is one that can disagree with the
                  button the server would accept. */}
              {order.allowedActions.includes('DELIVERING') && (
                <Button type="primary" onClick={() => void onAction('deliver', order)}>
                  {order.nextAction ?? '发起交收'}
                </Button>
              )}
              {order.allowedActions.includes('COMPLETED') && (
                <Button type="primary" onClick={() => void onAction('complete', order)}>
                  {order.nextAction ?? '确认完成'}
                </Button>
              )}
              {order.allowedActions.includes('CANCELLED') && (
                <Popconfirm title="取消这笔订单？" description="交收开始前可取消，货物会退回卖方。"
                  okText="取消订单" cancelText="再想想" okButtonProps={{ danger: true }}
                  onConfirm={() => void onAction('cancel', order)}>
                  <Button danger>取消订单</Button>
                </Popconfirm>
              )}
              {order.allowedActions.length === 0 && (
                <Typography.Text type="secondary">订单已终结，没有可执行的操作</Typography.Text>
              )}
            </Space>
          </Card>

          <Card size="small" title="状态轨迹">
            <Timeline
              items={history.map((entry) => ({
                color: entry.toStatus === 'CANCELLED' ? 'red' : 'blue',
                children: (
                  <div>
                    <Space size={6}>
                      <Typography.Text strong>{entry.toText}</Typography.Text>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {entry.operator}
                      </Typography.Text>
                    </Space>
                    <div>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {entry.reason} · {dayjs(entry.createdAt).format('MM-DD HH:mm:ss')}
                      </Typography.Text>
                    </div>
                  </div>
                ),
              }))}
            />
          </Card>
        </Space>
      )}
    </Modal>
  )
}
