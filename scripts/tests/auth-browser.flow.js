async (page) => {
  const assert = (condition, message) => { if (!condition) throw new Error(message) }
  const login = async (username) => {
    await page.goto('http://localhost:38080/login')
    await page.getByLabel('用户名', { exact: true }).fill(username)
    await page.getByLabel('密码', { exact: true }).fill('Admin@123')
    await page.getByRole('button', { name: /登\s*录/, exact: true }).click()
    await page.waitForURL('**/dashboard')
  }
  const profile = async () => {
    await page.goto('http://localhost:38080/enterprise')
    await page.getByText('所属企业', { exact: true }).waitFor()
  }
  await login('seller01')
  await profile()
  await page.getByText('华东金属材料有限公司', { exact: true }).waitFor()
  await page.goto('http://localhost:38080/inventory')
  await page.getByText('AUTH-E2E-SELLER', { exact: true }).waitFor()
  await page.getByText('王经理', { exact: true }).hover()
  await page.getByText('退出登录', { exact: true }).click()
  await page.waitForURL('http://localhost:38080/')
  await login('buyer01')
  await profile()
  await page.getByText('浙江建工物资有限公司', { exact: true }).waitFor()
  assert(await page.getByText('华东金属材料有限公司', { exact: true }).count() === 0, '换账号后泄露卖方企业资料')
  await page.goto('http://localhost:38080/inventory')
  await page.getByText('AUTH-E2E-BUYER', { exact: true }).waitFor()
  assert(await page.getByText('AUTH-E2E-SELLER', { exact: true }).count() === 0, '买方看到卖方库存')
  // 使用当前浏览器身份验证后端用途边界，仅返回状态，不打印任何令牌。
  const statuses = await page.evaluate(async () => {
    const { state } = JSON.parse(localStorage.getItem('spotlink-auth'))
    const access = await fetch('/api/auth/me', { headers: { Authorization: `Bearer ${state.accessToken}` } })
    const refresh = await fetch('/api/auth/me', { headers: { Authorization: `Bearer ${state.refreshToken}` } })
    const inventory = await fetch('/api/inventory-notes/8700000000000000701', { headers: { Authorization: `Bearer ${state.accessToken}` } })
    return { access: access.status, refresh: refresh.status, privateCode: (await inventory.json()).code }
  })
  assert(statuses.access === 200 && statuses.refresh === 401 && statuses.privateCode !== 0, '令牌或企业对象边界失效')
  await page.screenshot({ path: '/workspace/.local/browser/auth-switch.png', fullPage: true })
  console.log('同浏览器卖方→退出→买方：企业资料、库存缓存、refresh 拒绝、对象越权验证通过')
}
