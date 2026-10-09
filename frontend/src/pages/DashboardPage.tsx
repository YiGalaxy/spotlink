import {
  Badge,
  Alert,
  Button,
  Card,
  Col,
  Empty,
  List,
  Popconfirm,
  Row,
  Space,
  Statistic,
  Tag,
  Typography,
  message,
} from 'antd'
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  FileTextOutlined,
  PlayCircleOutlined,
  SignatureOutlined,
} from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import { fetchMyListings, fetchMyOrders, draftContract, signContract, startDelivery, completeOrder, confirmOrder, rejectOrder } from '@/api/trading'
import { fetchTasks } from '@/api/tasks'
import { identityKey, useAuthStore } from '@/store/auth'
import type { TaskView } from '@/types/api'

/** 某种待办类型戴哪个图标。纯装饰——动作才是重点。 */
const KIND_ICON: Record<TaskView['kind'], React.ReactNode> = {
  ACCEPTANCE_PENDING: <ClockCircleOutlined style={{ color: '#d46b08' }} />,
  CONTRACT_TO_SIGN: <SignatureOutlined style={{ color: '#d46b08' }} />,
  CONTRACT_TO_DRAFT: <FileTextOutlined style={{ color: '#8c8c8c' }} />,
  DELIVERY_TO_START: <PlayCircleOutlined style={{ color: '#1668dc' }} />,
  DELIVERY_TO_COMPLETE: <CheckCircleOutlined style={{ color: '#389e0d' }} />,
}

/**
 * 工作台：这家企业欠平台什么，仅此而已。
 *
 * <p>它取代了一页构建进度说明和顾问配置。那些东西描述的是项目；而这里描述的
 * 是用户自己的活儿，这才是一个人打开交易控制台想看到的东西。原来的内容并不算
 * 错，只是写给了错误的读者——它现在住在管理后台里，那里开发者或运营人员才是
 * 受众。
 *
 * <p>待办列表不是在这里拼起来的。它来自服务端的一次汇总，AI 顾问读的也是同一
 * 份，所以问助手和看这个页面不可能给出不同的答案。
 */
