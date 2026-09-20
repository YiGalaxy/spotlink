import { Avatar, Dropdown, Layout, Menu, Tag, Typography } from 'antd'
import {
  BankOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  LineChartOutlined,
  LogoutOutlined,
  RobotOutlined,
  SwapOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/store/auth'

const { Header, Sider, Content } = Layout

/**
 * Menu entries map one-to-one onto routes. Modules that do not exist yet are
 * deliberately absent: a menu item leading to a stub page is worse than no
 * menu item, especially in a demo.
 */
const MENU_ITEMS = [
  { key: '/', icon: <DashboardOutlined />, label: '工作台' },
  { key: '/market', icon: <LineChartOutlined />, label: '行情' },
  { key: '/trading', icon: <SwapOutlined />, label: '挂单交易' },
  { key: '/inventory', icon: <DatabaseOutlined />, label: '我的库存' },
  { key: '/advisor', icon: <RobotOutlined />, label: 'AI 顾问' },
  { key: '/enterprise', icon: <BankOutlined />, label: '企业信息' },
]

export default function MainLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const user = useAuthStore((state) => state.user)
  const clear = useAuthStore((state) => state.clear)

  const selectedKey =
    MENU_ITEMS.find((item) => item.key === location.pathname)?.key ?? '/'

  const handleLogout = () => {
    clear()
    navigate('/login', { replace: true })
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
          }}
        >
          大宗现货交易平台
        </div>
        <Menu
          mode="inline"
          selectedKeys={[selectedKey]}
          items={MENU_ITEMS}
          style={{ borderInlineEnd: 'none', paddingTop: 8 }}
          onClick={({ key }) => navigate(key)}
        />
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
            <span style={{ cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 8 }}>
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
        </Header>

        <Content style={{ background: '#f5f6f8' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
