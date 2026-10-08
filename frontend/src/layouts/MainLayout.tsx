import { Avatar, Badge, Button, Divider, Dropdown, Layout, Menu, Space, Tag, Typography } from 'antd'
import {
  BankOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  HomeOutlined,
  LineChartOutlined,
  LoginOutlined,
  LogoutOutlined,
  RobotOutlined,
  SwapOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { fetchTasks } from '@/api/tasks'
import { useTaskStream } from '@/hooks/useTaskStream'
import { useAuthStore } from '@/store/auth'

const { Header, Sider, Content, Footer } = Layout

const footerStyle: React.CSSProperties = {
  textAlign: 'center',
  background: '#fff',
  borderTop: '1px solid #eceef2',
  padding: '12px 24px',
  height: 'auto',
  lineHeight: 1.6,
}

/** 菜单项与可用路由保持一一对应。 */
const PUBLIC_MENU = [
  { key: '/', icon: <HomeOutlined />, label: '首页' },
  { key: '/market', icon: <LineChartOutlined />, label: '行情' },
  { key: '/trading', icon: <SwapOutlined />, label: '挂牌交易' },
]

// 知识库仅供运营后台维护和查看。

/** 登录后可见、按企业数据隔离的菜单。 */
const MEMBER_MENU = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '工作台' },
  { key: '/inventory', icon: <DatabaseOutlined />, label: '我的库存' },
  { key: '/advisor', icon: <RobotOutlined />, label: 'AI 顾问' },
  { key: '/enterprise', icon: <BankOutlined />, label: '企业信息' },
]

export default function MainLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const user = useAuthStore((state) => state.user)
  const accessToken = useAuthStore((state) => state.accessToken)
  const clear = useAuthStore((state) => state.clear)

  const signedIn = Boolean(accessToken)

  // SSE 实时刷新，轮询兜底。
  useTaskStream()
  const { data: tasks = [] } = useQuery({
    queryKey: ['tasks'],
    queryFn: fetchTasks,
    enabled: signedIn,
    refetchInterval: 60_000,
  })
  const pendingCount = tasks.length

  // 保持公开菜单顺序不变。
  const menuItems = signedIn
    ? [...PUBLIC_MENU, ...MEMBER_MENU]
    : PUBLIC_MENU

  // 将跨模块待办集中提示到交易入口。
  const itemsWithBadge = menuItems.map((item) =>
    item.key === '/trading' && pendingCount > 0
      ? {
          ...item,
          label: (
            <Space size={6}>
              <span>{item.label}</span>
              <Badge count={pendingCount} size="small" />
            </Space>
          ),
        }
      : item,
  )

  // 菜单项使用精确路径匹配。
  const selectedKey = menuItems.find((item) => item.key === location.pathname)?.key ?? '/'

  const handleLogout = () => {
    clear()
    navigate('/', { replace: true })
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider theme="light" width={216} style={{ borderRight: '1px solid #eceef2' }}>
        <div
          style={{
            height: 56,
            display: 'flex',
            alignItems: 'center',
            padding: '0 20px',
            fontWeight: 600,
            fontSize: 15,
            borderBottom: '1px solid #eceef2',
            cursor: 'pointer',
          }}
          onClick={() => navigate('/')}
        >
          现货通 SpotLink
        </div>
        <Menu
          mode="inline"
          selectedKeys={[selectedKey]}
          items={itemsWithBadge}
          style={{ borderInlineEnd: 'none', paddingTop: 8 }}
          onClick={({ key }) => navigate(key)}
        />

        {!signedIn && (
          <div style={{ padding: '16px 20px' }}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              浏览无需登录。
              <br />
              交易、库存与 AI 顾问需要企业账号。
            </Typography.Text>
          </div>
        )}
      </Sider>

      <Layout>
        <Header
          style={{
            background: '#fff',
            borderBottom: '1px solid #eceef2',
            paddingInline: 24,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-end',
            height: 56,
            lineHeight: '56px',
          }}
        >
          {signedIn ? (
            <Dropdown
              placement="bottomRight"
              menu={{
                items: [
                  {
                    key: 'logout',
                    icon: <LogoutOutlined />,
                    label: '退出登录',
                    onClick: handleLogout,
                  },
                ],
              }}
            >
              <span
                style={{ cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 8 }}
              >
                <Avatar size={26} icon={<UserOutlined />} />
                <Typography.Text>{user?.realName || user?.username}</Typography.Text>
                {user?.platformOperator ? (
                  <Tag color="gold" style={{ marginInlineEnd: 0 }}>
                    平台运营
                  </Tag>
                ) : (
                  user?.traderCode && (
                    <Tag color="blue" style={{ marginInlineEnd: 0 }}>
                      {user.traderCode}
                    </Tag>
                  )
                )}
              </span>
            </Dropdown>
          ) : (
            <Space size={10}>
              <Typography.Text type="secondary" style={{ fontSize: 13 }}>
                未登录 · 浏览模式
              </Typography.Text>
              <Button
                type="primary"
                size="small"
                icon={<LoginOutlined />}
                onClick={() => navigate('/login')}
              >
                登录 / 注册
              </Button>
            </Space>
          )}
        </Header>

        <Content style={{ background: '#f5f6f8' }}>
          <Outlet />
        </Content>

        {/* 标注项目属性。 */}
        <Footer style={footerStyle}>
          <Space size={8} wrap split={<Divider type="vertical" />}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              个人作品集项目 · 现货通 SpotLink
            </Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              作者 别太在亿啦
            </Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              Java 21 · Spring Boot 3 · MySQL 8 · Spring AI · React 19
            </Typography.Text>
          </Space>
        </Footer>
      </Layout>
    </Layout>
  )
}
