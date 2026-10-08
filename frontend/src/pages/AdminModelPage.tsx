import { useEffect, useState } from 'react'
import { Alert, Button, Card, Col, Form, Input, InputNumber, Popconfirm, Result, Row, Select, Space, Spin, Switch, Tag, Typography, message } from 'antd'
import { ApiOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { identityKey, useAuthStore } from '@/store/auth'
import { fetchModelSettings, resetModelSettings, saveModelSettings, testModelConnection, type ConnectionResult, type ModelUpdate } from '@/api/adminAdvisor'

const presets = {
  docker: { baseUrl: 'http://ollama:11434/v1', model: 'qwen3:4b', apiKey: 'ollama' },
  local: { baseUrl: 'http://host.docker.internal:11434/v1', model: 'qwen3:4b', apiKey: 'ollama' },
  openai: { baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini', apiKey: '' },
  deepseek: { baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat', apiKey: '' },
}

export default function AdminModelPage() {
  const user = useAuthStore(s => s.user)
  const cache = useQueryClient()
  const [form] = Form.useForm<ModelUpdate>()
  const [busy, setBusy] = useState<'save' | 'reset' | 'test' | null>(null)
  const [connection, setConnection] = useState<ConnectionResult | null>(null)
  const [dirty, setDirty] = useState(false)
  const query = useQuery({ queryKey: identityKey('admin-model'), queryFn: fetchModelSettings, enabled: user?.platformOperator })
  const config = query.data
  useEffect(() => {
    if (!config) return
    form.setFieldsValue({ ...config, apiKey: '', clearApiKey: false })
    setDirty(false)
    setConnection(null)
  }, [config, form])
  if (!user?.platformOperator) return <Result status="403" title="无权访问" subTitle="模型服务由平台管理员配置。" />
  if (query.isLoading) return <Spin style={{ display: 'block', margin: 80 }} />
  if (query.isError || !config) return <Result status="warning" title="无法读取模型配置" subTitle="请确认账号具备模型配置权限，或稍后重试。" extra={<Button onClick={() => void query.refetch()}>重新加载</Button>} />

  const invalidate = async () => {
    await cache.invalidateQueries({ queryKey: identityKey('admin-model') })
    await cache.invalidateQueries({ queryKey: identityKey('advisor-status') })
  }
  const save = async (value: ModelUpdate) => {
    setBusy('save')
    try {
      await saveModelSettings(value)
      void message.success('已保存，下一轮顾问调用立即生效')
      await invalidate()
    } catch { /* API 客户端统一显示错误 */ }
    finally { form.setFieldValue('apiKey', ''); setBusy(null) }
  }
  const reset = async () => {
    setBusy('reset')
    try { await resetModelSettings(); await invalidate(); void message.success('已恢复环境配置') }
    catch { /* API 客户端统一显示错误 */ }
    finally { setBusy(null) }
  }
  const test = async () => {
    setBusy('test')
    setConnection(null)
    try { setConnection(await testModelConnection()) }
    catch { /* API 客户端统一显示错误 */ }
    finally { setBusy(null) }
  }

  return <div style={{ maxWidth: 1180, margin: '0 auto', padding: '32px 20px 64px' }}>
    <Typography.Text type="secondary">管理后台 / AI 服务</Typography.Text>
    <Typography.Title level={2} style={{ marginTop: 8 }}>平台模型配置</Typography.Title>
    <Typography.Paragraph type="secondary">为所有用户提供统一的 AI 顾问。支持本地推理服务和 OpenAI 兼容云端 API，由 SpotLink 服务端完成调用。</Typography.Paragraph>
    <Row gutter={[24, 24]}>
      <Col xs={24} lg={15}>
        <Card title="连接与生成设置" extra={<Tag color={config.source === 'admin' ? 'blue' : 'default'}>{config.source === 'admin' ? '后台配置' : '.env 配置'}</Tag>}>
          {!config.canEdit && <Alert type="info" showIcon message="当前账号只有查看权限" style={{ marginBottom: 20 }} />}
          {!config.encryptionReady && <Alert type="warning" showIcon message="后台凭证加密密钥未就绪" description="请在 .env 中恢复或设置独立的 SPOTLINK_ADVISOR_CONFIG_SECRET，然后重启后端。" style={{ marginBottom: 20 }} />}
          <Form form={form} layout="vertical" onFinish={value => void save(value)} disabled={!config.canEdit || busy !== null} onValuesChange={() => { setDirty(true); setConnection(null) }}>
            <Form.Item label="快速填写">
              <Select placeholder="选择示例，也可以直接填写下方字段" onChange={(preset: keyof typeof presets) => {
                form.setFieldsValue({ ...presets[preset], clearApiKey: false, enabled: true, tokenParameter: 'max_tokens' })
                setDirty(true); setConnection(null)
              }} options={[{ value: 'docker', label: 'Ollama · 本项目 Docker 服务' }, { value: 'local', label: 'Ollama · 宿主机服务' }, { value: 'openai', label: 'OpenAI · 云端 API' }, { value: 'deepseek', label: 'DeepSeek · 云端 API' }]} />
            </Form.Item>
            <Form.Item name="enabled" label="启用 AI 顾问" valuePropName="checked"><Switch /></Form.Item>
            <Form.Item name="baseUrl" label="API 地址" rules={[{ required: true, message: '请填写 API 地址' }]} extra="填写 /v1 API 地址或服务根地址。Docker 中访问宿主机需使用 host.docker.internal。">
              <Input placeholder="https://api.openai.com/v1" autoComplete="off" />
            </Form.Item>
            <Form.Item name="model" label="模型 ID" rules={[{ required: true, message: '请填写模型 ID' }]} extra="须与服务端实际模型名一致，并支持工具调用。"><Input placeholder="qwen3:4b 或 gpt-4o-mini" autoComplete="off" /></Form.Item>
            <Form.Item name="apiKey" label="API Key" extra={config.hasKey ? '已有密钥；留空保留，填写新值替换。保存后输入会清空。' : '尚未设置。无鉴权的本地服务可填写 ollama 或 local。'}>
              <Input.Password placeholder={config.hasKey ? '已设置 · 留空保留' : '请输入密钥或本地占位值'} autoComplete="new-password" />
            </Form.Item>
            <Form.Item name="clearApiKey" label="清除已保存密钥" valuePropName="checked" extra="开启后，本次保存会移除密钥并停止顾问调用。"><Switch /></Form.Item>
            <Row gutter={16}>
              <Col xs={24} sm={12}><Form.Item name="maxTokens" label="每次响应输出上限" rules={[{ required: true }]}><InputNumber min={64} max={32768} style={{ width: '100%' }} /></Form.Item></Col>
              <Col xs={24} sm={12}><Form.Item name="timeoutSeconds" label="请求超时（秒）" rules={[{ required: true }]}><InputNumber min={5} max={300} style={{ width: '100%' }} /></Form.Item></Col>
            </Row>
            <Form.Item name="tokenParameter" label="输出上限参数"><Select options={[{ value: 'max_tokens', label: 'max_tokens · 常见兼容服务' }, { value: 'max_completion_tokens', label: 'max_completion_tokens · 要求新版参数的模型' }]} /></Form.Item>
            {config.canEdit && <Space wrap>
              <Button type="primary" htmlType="submit" icon={<SaveOutlined />} loading={busy === 'save'}>保存配置</Button>
              <Popconfirm title="恢复 .env 配置？" description="删除后台覆盖配置与密文，新调用使用后端启动时的环境配置。" onConfirm={() => void reset()} okText="恢复" cancelText="取消"><Button icon={<ReloadOutlined />} loading={busy === 'reset'}>恢复环境配置</Button></Popconfirm>
            </Space>}
          </Form>
        </Card>
      </Col>
      <Col xs={24} lg={9}>
        <Card title="当前服务" style={{ marginBottom: 20 }}>
          <Space wrap style={{ marginBottom: 16 }}><Tag color={config.enabled ? 'green' : 'default'}>{config.enabled ? '已启用' : '已停用'}</Tag><Tag color={config.hasKey ? 'blue' : 'orange'}>{config.hasKey ? '密钥已设置' : '待设置密钥'}</Tag></Space>
          <Typography.Title level={4} style={{ marginTop: 0, overflowWrap: 'anywhere' }}>{config.model}</Typography.Title>
          <Typography.Paragraph type="secondary" style={{ overflowWrap: 'anywhere' }}>{config.baseUrl}</Typography.Paragraph>
          <Typography.Paragraph>配置保存不发起请求。连接测试只发送固定测试语句和专用探针工具，不携带企业数据或聊天记录；云端测试可能消耗 API 额度。</Typography.Paragraph>
          {dirty && <Alert type="info" showIcon message="表单有未保存修改，保存后再测试" style={{ marginBottom: 16 }} />}
          {config.canEdit && <Button icon={<ApiOutlined />} loading={busy === 'test'} disabled={dirty || !config.enabled || !config.hasKey || busy !== null} onClick={() => void test()}>测试已保存配置</Button>}
          {connection && <Alert style={{ marginTop: 16 }} showIcon type={connection.connected && connection.toolCalling ? 'success' : 'warning'} message={connection.message} description={`耗时 ${(connection.durationMs / 1000).toFixed(1)} 秒`} />}
        </Card>
        <Card title="配置如何生效">
          <Typography.Paragraph>后台配置优先于 .env，保存后下一轮调用生效。已在运行的一轮保持原有模型与密钥。</Typography.Paragraph>
          <Typography.Paragraph>若修改 .env，需重启后端；存在后台覆盖时，先点击“恢复环境配置”。</Typography.Paragraph>
          <Typography.Paragraph type="secondary">API Key 加密保存在服务端。模型会收到当前问题、必要历史和有权限的工具摘要；切换提供商会影响后续问题的数据发送目的地。</Typography.Paragraph>
        </Card>
      </Col>
    </Row>
  </div>
}
