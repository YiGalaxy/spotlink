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

/**
 * 菜单项与路由一一对应。尚不存在的模块刻意不放进来：一个通向占位页面的
 * 菜单项比没有这个菜单项更糟，在演示里尤其如此。
 */
const PUBLIC_MENU = [
  { key: '/', icon: <HomeOutlined />, label: '首页' },
  { key: '/market', icon: <LineChartOutlined />, label: '行情' },
  { key: '/trading', icon: <SwapOutlined />, label: '挂牌交易' },
]

// 知识库 是有意不放在这里的。它是平台自己的规则手册——助手从中检索的语料，
// 也是运营方要维护的东西。会员想翻一翻规则是合理的诉求，但维护规则不是，
// 而这个页面目前两件事都做了。它移到了运营控制台，让写规则的人能看见助手
// 究竟是根据什么在回答。

/**
 * 登录之后多出来的东西。
 *
 * <p>其中每一项读的都是限定到某一家企业的数据——这就是它们被放在登录后面、
 * 而不只是被藏在登录后面的原因。没有企业可供限定范围时，它们每一个都会渲染
 * 成空页面。
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

  // 服务端推得过来就推，否则轮询。轮询是兜底而不是机制：当流没能保持住连接
  // 时，它让一个久置未动的标签页不至于说谎。
  useTaskStream()
  const { data: tasks = [] } = useQuery({
    queryKey: ['tasks'],
    queryFn: fetchTasks,
    enabled: signedIn,
    refetchInterval: 60_000,
  })
  const pendingCount = tasks.length

  // 会员项追加在后面，这样登录不会把访客刚记住位置的菜单项重新洗牌。
  const menuItems = signedIn
    ? [...PUBLIC_MENU, ...MEMBER_MENU]
    : PUBLIC_MENU

  // 角标挂在 挂牌交易 上，尽管这些待办横跨订单、合同和发货。用户正是去那里
  // 处理它们的，而挂在一个没人点的菜单项上的计数，只是个数字而不是提示。
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

  // 刻意用精确匹配：每个菜单项都是单层路径段，而前缀规则会让用户在别的
  // 页面子级时 挂牌交易 也亮起来。子页面是叠在各自列表页上的弹窗而非路由，
  // 正是这一点让它保持诚实。
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

        {/* 这是一个作品集项目，把这件事说出来正是页脚存在的意义。克制在
            一行安静的说明里：一个大声喊着作者名字的交易界面就是演示，而
            它上面的那些界面是要看起来像产品的。 */}
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
