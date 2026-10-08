import { Badge, Dropdown } from 'antd'
import {
  DownOutlined,
  LogoutOutlined,
  RobotOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { fetchTasks } from '@/api/tasks'
import { useTaskStream } from '@/hooks/useTaskStream'
import { identityKey, useAuthStore } from '@/store/auth'
import { useEffect, useState } from 'react'

export default function MainLayout() {
  const navigate = useNavigate()
  const user = useAuthStore((state) => state.user)
  const signedIn = Boolean(useAuthStore((state) => state.accessToken))
  const clear = useAuthStore((state) => state.clear)
  const [accountOpen, setAccountOpen] = useState(false)
  useEffect(() => {
    if (!accountOpen) return
    const dismiss = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setAccountOpen(false)
    }
    document.addEventListener('keydown', dismiss)
    return () => document.removeEventListener('keydown', dismiss)
  }, [accountOpen])
  useTaskStream()
  const { data: tasks = [] } = useQuery({
    queryKey: identityKey('tasks'),
    queryFn: fetchTasks,
    enabled: signedIn,
    refetchInterval: 60_000,
  })
  const accountMenu = {
    items: [
      {
        key: 'enterprise',
        label: '企业信息',
        onClick: () => navigate('/enterprise'),
      },
      {
        key: 'inventory',
        label: '我的库存',
        onClick: () => navigate('/inventory'),
      },
      ...(user?.platformOperator
          ? [
            {
              key: 'admin-model',
              label: '管理后台 · 模型配置',
              onClick: () => navigate('/admin/model'),
            },
            {
              key: 'knowledge',
              label: '知识库管理',
              onClick: () => navigate('/knowledge'),
            },
          ]
        : []),
      {
        key: 'logout',
        icon: <LogoutOutlined />,
        label: '退出登录',
        onClick: () => {
          clear()
          navigate('/', { replace: true })
        },
      },
    ],
  }
  return (
    <div className="site-shell">
      <a className="skip-link" href="#main-content">
        跳到页面内容
      </a>
      <div className="utility-bar">
        <div className="site-width utility-inner">
          <span>现货通，让大宗采购更直接</span>
          <div className="utility-links">
            {signedIn ? (
              <Dropdown
                menu={accountMenu}
                trigger={['hover', 'click']}
                open={accountOpen}
                onOpenChange={setAccountOpen}
              >
                <button className="account-trigger" aria-expanded={accountOpen}>
                  <UserOutlined /> {user?.realName || user?.username}{' '}
                  <DownOutlined />
                </button>
              </Dropdown>
            ) : (
              <Link className="accent-link" to="/login">
                你好，请登录
              </Link>
            )}
            <Link to="/dashboard">
              我的工作台{' '}
              {tasks.length > 0 && <Badge count={tasks.length} size="small" />}
            </Link>
            <Link to="/inventory">我的库存</Link>
            <Link to="/enterprise">企业中心</Link>
            {user?.platformOperator && <Link to="/admin/model">管理后台</Link>}
          </div>
        </div>
      </div>
      <header className="site-header">
        <div className="site-width header-inner">
          <Link to="/" className="brand" aria-label="现货通首页">
            <span className="brand-symbol" aria-hidden="true">
              S<span>↗</span>
            </span>
            <span className="brand-name">
              现货通<small>SpotLink · 大宗现货</small>
            </span>
          </Link>
          <nav className="main-nav" aria-label="主导航">
            <NavLink to="/" end>
              现货商城
            </NavLink>
            <NavLink to="/trading">挂牌大厅</NavLink>
            <NavLink to="/market">行情中心</NavLink>
          </nav>
          <Link to="/advisor" className="advisor-entry">
            <RobotOutlined /> AI 采购顾问 <span>↗</span>
          </Link>
        </div>
      </header>
      <main id="main-content" tabIndex={-1}>
        <Outlet />
      </main>
      <footer className="site-footer site-width">
        <Link to="/" className="footer-brand">
          现货通 <span>SpotLink</span>
        </Link>
        <p>挂牌、摘牌、签约与交收，一站连接大宗现货。</p>
        <div>
          <Link to="/trading">现货交易</Link>
          <Link to="/market">行情中心</Link>
          <Link to="/advisor">AI 顾问</Link>
        </div>
        <small>个人作品集项目 · 作者 别太在亿啦 · 平台数据仅供学习与演示</small>
      </footer>
    </div>
  )
}