export default function DashboardPage() {
  const user = useAuthStore((state) => state.user)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const { data: tasks = [], isLoading: tasksLoading, isError: tasksError, refetch: reloadTasks } = useQuery({
    queryKey: identityKey('tasks'),
    queryFn: fetchTasks,
    // 一个只有重新加载才会更新的工作台，正是这个页面要修掉的 bug；流会推送，
    // 而这里兜住的是流没能保持住的情况。
    refetchInterval: 60_000,
  })

  const { data: listings = [] } = useQuery({
    queryKey: identityKey('my-listings'),
    queryFn: fetchMyListings,
  })

  const { data: orders = [] } = useQuery({
    queryKey: identityKey('my-orders'),
    queryFn: () => fetchMyOrders(),
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: identityKey('tasks') })
    void queryClient.invalidateQueries({ queryKey: identityKey('my-orders') })
    void queryClient.invalidateQueries({ queryKey: identityKey('my-listings') })
    void queryClient.invalidateQueries({ queryKey: identityKey('inventory-notes') })
  }

  const { mutateAsync: run, isPending } = useMutation({
    mutationFn: async (task: TaskView) => {
      switch (task.kind) {
        case 'ACCEPTANCE_PENDING':
          return confirmOrder(task.targetId)
        case 'CONTRACT_TO_SIGN':
          return signContract(task.targetId)
        case 'CONTRACT_TO_DRAFT':
          return draftContract(task.targetId)
        case 'DELIVERY_TO_START':
          return startDelivery(task.targetId)
        case 'DELIVERY_TO_COMPLETE':
          return completeOrder(task.targetId)
      }
    },
    onSuccess: () => {
      void message.success('已处理')
      invalidate()
    },
  })

  /** 唯一一种除了同意之外还给出拒绝的待办类型。 */
  const refuse = async (task: TaskView) => {
    await rejectOrder(task.targetId, '挂牌方拒绝摘牌')
    void message.success('已拒绝')
    invalidate()
  }

  const openListings = listings.filter((l) => ['OPEN', 'PARTIALLY_FILLED'].includes(l.status))
  const openOrders = orders.filter((o) => !['COMPLETED', 'CANCELLED'].includes(o.status))

  return (
    <div className="business-page dashboard-page">
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        你好，{user?.realName || user?.username}
      </Typography.Title>
      {tasksError && <Alert type="error" showIcon message="待办读取失败" action={<Button onClick={() => void reloadTasks()}>重新加载</Button>} style={{ marginBottom: 16 }} />}
      <Typography.Text type="secondary">
        {user?.platformOperator
          ? '当前是平台运营账号，未绑定企业。请从右上角进入管理后台。'
          : `${user?.enterpriseName ?? ''} · 交易席位 ${user?.traderCode ?? '—'}`}
      </Typography.Text>

      <Row gutter={16} style={{ marginTop: 24 }}>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic
              title="待您处理"
              value={tasks.length}
              suffix="项"
              valueStyle={tasks.length > 0 ? { color: '#d46b08', fontSize: 26 } : undefined}
            />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic title="在挂挂牌" value={openListings.length} suffix="笔" />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic title="进行中订单" value={openOrders.length} suffix="笔" />
          </Card>
        </Col>
      </Row>

      <Card
        title="待办事项"
        size="small"
        style={{ marginTop: 16 }}
        extra={
          tasks.length > 0 && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              按紧急程度排序
            </Typography.Text>
          )
        }
      >
        {tasks.length === 0 && !tasksLoading ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="没有需要你处理的事项"
            style={{ padding: 24 }}
          />
        ) : (
          <List
            loading={tasksLoading}
            dataSource={tasks}
            renderItem={(task) => (
              <List.Item
                actions={[
                  task.kind === 'ACCEPTANCE_PENDING' && (
                    <Popconfirm
                      key="reject"
                      title="拒绝这笔摘牌？"
                      description="货权尚未转移，拒绝后挂牌数量原样恢复。"
                      okText="拒绝"
                      cancelText="再想想"
                      okButtonProps={{ danger: true }}
                      onConfirm={() => void refuse(task)}
                    >
                      <Button size="small" danger>
                        拒绝
                      </Button>
                    </Popconfirm>
                  ),
                  <Button
                    key="act"
                    type="primary"
                    size="small"
                    loading={isPending}
                    onClick={() => void run(task)}
                  >
                    {task.action}
                  </Button>,
                ].filter(Boolean)}
              >
                <List.Item.Meta
                  avatar={KIND_ICON[task.kind]}
                  title={
                    <Space size={8} wrap>
                      <Typography.Text strong>{task.action}</Typography.Text>
                      <Typography.Text>{task.commodityName}</Typography.Text>
                      {task.quantity != null && (
                        <Typography.Text type="secondary">
                          {task.quantity} {task.unit}
                        </Typography.Text>
                      )}
                      {task.amount != null && (
                        <Typography.Text type="secondary">
                          {Number(task.amount).toLocaleString()} 元
                        </Typography.Text>
                      )}
                    </Space>
                  }
                  description={
                    <Space size={12} wrap>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        对手 {task.counterparty}
                      </Typography.Text>
                      <Typography.Text code style={{ fontSize: 12 }}>
                        {task.targetNo}
                      </Typography.Text>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {task.detail}
                      </Typography.Text>
                      {task.deadline && (
                        <Tag color="warning" style={{ marginInlineEnd: 0 }}>
                          截止 {dayjs(task.deadline).format('MM-DD HH:mm')}
                        </Tag>
                      )}
                    </Space>
                  }
                />
              </List.Item>
            )}
          />
        )}
      </Card>

      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col xs={24} lg={12}>
          <Card
            size="small"
            title="在挂挂牌"
            extra={
              <Button type="link" size="small" onClick={() => navigate('/trading')}>
                全部
              </Button>
            }
          >
            {openListings.length === 0 ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="没有在挂的挂牌"
                style={{ padding: 16 }}
              />
            ) : (
              <List
                size="small"
                dataSource={openListings.slice(0, 5)}
                renderItem={(listing) => (
                  <List.Item>
                    <Space size={8} wrap>
                      <Tag color={listing.side === 'SELL' ? 'green' : 'blue'}>
                        {listing.sideText}
                      </Tag>
                      <Typography.Text>{listing.commodityName}</Typography.Text>
                      <Typography.Text type="secondary">
                        {listing.remainingQuantity} / {listing.quantity} {listing.unit}
                      </Typography.Text>
                      <Typography.Text strong>{listing.priceText}</Typography.Text>
                    </Space>
                  </List.Item>
                )}
              />
            )}
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card
            size="small"
            title="进行中订单"
            extra={
              <Button type="link" size="small" onClick={() => navigate('/trading')}>
                全部
              </Button>
            }
          >
            {openOrders.length === 0 ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="没有进行中的订单"
                style={{ padding: 16 }}
              />
            ) : (
              <List
                size="small"
                dataSource={openOrders.slice(0, 5)}
                renderItem={(order) => (
                  <List.Item>
                    <Space size={8} wrap>
                      <Tag color={order.myRole === 'BUYER' ? 'blue' : 'green'}>
                        {order.myRole === 'BUYER' ? '买入' : '卖出'}
                      </Tag>
                      <Typography.Text>{order.commodityName}</Typography.Text>
                      <Typography.Text type="secondary">
                        {order.quantity} {order.unit}
                      </Typography.Text>
                      <Tag>{order.statusText}</Tag>
                      {order.allowedActions.length > 0 && (
                        <Badge status="processing" text={<Typography.Text type="secondary" style={{ fontSize: 12 }}>待你处理</Typography.Text>} />
                      )}
                    </Space>
                  </List.Item>
                )}
              />
            )}
          </Card>
        </Col>
      </Row>
    </div>
  )
}
