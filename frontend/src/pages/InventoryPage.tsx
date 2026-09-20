import { useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Row,
  Segmented,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import { EditOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs from 'dayjs'
import {
  cancelInventoryNote,
  fetchCategoryTree,
  fetchWarehouses,
  listInventoryNotes,
  registerInventory,
  updateInventoryNote,
  type InventoryRegisterPayload,
} from '@/api/inventory'
import type { CategoryNode, EntityId, InventoryNoteView } from '@/types/api'

/** Only leaf categories are selectable — goods belong to something specific. */
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

const STATUS_COLOURS: Record<number, string> = {
  0: 'default',
  1: 'processing',
  2: 'success',
  3: 'warning',
  4: 'warning',
  5: 'default',
  6: 'default',
}

/** Trims the trailing zeros BigDecimal keeps, e.g. 100.000 -> 100. */
function qty(value: number | string | null | undefined): string {
  if (value === null || value === undefined) return '—'
  const num = Number(value)
  return Number.isInteger(num) ? String(num) : num.toFixed(3).replace(/0+$/, '').replace(/\.$/, '')
}

export default function InventoryPage() {
  const [registerOpen, setRegisterOpen] = useState(false)
  const [editing, setEditing] = useState<InventoryNoteView | null>(null)
  const [keyword, setKeyword] = useState('')
  const [statusFilter, setStatusFilter] = useState<number | undefined>(undefined)
  const [registerForm] = Form.useForm()
  const [editForm] = Form.useForm()
  const queryClient = useQueryClient()

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['inventory-notes'] })

  const { data: notes = [], isLoading } = useQuery({
    queryKey: ['inventory-notes', statusFilter],
    queryFn: () => listInventoryNotes(statusFilter),
  })

  const { data: categories = [] } = useQuery({
    queryKey: ['category-tree'],
    queryFn: fetchCategoryTree,
  })

  const { data: warehouses = [] } = useQuery({
    queryKey: ['warehouses'],
    queryFn: fetchWarehouses,
  })

  const categoryOptions = useMemo(() => flattenLeaves(categories), [categories])

  /** Totals across the notes currently loaded, so the header reflects the filter. */
  const summary = useMemo(
    () =>
      notes.reduce(
        (acc, note) => ({
          count: acc.count + 1,
          total: acc.total + Number(note.totalQuantity),
          available: acc.available + Number(note.availableQuantity),
          frozen: acc.frozen + Number(note.frozenQuantity),
        }),
        { count: 0, total: 0, available: 0, frozen: 0 },
      ),
    [notes],
  )

  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase()
    if (!kw) return notes
    return notes.filter(
      (note) =>
        note.commodityName.toLowerCase().includes(kw) ||
        note.noteNo.toLowerCase().includes(kw) ||
        (note.brand ?? '').toLowerCase().includes(kw),
    )
  }, [notes, keyword])

  const { mutateAsync: doRegister, isPending: registering } = useMutation({
    mutationFn: (payload: InventoryRegisterPayload) => registerInventory(payload),
    onSuccess: () => {
      void message.success('入库登记成功')
      setRegisterOpen(false)
      registerForm.resetFields()
      void invalidate()
    },
  })

  const { mutateAsync: doUpdate, isPending: updating } = useMutation({
    mutationFn: (vars: { id: EntityId; values: Record<string, unknown> }) =>
      updateInventoryNote(vars.id, {
        categoryId: vars.values.categoryId as EntityId,
        commodityName: vars.values.commodityName as string,
        brand: vars.values.brand as string | undefined,
        origin: vars.values.origin as string | undefined,
        spec: {
          ...(vars.values.cu_content !== undefined ? { cu_content: vars.values.cu_content } : {}),
          ...(vars.values.standard ? { standard: vars.values.standard } : {}),
        },
        remark: vars.values.remark as string | undefined,
      }),
    onSuccess: () => {
      void message.success('已保存')
      setEditing(null)
      void invalidate()
    },
  })

  const handleRegister = async (values: Record<string, unknown>) => {
    await doRegister({
      categoryId: values.categoryId as EntityId,
      warehouseId: values.warehouseId as EntityId,
      commodityName: values.commodityName as string,
      brand: values.brand as string | undefined,
      origin: values.origin as string | undefined,
      spec: {
        ...(values.cu_content !== undefined ? { cu_content: values.cu_content } : {}),
        ...(values.standard ? { standard: values.standard } : {}),
      },
      quantity: values.quantity as number,
      unit: values.unit as string | undefined,
      remark: values.remark as string | undefined,
    })
  }

  const handleCancel = async (id: EntityId) => {
    await cancelInventoryNote(id)
    void message.success('已注销')
    void invalidate()
  }

  const openEdit = (note: InventoryNoteView) => {
    setEditing(note)
    editForm.setFieldsValue({
      categoryId: note.categoryId,
      commodityName: note.commodityName,
      brand: note.brand,
      origin: note.origin,
      cu_content: note.spec?.cu_content,
      standard: note.spec?.standard,
      remark: undefined,
    })
  }

  const columns = [
    {
      title: '库存单',
      dataIndex: 'noteNo',
      width: 230,
      render: (value: string, row: InventoryNoteView) => (
        <div>
          <Typography.Text code style={{ fontSize: 12 }}>
            {value}
          </Typography.Text>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 11 }}>
              {dayjs(row.createdAt).format('YYYY-MM-DD HH:mm')}
            </Typography.Text>
          </div>
        </div>
      ),
    },
    {
      title: '商品',
      dataIndex: 'commodityName',
      render: (value: string, row: InventoryNoteView) => (
        <div>
          <Typography.Text strong>{value}</Typography.Text>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {[row.categoryName, row.brand, row.origin].filter(Boolean).join(' · ')}
            </Typography.Text>
          </div>
        </div>
      ),
    },
    {
      title: '交收仓库',
      dataIndex: 'warehouseName',
      width: 160,
      render: (value: string) => (
        <Typography.Text style={{ fontSize: 13 }}>{value}</Typography.Text>
      ),
    },
    {
      title: '可用 / 总量',
      width: 170,
      sorter: (a: InventoryNoteView, b: InventoryNoteView) =>
        Number(a.availableQuantity) - Number(b.availableQuantity),
      render: (_: unknown, row: InventoryNoteView) => (
        <div style={{ lineHeight: 1.5 }}>
          <div>
            <Typography.Text strong style={{ fontSize: 16 }}>
              {qty(row.availableQuantity)}
            </Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {' '}
              / {qty(row.totalQuantity)} {row.unit}
            </Typography.Text>
          </div>
          {Number(row.frozenQuantity) > 0 && (
            <Tag color="warning" style={{ marginTop: 2 }}>
              冻结 {qty(row.frozenQuantity)}
            </Tag>
          )}
        </div>
      ),
    },
    {
      title: '状态',
      dataIndex: 'statusText',
      width: 96,
      render: (value: string, row: InventoryNoteView) => (
        <Tag color={STATUS_COLOURS[row.status] ?? 'default'}>{value}</Tag>
      ),
    },
    {
      title: '操作',
      width: 120,
      render: (_: unknown, row: InventoryNoteView) => {
        const locked = row.status === 5 || row.status === 6
        return (
          <Space size={0}>
            <Tooltip title={locked ? '已交收或已注销，不能修改' : '修改描述信息'}>
              <Button
                type="link"
                size="small"
                icon={<EditOutlined />}
                disabled={locked}
                onClick={() => openEdit(row)}
              />
            </Tooltip>
            <Popconfirm
              title="注销这个库存单？"
              description="有冻结数量时会被拒绝，需先解除挂牌或订单。"
              okText="注销"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              onConfirm={() => void handleCancel(row.id)}
              disabled={locked}
            >
              <Button type="link" size="small" danger disabled={locked}>
                注销
              </Button>
            </Popconfirm>
          </Space>
        )
      },
    },
  ]

  return (
    <div style={{ padding: 24, maxWidth: 1500, margin: '0 auto' }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'flex-start',
          justifyContent: 'space-between',
          marginBottom: 16,
        }}
      >
        <div>
          <Typography.Title level={4} style={{ margin: 0 }}>
            我的库存
          </Typography.Title>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            电子库存单是货物在指定交收仓库的数字化凭证，本身不构成物权凭证
          </Typography.Text>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setRegisterOpen(true)}>
          登记入库
        </Button>
      </div>

      {/* Summary reflects whatever the current filter selected. */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic title="库存单" value={summary.count} suffix="单" />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic title="总量" value={qty(summary.total)} suffix="吨" />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic
              title="可用"
              value={qty(summary.available)}
              suffix="吨"
              valueStyle={{ color: '#389e0d' }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic
              title="冻结"
              value={qty(summary.frozen)}
              suffix="吨"
              valueStyle={{ color: summary.frozen > 0 ? '#d46b08' : undefined }}
            />
          </Card>
        </Col>
      </Row>

      <Card size="small" style={{ marginBottom: 12 }} styles={{ body: { padding: '10px 16px' } }}>
        <Space wrap size={16}>
          <Input
            allowClear
            prefix={<SearchOutlined />}
            placeholder="搜索商品名称、单号或品牌"
            style={{ width: 260 }}
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
          />
          <Segmented
            value={statusFilter ?? 'all'}
            onChange={(value) => setStatusFilter(value === 'all' ? undefined : (value as number))}
            options={[
              { label: '全部', value: 'all' },
              { label: '在库', value: 2 },
              { label: '部分冻结', value: 4 },
              { label: '全部冻结', value: 3 },
              { label: '已交收', value: 5 },
              { label: '已注销', value: 6 },
            ]}
          />
        </Space>
      </Card>

      <Table
        rowKey="id"
        size="middle"
        loading={isLoading}
        dataSource={filtered}
        columns={columns}
        pagination={{ pageSize: 10, showSizeChanger: false, hideOnSinglePage: true }}
        locale={{
          emptyText:
            notes.length === 0 ? (
              <Empty description="还没有库存单，点右上角「登记入库」开始" />
            ) : (
              <Empty description="没有符合搜索条件的库存单" />
            ),
        }}
      />

      {/* ---------- register ---------- */}
      <Modal
        title="登记入库"
        open={registerOpen}
        onCancel={() => setRegisterOpen(false)}
        onOk={() => registerForm.submit()}
        confirmLoading={registering}
        okText="确认登记"
        cancelText="取消"
        width={560}
      >
        <Form form={registerForm} layout="vertical" onFinish={handleRegister} style={{ marginTop: 16 }}>
          <Form.Item name="categoryId" label="品类" rules={[{ required: true, message: '请选择品类' }]}>
            <Select
              placeholder="选择货物品类"
              options={categoryOptions.map((o) => ({ value: o.id, label: o.label }))}
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>

          <Form.Item
            name="commodityName"
            label="商品名称"
            rules={[{ required: true, message: '请填写商品名称' }]}
          >
            <Input placeholder="如：江铜电解铜" />
          </Form.Item>

          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="brand" label="品牌">
                <Input placeholder="如：江铜" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="origin" label="产地">
                <Input placeholder="如：江西" />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item name="warehouseId" label="交收仓库" rules={[{ required: true, message: '请选择交收仓库' }]}>
            <Select
              placeholder="货物所在仓库"
              options={warehouses.map((w) => ({ value: w.id, label: `${w.code} ${w.name}` }))}
            />
          </Form.Item>

          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="quantity" label="数量" rules={[{ required: true, message: '请填写数量' }]}>
                <InputNumber min={0.001} step={1} style={{ width: '100%' }} placeholder="100" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="unit" label="单位">
                <Input placeholder="留空按品类默认" />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="cu_content" label="铜含量 (%)">
                <InputNumber min={0} max={100} step={0.01} style={{ width: '100%' }} placeholder="99.99" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="standard" label="执行标准">
                <Input placeholder="如：GB/T 467-2010" />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={256} showCount />
          </Form.Item>

          <Alert
            type="info"
            showIcon
            message="登记后货物立即进入「在库」状态，可以挂牌出售。"
            description="实际平台需要交收仓库确认收货后才会解锁交易——平台记录仓库告诉它的信息，而不是听信存货方的自述。"
          />
        </Form>
      </Modal>

      {/* ---------- edit ---------- */}
      <Modal
        title="修改库存单"
        open={editing !== null}
        onCancel={() => setEditing(null)}
        onOk={() => editForm.submit()}
        confirmLoading={updating}
        okText="保存"
        cancelText="取消"
        width={560}
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="只能修改描述信息"
          description="数量、仓库、单位不可修改。那些是货物的物理事实，变动要走入库、出库或移库流程，而不是改表单——否则平台记录和仓库里实际的货就对不上了。"
        />
        <Form form={editForm} layout="vertical" onFinish={(values) => editing && void doUpdate({ id: editing.id, values })}>
          <Form.Item name="categoryId" label="品类" rules={[{ required: true, message: '请选择品类' }]}>
            <Select
              options={categoryOptions.map((o) => ({ value: o.id, label: o.label }))}
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>

          <Form.Item
            name="commodityName"
            label="商品名称"
            rules={[{ required: true, message: '请填写商品名称' }]}
          >
            <Input />
          </Form.Item>

          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="brand" label="品牌">
                <Input />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="origin" label="产地">
                <Input />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="cu_content" label="铜含量 (%)">
                <InputNumber min={0} max={100} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="standard" label="执行标准">
                <Input />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={256} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
