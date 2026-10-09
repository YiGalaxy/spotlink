import { useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Descriptions, Empty, Form, Input, Modal, Result, Select, Space, Spin, Table, Tag, Typography, message } from 'antd'
import { identityKey, useAuthStore } from '@/store/auth'
import { adminEntries, can } from '@/utils/permissions'
import { assignUserRoles, changeUserStatus, fetchAdminAudit, fetchAdminOrders, fetchAdminOverview, fetchEnterprises, fetchRoles, fetchUsers, reviewEnterprise, type AdminEnterprise, type AdminUser } from '@/api/admin'
import type { EntityId } from '@/types/api'
import dayjs from 'dayjs'

const actionNames: Record<string, string> = { approve: '通过审核', reject: '驳回申请', freeze: '冻结企业', unfreeze: '解冻企业', 'change-status': '修改账号状态', 'assign-role': '分配角色' }
const moduleNames: Record<string, string> = { enterprise: '企业', user: '账号' }

type Operation = { kind: 'enterprise'; row: AdminEnterprise; action: string; title: string; reasonRequired: boolean }
  | { kind: 'status'; row: AdminUser; status: number; title: string; reasonRequired: boolean }
  | { kind: 'roles'; row: AdminUser; title: string; reasonRequired: false }

export default function AdminPage() {
  const user = useAuthStore(s => s.user)
  const { pathname } = useLocation()
  const section = pathname.split('/')[2] || 'overview'
  const entry = adminEntries.find(item => item.path === pathname)
  const allowed = Boolean(entry && can(user, entry.permission))
  const cache = useQueryClient()
  const [keyword, setKeyword] = useState('')
  const [status, setStatus] = useState<number>()
  const [operation, setOperation] = useState<Operation | null>(null)
  const [busy, setBusy] = useState(false)
  const [form] = Form.useForm<{ reason: string; roleIds: EntityId[] }>()
  const enterprises = useQuery({ queryKey: identityKey('admin-enterprises', status, keyword), queryFn: () => fetchEnterprises(status, keyword), enabled: allowed && section === 'enterprises' })
  const users = useQuery({ queryKey: identityKey('admin-users', status, keyword), queryFn: () => fetchUsers(keyword, status), enabled: allowed && section === 'users' })
  const roles = useQuery({ queryKey: identityKey('admin-roles'), queryFn: fetchRoles, enabled: allowed && section === 'users' })
  const orders = useQuery({ queryKey: identityKey('admin-orders', keyword), queryFn: () => fetchAdminOrders(keyword), enabled: allowed && section === 'orders' })
  const audit = useQuery({ queryKey: identityKey('admin-audit', keyword), queryFn: () => fetchAdminAudit(keyword), enabled: allowed && section === 'audit' })
  const overview = useQuery({ queryKey: identityKey('admin-overview'), queryFn: fetchAdminOverview, enabled: allowed && section === 'overview' })
  const query = section === 'enterprises' ? enterprises : section === 'users' ? users : section === 'orders' ? orders : section === 'audit' ? audit : overview
  const open = (value: Operation) => {
    form.resetFields()
    if (value.kind === 'roles') form.setFieldValue('roleIds', roles.data?.filter(role => value.row.roles.includes(role.code)).map(role => role.id) ?? [])
    setOperation(value)
  }
  const submit = async () => {
    if (!operation || busy) return
    let values: { reason: string; roleIds: EntityId[] }
    try { values = await form.validateFields() } catch { return }
    setBusy(true)
    try {
      if (operation.kind === 'enterprise') await reviewEnterprise(operation.row.id, operation.action, values.reason)
      else if (operation.kind === 'status') await changeUserStatus(operation.row.id, operation.status, values.reason)
      else await assignUserRoles(operation.row.id, values.roleIds ?? [])
      setOperation(null)
      void message.success('已保存，后续请求使用新的状态和权限')
      await Promise.all(['admin-enterprises', 'admin-users', 'admin-audit', 'admin-overview', 'current-user'].map(key => cache.invalidateQueries({ queryKey: identityKey(key) })))
    } catch { /* 客户端统一展示失败，保留表单供重试 */ }
    finally { setBusy(false) }
  }
  if (!allowed) return <Result status="403" title="无权访问此管理页面" extra={<Link to="/">返回商城</Link>} />
  return <div className="business-page admin-page">
    <Typography.Text type="secondary">平台运营 / {entry?.label}</Typography.Text>
    <Typography.Title level={2}>{entry?.label}</Typography.Title>
    <nav className="business-nav" aria-label="运营导航">
      {adminEntries.filter(item => can(user, item.permission)).map(item => <Link key={item.path} to={item.path} aria-current={pathname === item.path ? 'page' : undefined}>{item.label}</Link>)}
    </nav>
    <Card>
      {section !== 'overview' && <p className="mobile-table-hint">表格可左右滑动，查看状态和操作。</p>}
      {section !== 'overview' && <Space className="business-toolbar" wrap>
        <Input.Search aria-label="后台关键词" placeholder={section === 'audit' ? '操作人账号' : section === 'orders' ? '订单编号' : '名称或账号关键词'} allowClear onSearch={setKeyword} style={{ width: 260 }} />
        {['enterprises', 'users'].includes(section) && <Select aria-label="状态筛选" placeholder="全部状态" allowClear style={{ width: 160 }} value={status} onChange={setStatus}
          options={(section === 'enterprises' ? ['待审核', '已通过', '已驳回', '已冻结', '已注销'] : ['已禁用', '正常', '已锁定']).map((label, value) => ({ value, label }))} />}
        <Button onClick={() => void query.refetch()}>刷新</Button>
      </Space>}
      {query.isError ? <Alert type="error" showIcon message="读取失败，请重试" action={<Button onClick={() => void query.refetch()}>重新加载</Button>} /> : <>
        {section === 'overview' && (overview.isLoading ? <Spin /> : overview.data && <Descriptions column={{ xs: 1, sm: 2, lg: 3 }} bordered items={[
          { key: 'enterprise', label: '企业总数', children: overview.data.enterpriseCount },
          { key: 'pending', label: '待审核企业', children: overview.data.pendingEnterpriseCount },
          { key: 'frozen', label: '冻结企业', children: overview.data.frozenEnterpriseCount },
          { key: 'users', label: '账号总数', children: overview.data.userCount },
          { key: 'orders', label: '进行中订单', children: overview.data.activeOrderCount },
          { key: 'amount', label: '累计成交额', children: overview.data.tradedAmountText },
        ]} />)}
        {section === 'enterprises' && <Table<AdminEnterprise> rowKey="id" loading={enterprises.isLoading} dataSource={enterprises.data} scroll={{ x: 1000 }} locale={{ emptyText: <Empty description="没有符合条件的企业" /> }} columns={[
          { title: '企业与资质', key: 'name', render: (_, row) => <><Typography.Text strong>{row.name}</Typography.Text><div>{row.enterpriseCode} · {row.unifiedSocialCreditCode}</div><div>法人：{row.legalPerson || '未提供'}</div>{row.rejectReason && <div>驳回原因：{row.rejectReason}</div>}</> },
          { title: '联系人', key: 'contact', render: (_, row) => <>{row.contactName || '—'}<div>{row.contactPhone || '—'}</div></> },
          { title: '所在地 / 席位', key: 'location', render: (_, row) => <>{[row.province, row.city, row.address].filter(Boolean).join(' ') || '未提供'}<div>{row.traderCode || '未分配席位'}</div></> },
          { title: '状态', dataIndex: 'statusText', render: text => <Tag>{text}</Tag> },
          { title: '操作', key: 'actions', render: (_, row) => <Space wrap>
            {[0, 2].includes(row.status) && can(user, 'admin:enterprise:review') && <Button onClick={() => open({ kind: 'enterprise', row, action: 'approve', title: '通过企业审核', reasonRequired: false })}>通过审核</Button>}
            {row.status === 0 && can(user, 'admin:enterprise:review') && <Button danger onClick={() => open({ kind: 'enterprise', row, action: 'reject', title: '驳回企业申请', reasonRequired: true })}>驳回</Button>}
            {row.status === 1 && can(user, 'admin:enterprise:freeze') && <Button danger onClick={() => open({ kind: 'enterprise', row, action: 'freeze', title: '冻结企业', reasonRequired: true })}>冻结</Button>}
            {row.status === 3 && can(user, 'admin:enterprise:freeze') && <Button onClick={() => open({ kind: 'enterprise', row, action: 'unfreeze', title: '解冻企业', reasonRequired: false })}>解冻</Button>}
          </Space> },
        ]} />}
        {section === 'users' && <>
          {roles.isError && <Alert type="error" message="角色列表加载失败" action={<Button onClick={() => void roles.refetch()}>重试</Button>} />}
          <Table<AdminUser> rowKey="id" loading={users.isLoading} dataSource={users.data} scroll={{ x: 950 }} columns={[
            { title: '账号', key: 'username', render: (_, row) => <>{row.username}<div>{row.realName || '未填写姓名'}</div></> },
            { title: '归属', key: 'enterprise', render: (_, row) => row.enterpriseName || row.userTypeText },
            { title: '状态', dataIndex: 'statusText' },
            { title: '平台角色', key: 'roles', render: (_, row) => row.roles.map(code => <Tag key={code}>{roles.data?.find(role => role.code === code)?.name || code}</Tag>) },
            { title: '操作', key: 'actions', render: (_, row) => <Space wrap>
              {can(user, 'admin:user:status') && row.id !== user?.userId && <Button danger={row.status === 1} onClick={() => open({ kind: 'status', row, status: row.status === 1 ? 0 : 1, title: row.status === 1 ? '禁用账号' : '启用账号', reasonRequired: true })}>{row.status === 1 ? '禁用' : '启用'}</Button>}
              {can(user, 'admin:user:role') && !row.enterpriseId && <Button disabled={!roles.data} onClick={() => open({ kind: 'roles', row, title: '分配平台角色', reasonRequired: false })}>分配角色</Button>}
            </Space> },
          ]} />
        </>}
        {section === 'orders' && <Table rowKey="id" loading={orders.isLoading} dataSource={orders.data} scroll={{ x: 900 }} columns={[
          { title: '订单编号', dataIndex: 'orderNo' }, { title: '货物', dataIndex: 'commodityName' }, { title: '买方', dataIndex: 'buyerName' }, { title: '卖方', dataIndex: 'sellerName' }, { title: '金额', dataIndex: 'amountText' }, { title: '状态', dataIndex: 'statusText' },
        ]} />}
        {section === 'audit' && <><Typography.Paragraph type="secondary">显示最近 100 条记录，可按操作人过滤。展开查看状态变更依据。</Typography.Paragraph><Table rowKey="id" loading={audit.isLoading} dataSource={audit.data} scroll={{ x: 850 }} columns={[
          { title: '时间', dataIndex: 'createdAt', render: value => dayjs(value).format('YYYY-MM-DD HH:mm:ss') }, { title: '操作人', dataIndex: 'username' }, { title: '模块', dataIndex: 'module', render: value => moduleNames[value] || value }, { title: '操作', dataIndex: 'action', render: value => actionNames[value] || value }, { title: '目标', dataIndex: 'targetId' }, { title: '结果', dataIndex: 'success', render: value => value ? '成功' : '失败' },
        ]} expandable={{ expandedRowRender: row => <div className="audit-detail"><p>变更前：{row.beforeData || '—'}</p><p>变更后：{row.afterData || '—'}</p></div> }} /></>}
      </>}
    </Card>
    <Modal title={operation?.title} open={Boolean(operation)} confirmLoading={busy} onOk={() => void submit()} onCancel={() => { if (!busy) setOperation(null) }} maskClosable={!busy} closable={!busy} cancelButtonProps={{ disabled: busy }} okText="确认保存" cancelText="取消" destroyOnClose>
      <Typography.Paragraph>操作对象：{operation?.kind === 'enterprise' ? operation.row.name : operation?.row.username}</Typography.Paragraph>
      <Alert type="warning" showIcon message="状态和权限变更会影响已登录账号的后续请求。" style={{ marginBottom: 20 }} />
      <Form form={form} layout="vertical" disabled={busy}>
        {operation?.reasonRequired && <Form.Item name="reason" label="操作原因" rules={[{ required: true, whitespace: true, message: '请填写操作原因' }, { max: 500 }]}><Input.TextArea rows={3} maxLength={500} showCount /></Form.Item>}
        {operation?.kind === 'roles' && <><Form.Item name="roleIds" label="平台角色"><Select mode="multiple" options={roles.data?.map(role => ({ value: role.id, label: role.name }))} /></Form.Item><Typography.Paragraph type="secondary">清空即撤销全部平台角色；系统会阻止移除最后一个可分配角色的管理员。</Typography.Paragraph>{roles.data?.map(role => <details key={role.id}><summary>{role.name} · 权限说明</summary><p>{role.description}</p><p className="audit-detail">{role.permissionCodes.join('、')}</p></details>)}</>}
      </Form>
    </Modal>
  </div>
}
