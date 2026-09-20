import { Alert, Card, Descriptions, Tag, Typography } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { fetchCurrentUser } from '@/api/auth'

const USER_TYPES: Record<number, string> = {
  1: '企业用户',
  2: '平台运营',
  3: '超级管理员',
}

export default function EnterprisePage() {
  const { data, isLoading } = useQuery({
    queryKey: ['current-user'],
    queryFn: fetchCurrentUser,
  })

  return (
    <div style={{ padding: 24, maxWidth: 900, margin: '0 auto' }}>
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        企业信息
      </Typography.Title>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="这里的数据来自 GET /api/auth/me"
        description="企业 ID 由后端从 JWT 中解析，前端从不传企业 ID。这是多租户隔离的做法：越权在结构上就不可能发生。"
      />

      <Card loading={isLoading} title="账号" size="small" style={{ marginBottom: 16 }}>
        <Descriptions column={2} size="small">
          <Descriptions.Item label="用户名">{data?.username ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="姓名">{data?.realName ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="账号类型">
            {data ? (USER_TYPES[data.userType] ?? '未知') : '—'}
          </Descriptions.Item>
          <Descriptions.Item label="账号归属">
            {data?.platformOperator ? (
              <Tag color="gold">平台运营（无企业归属）</Tag>
            ) : (
              <Tag color="blue">企业账号</Tag>
            )}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title="所属企业" size="small">
        {data?.platformOperator ? (
          <Typography.Text type="secondary">
            当前账号是平台运营账号，未绑定企业。
          </Typography.Text>
        ) : (
          <Descriptions column={2} size="small">
            <Descriptions.Item label="企业名称" span={2}>
              {data?.enterpriseName ?? '—'}
            </Descriptions.Item>
            <Descriptions.Item label="交易席位">
              {data?.traderCode ? (
                <Tag color="blue">{data.traderCode}</Tag>
              ) : (
                <Typography.Text type="secondary">尚未分配</Typography.Text>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="企业 ID">{data?.enterpriseId ?? '—'}</Descriptions.Item>
          </Descriptions>
        )}
      </Card>
    </div>
  )
}
