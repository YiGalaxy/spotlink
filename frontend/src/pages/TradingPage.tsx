import { useEffect, useMemo, useState } from 'react'
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
import { useNavigate, useSearchParams } from 'react-router-dom'
import dayjs from 'dayjs'
import { identityKey, useAuthStore } from '@/store/auth'
import {
  acceptListing,
  cancelOrder,
  closeListing,
  completeOrder,
  confirmOrder,
  draftContract,
  fetchMarket,
  fetchMyListings,
  fetchMatchingInventory,
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
import { positiveDecimalRule, decimalUnits } from '@/utils/decimal'
import type { CategoryNode, EntityId, ListingView, OrderView } from '@/types/api'

function flattenLeaves(nodes: CategoryNode[], depth = 0): (CategoryNode & { label: string })[] {
  const options: (CategoryNode & { label: string })[] = []
  for (const node of nodes) {
    if (node.children.length === 0) {
      options.push({ ...node, label: `${'　'.repeat(depth)}${node.name}` })
    } else {
      options.push(...flattenLeaves(node.children, depth + 1))
    }
  }
  return options
}

const ORDER_STEPS = ['PENDING_CONFIRM', 'CONFIRMED', 'CONTRACTED', 'DELIVERING', 'COMPLETED']

/** 与服务端 OrderStatus.text 保持一致；用于筛选标签。 */
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
  const [searchParams, setSearchParams] = useSearchParams()
  const focusedListing = searchParams.get('listing')
  const accessToken = useAuthStore((state) => state.accessToken)
  const user = useAuthStore((state) => state.user)
  const signedIn = Boolean(accessToken)

  /**
   * 这个账号有没有可以用来交易的企业。
   *
   * <p>与「已登录」是两回事。平台运营是一个合法的账号类型，只是没有租户，所以每一个按
   * 租户划分的查询都会返回「未绑定企业」——结果就是那两个会员标签页出现了，一点开却
   * 报错。**按标签页真正需要的东西来把关，而不是按碰巧相关的那件更宽松的事。**
   */
  const isMember = signedIn && Boolean(user?.enterpriseId)
  const [acceptTarget, setAcceptTarget] = useState<ListingView | null>(null)
  const [publishOpen, setPublishOpen] = useState(false)
  const [detailOrder, setDetailOrder] = useState<OrderView | null>(null)
  const sideFilter = ['SELL', 'BUY'].includes(searchParams.get('side') ?? '') ? searchParams.get('side')! : undefined
  const keyword = searchParams.get('q') ?? ''
  const [searchDraft, setSearchDraft] = useState(keyword)
  useEffect(() => setSearchDraft(keyword), [keyword])
  const updateFilter = (key: string, value?: string) => setSearchParams(current => {
    const next = new URLSearchParams(current)
    if (value) next.set(key, value)
    else next.delete(key)
    return next
  })
  const setSideFilter = (value?: string) => updateFilter('side', value)
  const setKeyword = (value: string) => updateFilter('q', value.trim())
  const requestedTab = searchParams.get('tab')
  const activeTab = (requestedTab === 'mine' || requestedTab === 'orders') && isMember ? requestedTab : 'market'
  /** 'ACTIVE' | 'FINISHED' | 'ALL' — the top-level split on 我的订单. */
  const [orderPhase, setOrderPhase] = useState<'ACTIVE' | 'FINISHED' | 'ALL'>('ACTIVE')
  /** 空字符串表示所选层级下的全部状态。 */
  const [orderStage, setOrderStage] = useState('')
  const [acceptForm] = Form.useForm()
  const [publishForm] = Form.useForm()
  const publishSide = Form.useWatch('side', publishForm)
  const publishPriceType = Form.useWatch('priceType', publishForm)
  const publishNoteId = Form.useWatch('inventoryNoteId', publishForm)
  const publishCategoryId = Form.useWatch('categoryId', publishForm)
  const acceptQuantity = Form.useWatch('quantity', acceptForm)
  const acceptUnits = decimalUnits(acceptQuantity, 3)
  const remainingUnits = decimalUnits(acceptTarget?.remainingQuantity, 3)
  const canFindStock = acceptTarget?.side === 'BUY' && isMember && acceptUnits !== null && acceptUnits > 0n
    && remainingUnits !== null && acceptUnits <= remainingUnits
  const { data: matchingNotes = [], isFetching: findingStock, isError: stockError, refetch: reloadStock } = useQuery({
    queryKey: identityKey('matching-inventory', acceptTarget?.id, String(acceptQuantity)),
    queryFn: () => fetchMatchingInventory(acceptTarget!.id, String(acceptQuantity)),
    enabled: canFindStock,
  })
  useEffect(() => { acceptForm.setFieldValue('inventoryNoteId', undefined) }, [acceptTarget?.id, acceptQuantity, acceptForm])

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: identityKey('market') })
    void queryClient.invalidateQueries({ queryKey: identityKey('my-listings') })
    void queryClient.invalidateQueries({ queryKey: identityKey('my-orders') })
    void queryClient.invalidateQueries({ queryKey: identityKey('inventory-notes') })
    void queryClient.invalidateQueries({ queryKey: identityKey('market-quotes') })
  }

  const { data: market = [], isLoading: marketLoading, isError: marketError, refetch: reloadMarket } = useQuery({
    queryKey: identityKey('market', sideFilter, keyword),
    // 服务端一直支持关键词搜索——browse() 从写出来那天就接受这个参数——
    // 却从来没有人传过。在一个只会越来越大的大厅里，筛选就是清单和草堆的区别。
    queryFn: () => fetchMarket(undefined, sideFilter, keyword || undefined),
  })

  // 挂牌大厅是公开的；下面这些每一件都是某一家企业自己的事，所以访客连问都不该问。
  // 不带登录态去发这些请求会拿到 401，而客户端的 401 处理把它当作会话过期，
  // 会把访客弹到登录页——于是「浏览」就变成了「被赶出去」。
  const { data: myListings = [] } = useQuery({
    queryKey: identityKey('my-listings'),
    queryFn: fetchMyListings,
    enabled: isMember,
  })

  const { data: myOrders = [] } = useQuery({
    queryKey: identityKey('my-orders'),
    queryFn: () => fetchMyOrders(),
    enabled: isMember,
  })

  const { data: notes = [] } = useQuery({
    queryKey: identityKey('inventory-notes'),
    queryFn: () => listInventoryNotes(),
    enabled: isMember,
  })

  const { data: categories = [] } = useQuery({
    queryKey: identityKey('category-tree'),
    queryFn: fetchCategoryTree,
  })

  const { data: warehouses = [] } = useQuery({
    queryKey: identityKey('warehouses'),
    queryFn: fetchWarehouses,
    enabled: isMember,
  })

  /**
   * 这家企业有多少订单在等他动。
   *
   * <p>从 `allowedActions` 推导，也就是服务端真正据以执行的那个字段，所以页签上的数字
   * 和点进去之后的按钮不可能对不上。用任何别的方式算出来的数字——比如按状态算——都会
   * 变成对「待办」是什么的第二种说法。
   */
  const pendingOrderCount = useMemo(
    () => myOrders.filter((o) => o.allowedActions.length > 0).length,
    [myOrders],
  )

  /**
   * 未完结的状态，按交易流程走过的顺序排列。
   *
   * <p>写死而不是从数据里推导，因为顺序才是重点：待确认 → 已确认 → 已签约 → 交收中
   * 是一笔交易走完的次序，而按「数据里恰好存在哪些状态」排序，会得出一个毫无意义的
   * 顺序。没人在的阶段也会以零显示出来，让读者看见它被考虑过。
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
   * 各状态的计数，以及要展示的行。
   *
   * <p>刻意做成两级。「已结束」和「进行中」回答的是「这一笔还归我操心吗」，具体的状态
   * 回答的是「走到哪一步了」——把它们混成一行七个标签，就是一个没人会看的筛选器。
   * 哪些状态属于哪一级，取自服务端自己的生命周期，而不是对排序顺序的猜测。
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
  const buyCategory = categoryOptions.find(category => category.id === publishCategoryId)
  /** 只有还有空闲数量的库存单，才能给卖方挂牌做背书。 */
  const sellableNotes = useMemo(
    () => notes.filter((n) => Number(n.availableQuantity) > 0 && [2, 3, 4].includes(n.status)),
    [notes],
  )
  const publishNote = sellableNotes.find(note => note.id === publishNoteId)

  // ---- 写操作 ----

  const { mutateAsync: doAccept, isPending: accepting } = useMutation({
    mutationFn: (vars: { id: EntityId; quantity: string; remark?: string; inventoryNoteId?: EntityId }) =>
      acceptListing(vars.id, vars.quantity, vars.remark, vars.inventoryNoteId),
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
    const selling = values.side === 'SELL'
    await doPublish({
      side: values.side as 'SELL' | 'BUY',
      inventoryNoteId: selling ? values.inventoryNoteId as EntityId : undefined,
      categoryId: selling ? undefined : values.categoryId as EntityId,
      commodityName: selling ? undefined : values.commodityName as string,
      brand: selling ? undefined : values.brand as string | undefined,
      origin: selling ? undefined : values.origin as string | undefined,
      spec: selling ? undefined : values.spec as Record<string, unknown>,
      unit: selling ? undefined : buyCategory?.unit,
      quantity: String(values.quantity),
      price: values.priceType === 'FIXED' ? String(values.price) : undefined,
      priceType: values.priceType as 'FIXED' | 'NEGOTIABLE',
      // 服务端会拒绝买方挂牌上的 MANUAL，所以表单干脆不提供它，
      // 而不是让请求去失败一次。
      confirmMode: values.side === 'SELL'
        ? (values.confirmMode as 'AUTO' | 'MANUAL' | undefined)
        : undefined,
      warehouseId: selling ? undefined : values.warehouseId as EntityId | undefined,
      deliveryMethod: values.deliveryMethod as string,
      validUntil: (values.validUntil as dayjs.Dayjs).toISOString(),
      remark: values.remark as string | undefined,
    })
  }

  // ---- 表格列 ----

  const marketColumns: TableColumnsType<ListingView> = [
    { title: '方向', dataIndex: 'sideText', width: 96,
      // 按代码排序，而不是按标签，这样无论两侧的措辞将来怎么改，
      // 顺序都是稳定的。
      sorter: (a: ListingView, b: ListingView) => a.side.localeCompare(b.side),
      render: (v: string, r: ListingView) => (
        <Tag color={r.side === 'SELL' ? 'green' : 'blue'}>{v}</Tag>) },
    {
      title: '商品',
      width: 220,
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
      // 「面议」的行没有价格；byNumberNullsLast 让它们不至于被当成 0 参与排序，
      // 把真正的数字埋掉。
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
          {/* 两种约定里适用哪一种，是买方按下「摘牌」之前最需要知道的事，
              所以它显示在行情行上，而不是事后才发现。 */}
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
      // 默认排序列，也是这一列存在的原因：没有一个可见的时间戳，一长串列表唯一
      // 能依据的顺序，就是服务端碰巧返回的那个顺序。
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
          // 这里不是一个禁用按钮：禁用按钮说的是「你不能」，而访客其实能——
          // 登录之后就能。所以按钮直接把这句话说出来。
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
      width: 220,
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
      // 三档，中间那档才是重点：先是你该动的，然后是还在流程里、等着对方动的，最后是
      // 已完结的。两档不够——对方压着不办的订单并没有结束，把它埋进已完结里面，恰恰
      // 藏起了那笔正在变馊的交易。
      //
      // 按 `statusHint` 排序，而不是按状态码，因为「待办」不是一个状态：同一笔「已确认」
      // 的订单，在它的合同还没起草时是你该动的，合同一出现就谁都不用动了。
      sorter: (a: OrderView, b: OrderView) => {
        const tier = (o: OrderView) =>
          o.statusHintMine ? 0 : o.statusHint ? 1 : 2
        const byTier = tier(a) - tier(b)
        if (byTier !== 0) return byTier
        return a.status.localeCompare(b.status)
      },
      defaultSortOrder: 'ascend' as const,
      // 标签是状态；它下面那行才是「该谁动」。仍然只有一个标签——旁边再放一个会被读成
      // 第二个状态，而一行订单恰好只有一个状态。但光有「已签约」说不清等的是卖方还是
      // 买方，而正翻着自己订单的人问的就是这个。
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
    // 刻意不作为默认排序：Ant Design 只应用一个排序，而「待办优先」是更有用的初始
    // 视图。最新优先只差一次点击。
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
    <div className="business-page trading-page">
      <Typography.Title level={4} style={{ marginTop: 0, marginBottom: 16 }}>
        挂牌交易
      </Typography.Title>
      <Typography.Paragraph type="secondary">浏览现货报价与采购需求。通过企业账号发布挂牌，并在我的订单中完成确认、签约和交收。</Typography.Paragraph>
      {marketError && <Alert type="error" showIcon message="挂牌读取失败，请重试" action={<Button onClick={() => void reloadMarket()}>重新加载</Button>} style={{ marginBottom: 16 }} />}

      <Tabs
        activeKey={activeTab}
        onChange={tab => {
          if (tab !== 'market' && !signedIn) { navigate('/login', { state: { from: `/trading?tab=${tab}` } }); return }
          setSearchParams(current => { const next = new URLSearchParams(current); next.set('tab',tab); next.delete('listing'); return next })
        }}
        // 未登录的访客只看得到大厅。「我的挂牌」和「我的订单」在没有企业时无所谓
        // 「我的」，把它们渲染成空表，看起来会像一个坏掉的页面，而不是未登录的页面。
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
                    <Input.Search allowClear placeholder="按商品名称搜索" value={searchDraft}
                      style={{ width: 240 }}
                      onChange={event => setSearchDraft(event.target.value)}
                      onSearch={setKeyword}
                      onClear={() => setKeyword('')} />
                    {(sideFilter || keyword) && (
                      <Button type="link" size="small"
                        onClick={() => setSearchParams(current => { const next = new URLSearchParams(current); next.delete('side'); next.delete('q'); return next })}>
                        重置筛选
                      </Button>
                    )}
                  </Space>
                </Card>
                {focusedListing && <Alert type="info" style={{ marginBottom: 12 }} message="已定位你从商城选择的挂牌" description="挂牌可能已成交或过期；以当前大厅数据为准。" action={<Button onClick={() => setSearchParams(current => { const next = new URLSearchParams(current); next.delete('listing'); return next })}>查看全部</Button>}/>}
                {focusedListing && market.find(row => row.id === focusedListing) && (() => {
                  const item = market.find(row => row.id === focusedListing)!
                  return <Card title={`${item.commodityName} · 挂牌详情`} style={{ marginBottom: 16 }}>
                    <Descriptions column={{ xs: 1, sm: 2, lg: 3 }} items={[
                      { key: 'no', label: '挂牌编号', children: item.listingNo },
                      { key: 'seller', label: '挂牌方', children: item.enterpriseName },
                      { key: 'price', label: '挂牌单价', children: item.price == null ? '面议' : `${item.priceText} 元/${item.unit}` },
                      { key: 'quantity', label: '剩余 / 总量', children: `${item.remainingQuantity} / ${item.quantity} ${item.unit}` },
                      { key: 'warehouse', label: '交收仓库', children: item.warehouseName || '未登记' },
                      { key: 'delivery', label: '交付方式', children: item.deliveryMethodText },
                      { key: 'spec', label: '规格', children: Object.entries(item.spec).map(([key, value]) => `${key}：${String(value)}`).join('，') || '未登记' },
                      { key: 'origin', label: '品牌 / 产地', children: [item.brand, item.origin].filter(Boolean).join(' / ') || '未登记' },
                      { key: 'valid', label: '有效期', children: dayjs(item.validUntil).format('YYYY-MM-DD HH:mm') },
                    ]} />
                    <Typography.Text type="secondary">单价为挂牌报价，运输、装卸及其他费用请另行核实；摘牌、签约等操作需你本人确认。</Typography.Text>
                  </Card>
                })()}
                {requestedTab && requestedTab !== 'market' && !isMember && <Alert type="info" style={{ marginBottom: 12 }} message="登录企业账号后可管理挂牌与订单" action={!signedIn ? <Button onClick={() => navigate('/login', { state: { from: `/trading?tab=${requestedTab}` } })}>前往登录</Button> : undefined}/>}
                <Table rowKey="id" size="middle" loading={marketLoading} dataSource={focusedListing ? market.filter(row => row.id === focusedListing) : market} scroll={{ x: 1400 }}
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
                      <Table rowKey="id" size="middle" dataSource={myListings} scroll={{ x: 1200 }}
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
                  // 人们真正会扫视的那个数字。一个显示「21」的页签，其中十一个其实在
                  // 等你动，这个数字就藏起了唯一值得知道的事。
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
                        <Space direction="vertical" size={8} style={{ width: '100%', minWidth: 0 }}>
                          <Segmented
                            value={orderPhase}
                            onChange={(v) => {
                              setOrderPhase(v as 'ACTIVE' | 'FINISHED' | 'ALL')
                              // 阶段筛选属于某个阶段分组；把它带过去，会在一个已经不在
                              // 屏幕上的筛选项下面显示一张空表。
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
                      <Table rowKey="id" size="middle" dataSource={visibleOrders} columns={orderColumns} scroll={{ x: 1400 }}
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
        centered
        styles={{ body: { maxHeight: 'calc(100dvh - 180px)', overflowY: 'auto' } }}
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
                description={acceptTarget.side === 'BUY'
                  ? '你是交付货物的卖方，请选择符合采购要求的自有库存。成交后你的库存减少，采购方获得等量库存。'
                  : '接受卖方挂牌后，你获得等量电子库存，卖方库存减少。价格与交收条款按挂牌内容执行。'} />
            )}
            <Descriptions column={1} size="small" bordered style={{ marginBottom: 16 }}>
              <Descriptions.Item label="挂牌方">{acceptTarget.enterpriseName}</Descriptions.Item>
              <Descriptions.Item label="单价">{acceptTarget.price == null ? '面议：需先协商' : `${acceptTarget.price} 元/${acceptTarget.unit}`}</Descriptions.Item>
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
                id: acceptTarget.id, quantity: String(v.quantity), remark: v.remark as string,
                inventoryNoteId: acceptTarget.side === 'BUY' ? v.inventoryNoteId as EntityId : undefined })}>
              <Form.Item name="quantity" label="摘牌数量"
                rules={[{ required: true, message: '请填写数量' }, positiveDecimalRule(3, '999999999999999.999', '数量')]}>
                <InputNumber stringMode min="0.001" max={acceptTarget.remainingQuantity} step="1"
                  style={{ width: '100%' }} addonAfter={acceptTarget.unit} />
              </Form.Item>
              {acceptTarget.side === 'BUY' && <>
                {stockError && <Alert type="error" showIcon message="匹配库存读取失败" action={<Button onClick={() => void reloadStock()}>重新加载</Button>} />}
                <Form.Item name="inventoryNoteId" label="用于交付的源库存单" extra="仅显示本企业符合单位、规格、仓库和当前数量的库存；提交时服务端会再次校验。"
                  rules={[{ required: true, message: '请选择用于交付的库存单' }]}>
                  <Select showSearch optionFilterProp="label" loading={findingStock} placeholder="明确选择交付库存"
                    options={matchingNotes.map(note => ({ value: note.id, label: `${note.commodityName} · ${note.availableQuantity} ${note.unit} · ${note.warehouseName}` }))}
                    notFoundContent={canFindStock && !findingStock ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有符合采购要求的可用库存" /> : '请先填写合法数量'} />
                </Form.Item>
              </>}
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
        centered
        styles={{ body: { maxHeight: 'calc(100dvh - 180px)', overflowY: 'auto', paddingRight: 4 } }}
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
          initialValues={{ side: 'SELL', priceType: 'FIXED', confirmMode: 'AUTO', deliveryMethod: 'SELF_PICKUP', validUntil: dayjs().add(7, 'day') }}>
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
          {publishSide !== 'BUY' && publishNote && <Descriptions title="货物信息（来自库存）" column={1} size="small" bordered style={{ marginBottom: 16 }} items={[
            { key: 'name', label: '商品 / 品类', children: `${publishNote.commodityName} / ${publishNote.categoryName}` },
            { key: 'brand', label: '品牌 / 产地', children: [publishNote.brand, publishNote.origin].filter(Boolean).join(' / ') || '未登记' },
            { key: 'warehouse', label: '交收仓库', children: publishNote.warehouseName },
            { key: 'quantity', label: '可用数量', children: `${publishNote.availableQuantity} ${publishNote.unit}` },
            { key: 'spec', label: '规格', children: Object.entries(publishNote.spec).map(([key, value]) => `${key}：${String(value)}`).join('，') || '未登记' },
          ]} />}
          {publishSide === 'BUY' && <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="categoryId" label="品类"
                rules={[{ required: publishSide === 'BUY', message: '请选择品类' }]}>
                <Select placeholder="选择品类" showSearch optionFilterProp="label" onChange={() => publishForm.setFieldValue('spec', {})}
                  options={categoryOptions.map((o) => ({ value: o.id, label: o.label }))} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="commodityName" label="商品名称"
                rules={[{ required: publishSide === 'BUY', message: '请填写商品名称' }]}>
                <Input placeholder="采购商品名称" maxLength={128} />
              </Form.Item>
            </Col>
          </Row>}
          {publishSide === 'BUY' && <>
            <Typography.Paragraph type="secondary">采购单位：{buyCategory?.unit ?? '选择品类后确定'}。指定规格按值匹配，未填写的可选项不限制；数字规格 99.7 与 99.700 等价。</Typography.Paragraph>
            {(buyCategory?.specSchema ?? []).map(field => <Form.Item key={field.key} name={['spec', field.key]}
              label={`${field.label}${field.unit ? ` (${field.unit})` : ''}`} rules={[{ required: field.required, message: `请填写${field.label}` }]}>
              {field.type === 'number' ? <InputNumber min={field.unit === '%' ? 0 : undefined} max={field.unit === '%' ? 100 : undefined} style={{ width: '100%' }} /> : <Input maxLength={256} />}
            </Form.Item>)}
            <Row gutter={12}><Col xs={24} sm={12}><Form.Item name="brand" label="指定品牌（可选）"><Input maxLength={64} /></Form.Item></Col>
              <Col xs={24} sm={12}><Form.Item name="origin" label="指定产地（可选）"><Input maxLength={64} /></Form.Item></Col></Row>
          </>}

          <Row gutter={12}>
            <Col xs={24} sm={8}>
              <Form.Item name="quantity" label="数量" rules={[{ required: true, message: '请填写数量' }, positiveDecimalRule(3, '999999999999999.999', '数量')]}>
                <InputNumber stringMode min="0.001" step="1" style={{ width: '100%' }} addonAfter={publishSide === 'BUY' ? undefined : publishNote?.unit} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              <Form.Item name="priceType" label="价格方式">
                <Radio.Group>
                  <Radio value="FIXED">定价</Radio>
                  <Radio value="NEGOTIABLE">面议</Radio>
                </Radio.Group>
              </Form.Item>
            </Col>
            <Col xs={24} sm={8}>
              {publishPriceType === 'FIXED' && (
                <Form.Item name="price" label="单价（元）"
                  rules={[{ required: true, message: '请填写单价' }, positiveDecimalRule(4, '999999999999999.9999', '单价')]}>
                  <InputNumber stringMode min="0.0001" step="100" style={{ width: '100%' }} />
                </Form.Item>
              )}
            </Col>
          </Row>

          <Row gutter={12}>
            {publishSide === 'BUY' && <Col span={12}>
              <Form.Item name="warehouseId" label="交收仓库">
                <Select allowClear placeholder="选择要求的交收仓库（可不限定）"
                  options={warehouses.map((w) => ({ value: w.id, label: w.name }))} />
              </Form.Item>
            </Col>}
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
              disabledDate={(d) => d && d < dayjs().startOf('day')} />
          </Form.Item>

          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={512} showCount />
          </Form.Item>
        </Form>
      </Modal>

      {/* ---------- 订单详情 ---------- */}
      <OrderDetailModal
        order={detailOrder}
        onClose={() => setDetailOrder(null)}
        onAction={runOrderAction}
        onChanged={invalidate}
      />
    </div>
  )
}

