import { identityKey } from '@/store/auth'
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
import type { CategoryNode, EntityId, InventoryNoteView, SpecField } from '@/types/api'

/** 只有叶子品类可选——货物总归属于某个具体的东西。 */
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

const STATUS_COLOURS: Record<number, string> = {
  0: 'default',
  1: 'processing',
  2: 'success',
  3: 'warning',
  4: 'warning',
  5: 'default',
  6: 'default',
  7: 'processing',
}

/** 去掉 BigDecimal 保留的尾随零，例如 100.000 -> 100。 */
function qty(value: number | string | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return String(value).replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '')
}

function mills(value: string): bigint {
  const [whole, fraction = ''] = value.split('.')
  return BigInt(whole) * 1000n + BigInt(fraction.padEnd(3, '0'))
}

function formatMills(value: bigint): string {
  return qty(`${value / 1000n}.${String(value % 1000n).padStart(3, '0')}`)
}

export default function InventoryPage() {
  const [registerOpen, setRegisterOpen] = useState(false)
  const [editing, setEditing] = useState<InventoryNoteView | null>(null)
  const [keyword, setKeyword] = useState('')
  const [statusFilter, setStatusFilter] = useState<number | undefined>(undefined)
  const [registerForm] = Form.useForm()
  const [editForm] = Form.useForm()
  const queryClient = useQueryClient()
  const registerCategoryId = Form.useWatch('categoryId', registerForm)
  const editCategoryId = Form.useWatch('categoryId', editForm)

  const invalidate = () => queryClient.invalidateQueries({ queryKey: identityKey('inventory-notes') })

  const { data: notes = [], isLoading, isError, refetch } = useQuery({
    queryKey: identityKey('inventory-notes', statusFilter),
    queryFn: () => listInventoryNotes(statusFilter),
  })

  const { data: categories = [], isError: categoryError, refetch: reloadCategories } = useQuery({
    queryKey: identityKey('category-tree'),
    queryFn: fetchCategoryTree,
  })

  const { data: warehouses = [], isError: warehouseError, refetch: reloadWarehouses } = useQuery({
    queryKey: identityKey('warehouses'),
    queryFn: fetchWarehouses,
  })

  const categoryOptions = useMemo(() => flattenLeaves(categories), [categories])
  const registerCategory = categoryOptions.find(category => category.id === registerCategoryId)
  const editCategory = categoryOptions.find(category => category.id === editCategoryId)
  const specFields = (fields: SpecField[] = [], disabled = false) => fields.map(field => (
    <Form.Item key={field.key} name={['spec', field.key]}
      label={`${field.label}${field.unit ? ` (${field.unit})` : ''}`}
      rules={[{ required: field.required, message: `请填写${field.label}` }]}>
      {field.type === 'number'
        ? <InputNumber disabled={disabled} min={field.unit === '%' ? 0 : undefined}
            max={field.unit === '%' ? 100 : undefined} style={{ width: '100%' }} />
        : <Input disabled={disabled} maxLength={256} />}
    </Form.Item>
  ))

  /** 已注销数量不计入现存货物，不同单位分别汇总。 */
  const summary = useMemo(
    () =>
      notes.filter(note => note.status !== 6).reduce((acc, note) => {
        const group = acc[note.unit] ?? { total: 0n, available: 0n, frozen: 0n }
        group.total += mills(note.totalQuantity)
        group.available += mills(note.availableQuantity)
        group.frozen += mills(note.frozenQuantity)
        acc[note.unit] = group
        return acc
      }, {} as Record<string, { total: bigint; available: bigint; frozen: bigint }>),
    [notes],
  )
  const summaryText = (key: 'total' | 'available' | 'frozen') =>
    Object.entries(summary).map(([unit, group]) => `${formatMills(group[key])} ${unit}`).join(' / ') || '—'

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
        version: editing?.version,
        categoryId: vars.values.categoryId as EntityId,
        commodityName: vars.values.commodityName as string,
        brand: vars.values.brand as string | undefined,
        origin: vars.values.origin as string | undefined,
        spec: { ...(editing && editing.categoryId === vars.values.categoryId ? editing.spec : {}),
          ...vars.values.spec as Record<string, unknown> },
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
      spec: values.spec as Record<string, unknown>,
      quantity: values.quantity as string,
      unit: registerCategory?.unit,
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
    editForm.resetFields()
    editForm.setFieldsValue({
      categoryId: note.categoryId,
      commodityName: note.commodityName,
      brand: note.brand,
      origin: note.origin,
      spec: note.spec,
      remark: note.remark,
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
      width: 140,
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
                aria-label={`修改${row.commodityName}`}
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
    <div className="business-page inventory-page">
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
      {(isError || categoryError || warehouseError) && <Alert type="error" showIcon
        style={{ marginBottom: 16 }} message="暂时无法加载库存或基础资料"
        action={<Button onClick={() => { void refetch(); void reloadCategories(); void reloadWarehouses() }}>重试</Button>} />}

      {/* 汇总跟着当前筛选选中的内容走。 */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic title="库存单" value={notes.length} suffix="单" />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic title="现存总量（按单位）" value={summaryText('total')} />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic
              title="可用"
              value={summaryText('available')}
              valueStyle={{ color: '#389e0d' }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic
              title="冻结"
              value={summaryText('frozen')}
              valueStyle={{ color: '#d46b08' }}
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
              { label: '待交收（受限）', value: 7 },
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
        scroll={{ x: 850 }}
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
              onChange={() => registerForm.setFieldsValue({ spec: undefined })}
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
              placeholder="货物所在交收仓库"
              options={warehouses.map((w) => ({ value: w.id, label: `${w.code} ${w.name}` }))}
            />
          </Form.Item>

          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="quantity" label="数量" rules={[
                { required: true, message: '请填写数量' },
                { pattern: /^(?:0|[1-9]\d{0,14})(?:\.\d{1,3})?$/, message: '最多 15 位整数和 3 位小数' },
              ]}>
                <InputNumber stringMode min="0.001" max="999999999999999.999" step="1" style={{ width: '100%' }} placeholder="100" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item label="单位">
                <Input value={registerCategory?.unit ?? ''} readOnly placeholder="选择品类后显示" />
              </Form.Item>
            </Col>
          </Row>

          {specFields(registerCategory?.specSchema)}

          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={256} showCount />
          </Form.Item>

          <Alert
            type="info"
            showIcon
            message="登记后货物立即进入「在库」状态，可以挂牌出售。"
            description="实际平台需要交收仓库确认收货后才会解锁交易。"
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
          description="数量、仓库、单位不可修改；存在冻结数量时仅可修改备注。"
        />
        <Form form={editForm} layout="vertical" onFinish={(values) => editing && void doUpdate({ id: editing.id, values })}>
          <Form.Item name="categoryId" label="品类" rules={[{ required: true, message: '请选择品类' }]}>
            <Select
              options={categoryOptions.map((o) => ({ value: o.id, label: o.label }))}
              showSearch
              optionFilterProp="label"
              disabled={Number(editing?.frozenQuantity) > 0}
              onChange={() => editForm.setFieldsValue({ spec: undefined })}
            />
          </Form.Item>

          <Form.Item
            name="commodityName"
            label="商品名称"
            rules={[{ required: true, message: '请填写商品名称' }]}
          >
            <Input disabled={Number(editing?.frozenQuantity) > 0} maxLength={128} />
          </Form.Item>

          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="brand" label="品牌">
                <Input disabled={Number(editing?.frozenQuantity) > 0} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="origin" label="产地">
                <Input disabled={Number(editing?.frozenQuantity) > 0} />
              </Form.Item>
            </Col>
          </Row>

          {specFields(editCategory?.specSchema, Number(editing?.frozenQuantity) > 0)}

          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={256} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
