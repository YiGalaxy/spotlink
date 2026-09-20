import { Avatar, Badge, Button, Divider, Dropdown, Layout, Menu, Space, Tag, Typography } from 'antd'
import {
  BankOutlined,
  BookOutlined,
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

/**
 * Menu entries map one-to-one onto routes. Modules that do not exist yet are
 * deliberately absent: a menu item leading to a stub page is worse than no
 * menu item, especially in a demo.
 */
const PUBLIC_MENU = [
  { key: '/', icon: <HomeOutlined />, label: '首页' },
  { key: '/market', icon: <LineChartOutlined />, label: '行情' },
  { key: '/trading', icon: <SwapOutlined />, label: '挂牌交易' },
  { key: '/knowledge', icon: <BookOutlined />, label: '知识库' },
]

/**
 * What signing in adds.
 *
 * <p>Each of these reads data scoped to one enterprise — which is why they sit
 * behind the login rather than merely being hidden there. With no enterprise
 * to scope by, every one of them would render empty.
 */
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

  // Pushed when the server can reach us, polled otherwise. The poll is the
  // fallback rather than the mechanism: it keeps a long-idle tab honest if the
  // stream could not be held open.
  useTaskStream()
  const { data: tasks = [] } = useQuery({
    queryKey: ['tasks'],
    queryFn: fetchTasks,
    enabled: signedIn,
    refetchInterval: 60_000,
  })
  const pendingCount = tasks.length

  // Member entries are inserted before 知识库 rather than appended, so signing
  // in does not reshuffle the items a visitor has just learned the positions
  // of.
  const menuItems = signedIn
    ? [PUBLIC_MENU[0], PUBLIC_MENU[1], PUBLIC_MENU[2], ...MEMBER_MENU, PUBLIC_MENU[3]]
    : PUBLIC_MENU

  // The count rides on 挂牌交易 even though the tasks span orders, contracts
  // and delivery. That is where the user goes to act on them, and a count on a
  // menu entry nobody clicks is a number rather than a prompt.
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

  // Exact match, deliberately: every entry is a single path segment and a
  // prefix rule would light up 挂牌交易 while the user is on a sub-page of
  // something else. Sub-pages are modals over their list page rather than
  // routes, which is what keeps this honest.
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

        {/* A portfolio project, and saying so is the point of the footer. Kept
            to one quiet line: a trading screen that shouts about its author is
            a demo, and the screens above it are meant to look like a product. */}
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
