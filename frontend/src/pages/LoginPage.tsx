import { useState } from 'react'
import { Button, Card, Form, Input, Typography, Alert, Divider } from 'antd'
import { LockOutlined, UserOutlined } from '@ant-design/icons'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import CommodityArtwork from '@/components/CommodityArtwork'
import { login as loginApi } from '@/api/auth'
import { useAuthStore } from '@/store/auth'
import { notifySuccess } from '@/utils/notify'

interface LoginForm {
  username: string
  password: string
}

const DEMO_ACCOUNTS = [
  { username: 'seller01', label: '卖方（已审核）' },
  { username: 'buyer01', label: '买方（已审核）' },
  { username: 'admin', label: '平台运营' },
  { username: 'pending01', label: '企业待审核' },
]

export default function LoginPage() {
  const [loading, setLoading] = useState(false)
  const [form] = Form.useForm<LoginForm>()
  const login = useAuthStore((state) => state.login)
  const navigate = useNavigate()
  const location = useLocation()

  const onFinish = async (values: LoginForm) => {
    const sessionId = useAuthStore.getState().sessionId
    setLoading(true)
    try {
      const data = await loginApi(values.username, values.password)
      if (useAuthStore.getState().sessionId !== sessionId) return
      login(data)
      notifySuccess('登录成功')
      // 主动登录后回商城；从受保护功能进入时保留原目标。
      const from = (location.state as { from?: string } | null)?.from
      navigate(from ?? '/', { replace: true })
    } catch {
      // axios 层已经把原因提示出来了。
    } finally {
      setLoading(false)
    }
  }

  const fillAccount = (username: string) => {
    form.setFieldsValue({ username, password: 'Admin@123' })
  }

  return (
    <div className="login-screen">
      <Link to="/" className="brand" aria-label="现货通首页">
        <span className="brand-symbol" aria-hidden="true">
          S<span>↗</span>
        </span>
        <span className="brand-name">
          现货通<small>SpotLink · 大宗现货</small>
        </span>
      </Link>
      <div className="login-content">
        <section className="login-intro">
          <span className="eyebrow">SPOTLINK / 连接每一笔现货生意</span>
          <h1>
            好货在眼前，
            <br />
            生意更进一步。
          </h1>
          <p>
            登录企业账号，管理库存、处理订单，
            <br />让 AI 顾问帮你理清下一步。
          </p>
          <CommodityArtwork name="钢材" />
        </section>
        <Card className="login-card" styles={{ body: { padding: 32 } }}>
          <Typography.Title level={3} style={{ marginBottom: 4 }}>
            欢迎登录
          </Typography.Title>
          <Typography.Text type="secondary">
            挂牌 · 摘牌 · 协议交易
          </Typography.Text>

          <Form
            form={form}
            layout="vertical"
            onFinish={onFinish}
            style={{ marginTop: 28 }}
          >
            <Form.Item
              name="username"
              label="用户名"
              rules={[{ required: true, message: '请输入用户名' }]}
            >
              <Input
                prefix={<UserOutlined />}
                placeholder="用户名"
                size="large"
                autoComplete="username"
              />
            </Form.Item>

            <Form.Item
              name="password"
              label="密码"
              rules={[{ required: true, message: '请输入密码' }]}
            >
              <Input.Password
                prefix={<LockOutlined />}
                placeholder="密码"
                size="large"
                autoComplete="current-password"
              />
            </Form.Item>

            <Button
              type="primary"
              htmlType="submit"
              size="large"
              block
              loading={loading}
            >
              登录
            </Button>
          </Form>

          <Divider plain style={{ marginTop: 28, fontSize: 12 }}>
            开发环境账号
          </Divider>
          <Alert
            type="info"
            showIcon={false}
            style={{ padding: '8px 12px' }}
            message={
              <div style={{ fontSize: 12, lineHeight: 2 }}>
                {DEMO_ACCOUNTS.map((account) => (
                  <div key={account.username}>
                    <Typography.Link
                      onClick={() => fillAccount(account.username)}
                    >
                      {account.username}
                    </Typography.Link>
                    <Typography.Text type="secondary">
                      {' '}
                      / Admin@123 — {account.label}
                    </Typography.Text>
                  </div>
                ))}
              </div>
            }
          />
        </Card>
      </div>
      <div className="login-footer">
        个人作品集项目 ·{' '}
        <Link to="/" className="login-home">
          返回现货商城
        </Link>
      </div>
    </div>
  )
}
