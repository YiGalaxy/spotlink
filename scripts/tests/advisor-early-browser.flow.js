async (page) => {
  const engine = '__SPOTLINK_BROWSER_ENGINE__'
  const assert = (value, message) => { if (!value) throw new Error(message) }
  await page.goto('http://localhost:58080/login')
  await page.getByLabel('用户名', { exact: true }).fill('min_seller01')
  await page.getByLabel('密码', { exact: true }).fill('Admin@123')
  await page.getByRole('button', { name: /登\s*录/, exact: true }).click()
  await page.waitForURL('http://localhost:58080/')
  await page.goto('http://localhost:58080/advisor')
  await page.getByText('已启用', { exact: true }).waitFor()
  if (engine === 'langchain') {
    await page.getByRole('combobox', { name: '顾问引擎' }).press('ArrowDown')
    await page.getByTitle('LangChain', { exact: true }).click()
    await page.getByText('已启用', { exact: true }).waitFor()
  }
  const ask = async question => {
    await page.getByLabel('向交易顾问提问').fill(question)
    await page.getByRole('button', { name: /发\s*送/, exact: true }).click()
  }
  await ask('我有哪些示例电解铜库存？')
  await page.locator('.markdown-body').last().getByText(/SL-MIN-IN-00001/).waitFor()
  assert((await page.locator('.markdown-body').allTextContents()).every(text => !text.includes('SL-MIN-IN-00003')), '页面泄漏买方库存')
  await page.getByText('查看数据依据（1 项查询）', { exact: true }).click()
  await page.locator('.tool-output').getByText(/SL-MIN-IN-00002/).waitFor()
  await page.getByText('查看数据依据（1 项查询）', { exact: true }).click()
  await ask('电子库存单可以质押吗？')
  await page.locator('.markdown-body').last().getByText(/不可质押/).waitFor()
  assert((await page.locator('.advisor-answer-meta').last().textContent()).includes(engine === 'langchain' ? 'LangChain' : 'Spring AI'), '页面显示了错误引擎')
  assert((await page.locator('.markdown-body').last().textContent()).includes('SL-MIN-KNOW-INVENTORY'), '回复缺少真实原文来源')
  await page.reload()
  await page.locator('.conv-item').first().click()
  await page.locator('.markdown-body').last().getByText(/不可质押/).waitFor()
  assert(await page.locator('.chat-bubble.user').count() === 2, '刷新后问答没有完整持久化')
  await page.screenshot({ path: '/workspace/.local/browser/advisor-early-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), '早期顾问手机页面横向溢出')
  await page.screenshot({ path: '/workspace/.local/browser/advisor-early-mobile.png', fullPage: true })
  await page.getByRole('button', { name: '会话记录', exact: true }).click()
  await page.locator('.conv-sidebar.is-open').waitFor()
  await page.getByLabel('关闭会话列表').click({ position: { x: 375, y: 400 } })
  return '导入账号与库存、真实查询依据、中文原文来源、刷新持久化和手机页面通过；模型为固定离线替身。'
}