/** 订单详情：生命周期步骤条、可执行操作、合同签署与状态轨迹。 */
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
    queryKey: identityKey('order-history', orderId),
    queryFn: () => fetchOrderHistory(orderId as EntityId),
    enabled: Boolean(orderId),
  })

  const { data: contract, refetch: refetchContract } = useQuery({
    queryKey: identityKey('order-contract', orderId),
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
              {/* 只有挂牌方会走到这里：服务端已经把 CONFIRMED 从对方的可执行动作里
                  剥掉了，所以他绝不该按的那个按钮，永远不会为他渲染出来。 */}
              {order.status === 'PENDING_CONFIRM' && order.myRole === 'SELLER' && (
                <Popconfirm title="拒绝这笔摘牌？" description="货权尚未转移，拒绝后挂牌数量原样恢复。"
                  okText="拒绝" cancelText="再想想" okButtonProps={{ danger: true }}
                  onConfirm={() => void onAction('reject', order)}>
                  <Button danger>拒绝摘牌</Button>
                </Popconfirm>
              )}
              {/* 文案来自服务端，而不是写在这里。谁发起交收、谁完成交收，取决于交收
                  条款和读它的是哪一方，而这条规则在客户端再存一份，就可能和服务端会
                  接受的那个按钮对不上。 */}
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
