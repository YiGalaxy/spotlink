import { useState } from 'react'
import { Button, Card, Form, Input, Typography, Alert, Divider } from 'antd'
import { LockOutlined, UserOutlined } from '@ant-design/icons'
import { useLocation, useNavigate } from 'react-router-dom'
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
    setLoading(true)
    try {
      const data = await loginApi(values.username, values.password)
      login(data)
      notifySuccess('登录成功')
      // 回到守卫当初打断他们的地方，或者工作台。不是首页：刚刚登录的人是
      // 来这里干活的，而公开页面在菜单里只差一次点击。
      const from = (location.state as { from?: string } | null)?.from
      navigate(from ?? '/dashboard', { replace: true })
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
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'linear-gradient(135deg, #eef3ff 0%, #f7f8fa 55%, #eef7f2 100%)',
        padding: 24,
      }}
    >
      <Card style={{ width: 400 }} styles={{ body: { padding: 32 } }}>
        <Typography.Title level={3} style={{ marginBottom: 4 }}>
          现货通 SpotLink
        </Typography.Title>
        <Typography.Text type="secondary">
          挂牌 · 摘牌 · 协议交易
        </Typography.Text>

        <Form form={form} layout="vertical" onFinish={onFinish} style={{ marginTop: 28 }}>
          <Form.Item
            name="username"
            label="用户名"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input prefix={<UserOutlined />} placeholder="用户名" size="large" autoComplete="username" />
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

          <Button type="primary" htmlType="submit" size="large" block loading={loading}>
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
                  <Typography.Link onClick={() => fillAccount(account.username)}>
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
  )
}
