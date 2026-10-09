async (page) => {
  const base = 'http://localhost:38080'
  const assert = (ok, why) => { if (!ok) throw new Error(why) }
  const name = '独立预留验收-' + Date.now()
  const login = async username => {
    await page.goto(base + '/login')
    await page.getByLabel('用户名', { exact: true }).fill(username)
    await page.getByLabel('密码', { exact: true }).fill('Admin@123')
    await page.getByRole('button', { name: /登\s*录/, exact: true }).click()
    await page.waitForURL(base + '/')
  }
  const call = async (method, path, data) => page.evaluate(async ({ method, path, data }) => {
    const { state } = JSON.parse(localStorage.getItem('spotlink-auth'))
    const result = await (await fetch('/api' + path, { method, headers: { Authorization: `Bearer ${state.accessToken}`, 'Content-Type': 'application/json' }, body: data === undefined ? undefined : JSON.stringify(data) })).json()
    if (result.code !== 0) throw new Error(result.message)
    return result.data
  }, { method, path, data })
  await login('seller01')
  const note = await call('POST', '/inventory-notes', { categoryId: '1003', warehouseId: '2001', commodityName: name, quantity: '100', unit: '吨', spec: { al_content: 99.7 } })
  const listing = await call('POST', '/listings', { side: 'SELL', inventoryNoteId: note.id, quantity: '20', price: '68000', priceType: 'FIXED', confirmMode: 'MANUAL', deliveryMethod: 'SELF_PICKUP', validUntil: new Date(Date.now() + 86400000).toISOString() })
  await login('buyer01')
  const first = await call('POST', `/listings/${listing.id}/accept`, { quantity: '8' })
  const second = await call('POST', `/listings/${listing.id}/accept`, { quantity: '6' })
  assert(first.status === 'PENDING_CONFIRM' && second.status === 'PENDING_CONFIRM', 'MANUAL 提前成交')
  await login('seller01')
  await page.goto(base + '/trading?tab=orders')
  await page.getByText(second.orderNo, { exact: true }).waitFor()
  await page.getByRole('row').filter({ hasText: second.orderNo }).getByRole('button', { name: '去处理' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: '确认成交', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  const after = (await call('GET', '/orders')).find(row => row.id === second.id)
  assert(after.status === 'CONFIRMED' && after.confirmDeadline === null, '独立订单确认失败')
  await login('buyer01')
  await call('POST', `/orders/${first.id}/cancel`, { reason: '撤回第一笔摘牌' })
  await login('seller01')
  const remaining = (await call('GET', '/listings/mine')).find(row => row.id === listing.id)
  assert(Number(remaining.remainingQuantity) === 14, '撤销第一笔改变了第二笔的预留')
  const stock = (await call('GET', '/inventory-notes')).find(row => row.id === note.id)
  assert(Number(stock.totalQuantity) === 94 && Number(stock.frozenQuantity) === 14, 'MANUAL 确认及撤销数量不守恒')
  await page.goto(base + '/trading?tab=orders')
  await page.getByText(second.orderNo, { exact: true }).waitFor()
  for (const width of [1440, 1280, 1024, 768, 390]) {
    await page.setViewportSize({ width, height: 900 })
    await page.waitForFunction(() => document.documentElement.scrollWidth <= innerWidth + 1)
  }
  await page.waitForFunction(() => [...document.querySelectorAll('.ant-spin-container')].every(node => getComputedStyle(node).opacity === '1'))
  await page.screenshot({ path: '/workspace/.local/browser/manual-orders-mobile.png', fullPage: true })
  await call('POST', `/listings/${listing.id}/close`)
  console.log('MANUAL 两笔独立预留、逆序确认、单笔撤销、真实数量和五宽度订单页通过')
}
