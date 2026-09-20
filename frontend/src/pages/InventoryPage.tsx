import { useMemo, useState } from 'react'
import {
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Segmented,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs from 'dayjs'
import {
  cancelInventoryNote,
  fetchCategoryTree,
  fetchWarehouses,
  listInventoryNotes,
  registerInventory,
  type InventoryRegisterPayload,
} from '@/api/inventory'
import type { CategoryNode, EntityId, InventoryNoteView } from '@/types/api'

/** Category options with children are not selectable — goods belong to a leaf. */
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

export default function InventoryPage() {
  const [registerOpen, setRegisterOpen] = useState(false)
  const [statusFilter, setStatusFilter] = useState<number | undefined>(undefined)
  const [form] = Form.useForm()
  const queryClient = useQueryClient()

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

  const { mutateAsync: doRegister, isPending: registering } = useMutation({
    mutationFn: (payload: InventoryRegisterPayload) => registerInventory(payload),
    onSuccess: () => {
      void message.success('入库登记成功')
      setRegisterOpen(false)
      form.resetFields()
      void queryClient.invalidateQueries({ queryKey: ['inventory-notes'] })
    },
  })

  const handleRegister = async (values: Record<string, unknown>) => {
    const spec: Record<string, unknown> = {}
    if (values.cu_content !== undefined) spec.cu_content = values.cu_content
    if (values.standard) spec.standard = values.standard

    await doRegister({
      categoryId: values.categoryId as EntityId,
      warehouseId: values.warehouseId as EntityId,
      commodityName: values.commodityName as string,
      brand: values.brand as string | undefined,
      origin: values.origin as string | undefined,
      spec,
      quantity: values.quantity as number,
      unit: values.unit as string | undefined,
      remark: values.remark as string | undefined,
    })
  }

  const handleCancel = async (id: EntityId) => {
    await cancelInventoryNote(id)
    void message.success('已注销')
    void queryClient.invalidateQueries({ queryKey: ['inventory-notes'] })
  }

  const columns = [
    {
      title: '库存单号',
      dataIndex: 'noteNo',
      width: 190,
      render: (value: string) => <Typography.Text code>{value}</Typography.Text>,
    },
    {
      title: '商品',
      dataIndex: 'commodityName',
      render: (value: string, row: InventoryNoteView) => (
        <div>
          <div>{value}</div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {[row.brand, row.origin].filter(Boolean).join(' · ') || '—'}
          </Typography.Text>
        </div>
      ),
    },
    { title: '品类', dataIndex: 'categoryName', width: 110 },
    {
      title: '交收仓库',
      dataIndex: 'warehouseName',
      width: 180,
      render: (value: string) => <Typography.Text style={{ fontSize: 13 }}>{value}</Typography.Text>,
    },
    {
      title: '数量',
      width: 220,
      render: (_: unknown, row: InventoryNoteView) => (
        <Space size={4} direction="vertical" style={{ fontSize: 12, lineHeight: 1.6 }}>
          <span>
            总量 <b>{row.totalQuantity}</b> {row.unit}
          </span>
          <span>
            可用 {row.availableQuantity}
            {row.frozenQuantity > 0 && (
              <Typography.Text type="warning"> · 冻结 {row.frozenQuantity}</Typography.Text>
            )}
          </span>
        </Space>
      ),
    },
    {
      title: '状态',
      dataIndex: 'statusText',
      width: 100,
      render: (value: string, row: InventoryNoteView) => (
        <Tag color={STATUS_COLOURS[row.status] ?? 'default'}>{value}</Tag>
      ),
    },
    {
      title: '入库时间',
      dataIndex: 'createdAt',
      width: 140,
      render: (value: string) => (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {dayjs(value).format('YYYY-MM-DD HH:mm')}
        </Typography.Text>
      ),
    },
    {
      title: '操作',
      width: 90,
      render: (_: unknown, row: InventoryNoteView) => (
        <Popconfirm
          title="注销这个库存单？"
          description="有冻结数量时会被拒绝，需先解除挂牌或订单。"
          okText="注销"
          cancelText="取消"
          okButtonProps={{ danger: true }}
          onConfirm={() => void handleCancel(row.id)}
          disabled={row.status === 6}
        >
          <Button type="link" size="small" danger disabled={row.status === 6}>
            注销
          </Button>
        </Popconfirm>
      ),
    },
  ]

  return (
    <div style={{ padding: 24, maxWidth: 1400, margin: '0 auto' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
        <div>
          <Typography.Title level={4} style={{ margin: 0 }}>
            我的库存单
          </Typography.Title>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            电子库存单是货物在指定交收仓库的数字化凭证，本身不构成物权凭证
          </Typography.Text>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setRegisterOpen(true)}>
          登记入库
        </Button>
      </div>

      <Card
        size="small"
        style={{ marginBottom: 12 }}
        styles={{ body: { padding: '10px 16px' } }}
      >
        <Space>
          <Typography.Text type="secondary">状态筛选</Typography.Text>
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
        size="small"
        loading={isLoading}
        dataSource={notes}
        columns={columns}
        pagination={{ pageSize: 10, showSizeChanger: false }}
        locale={{ emptyText: '还没有库存单，点右上角「登记入库」开始' }}
      />

      <Modal
        title="登记入库"
        open={registerOpen}
        onCancel={() => setRegisterOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={registering}
        okText="确认登记"
        cancelText="取消"
        width={560}
      >
        <Form form={form} layout="vertical" onFinish={handleRegister} style={{ marginTop: 16 }}>
          <Form.Item
            name="categoryId"
            label="品类"
            rules={[{ required: true, message: '请选择品类' }]}
          >
            <Select
              placeholder="选择货物品类"
              options={categoryOptions.map((option) => ({
                value: option.id,
                label: option.label,
              }))}
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

          <Space size={12} style={{ width: '100%' }}>
            <Form.Item name="brand" label="品牌" style={{ width: 250 }}>
              <Input placeholder="如：江铜" />
            </Form.Item>
            <Form.Item name="origin" label="产地" style={{ width: 250 }}>
              <Input placeholder="如：江西" />
            </Form.Item>
          </Space>

          <Form.Item
            name="warehouseId"
            label="交收仓库"
            rules={[{ required: true, message: '请选择交收仓库' }]}
          >
            <Select
              placeholder="货物所在仓库"
              options={warehouses.map((warehouse) => ({
                value: warehouse.id,
                label: `${warehouse.code} ${warehouse.name}`,
              }))}
            />
          </Form.Item>

          <Space size={12}>
            <Form.Item
              name="quantity"
              label="数量"
              rules={[{ required: true, message: '请填写数量' }]}
              style={{ width: 250 }}
            >
              <InputNumber min={0.001} step={1} style={{ width: '100%' }} placeholder="100" />
            </Form.Item>
            <Form.Item name="unit" label="单位" style={{ width: 250 }}>
              <Input placeholder="留空则按品类默认单位" />
            </Form.Item>
          </Space>

          <Space size={12}>
            <Form.Item name="cu_content" label="铜含量 (%)" style={{ width: 250 }}>
              <InputNumber min={0} max={100} step={0.01} style={{ width: '100%' }} placeholder="99.99" />
            </Form.Item>
            <Form.Item name="standard" label="执行标准" style={{ width: 250 }}>
              <Input placeholder="如：GB/T 467-2010" />
            </Form.Item>
          </Space>

          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={256} showCount />
          </Form.Item>

          <Descriptions size="small" column={1} style={{ marginTop: -8 }}>
            <Descriptions.Item>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                登记后货物立即进入「在库」状态，可以挂牌出售。实际平台需要交收仓库确认收货后才会解锁交易。
              </Typography.Text>
            </Descriptions.Item>
          </Descriptions>
        </Form>
      </Modal>
    </div>
  )
}
