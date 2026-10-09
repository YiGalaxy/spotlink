async (page) => {
  const base = 'http://localhost:38080'
  const assert = (ok, message) => { if (!ok) throw new Error(message) }
  const batch = String(Date.now())
  const name = 'BUY匹配验收-' + batch
  const login = async username => {
    await page.goto(base + '/login')
    await page.getByLabel('用户名', { exact: true }).fill(username)
    await page.getByLabel('密码', { exact: true }).fill('Admin@123')
    await page.getByRole('button', { name: /登\s*录/, exact: true }).click()
    await page.waitForURL(base + '/')
  }
  const call = async (method, path, data) => page.evaluate(async ({ method, path, data }) => {
    const { state } = JSON.parse(localStorage.getItem('spotlink-auth'))
    const response = await (await fetch('/api' + path, { method, headers: { Authorization: `Bearer ${state.accessToken}`, 'Content-Type': 'application/json' }, body: data === undefined ? undefined : JSON.stringify(data) })).json()
    if (response.code !== 0) throw new Error(response.message)
    return response.data
  }, { method, path, data })
  await login('buyer01')
  await page.goto(base + '/trading?tab=mine')
  await page.getByRole('button', { name: 'plus 发布挂牌', exact: true }).click()
  let dialog = page.getByRole('dialog')
  await dialog.getByText('买方挂牌（我买货）', { exact: true }).click()
  await dialog.getByLabel('品类', { exact: true }).waitFor()
  await dialog.getByLabel('品类', { exact: true }).click()
  await page.getByTitle(/铝锭/).last().click()
  await dialog.getByLabel('商品名称', { exact: true }).fill(name)
  await dialog.getByLabel('指定品牌（可选）', { exact: true }).fill(batch)
  await dialog.getByLabel('铝含量 (%)', { exact: true }).fill('99.7')
  await dialog.getByLabel('数量', { exact: true }).fill('20.001')
  await dialog.getByLabel('单价（元）', { exact: true }).fill('68000.1234')
  await dialog.getByLabel('交收仓库', { exact: true }).click()
  await page.getByTitle('杭州金属材料交割仓', { exact: true }).click()
  await dialog.getByRole('button', { name: '确认发布' }).click()
  await dialog.waitFor({ state: 'hidden' })
  const listing = (await call('GET', '/listings/mine')).find(row => row.commodityName === name)
  assert(listing && listing.side === 'BUY' && listing.price === '68000.1234' && listing.unit === '吨' && listing.spec.al_content === 99.7, 'BUY 定价、单位或规格未持久化')
  await login('seller01')
  const fixtures = []
  for (const [warehouseId, purity] of [['2001', 99.7], ['2002', 99.7], ['2001', 99.8]]) {
    fixtures.push(await call('POST', '/inventory-notes', { categoryId: '1003', warehouseId, commodityName: `交付验收-${warehouseId}-${purity}-${batch}`, brand: batch, origin: '实际产地', quantity: '30.001', unit: '吨', spec: { al_content: purity, 质检批号: batch } }))
  }
  await page.goto(base + `/trading?listing=${listing.id}`)
  await page.getByRole('button', { name: /摘\s*牌/, exact: true }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('摘牌数量', { exact: true }).fill('10.001')
  await dialog.getByLabel('用于交付的源库存单', { exact: true }).click()
  await page.getByTitle(new RegExp(fixtures[0].commodityName)).waitFor()
  assert(await page.getByTitle(new RegExp(fixtures[1].commodityName)).count() === 0, '异仓库存出现在选择列表')
  assert(await page.getByTitle(new RegExp(fixtures[2].commodityName)).count() === 0, '不同规格库存出现在选择列表')
  await page.getByTitle(new RegExp(fixtures[0].commodityName)).click()
  await page.waitForFunction(() => [...document.querySelectorAll('.ant-select-dropdown')].every(node => getComputedStyle(node).display === 'none' || getComputedStyle(node).visibility === 'hidden'))
  for (const width of [1440, 1280, 1024, 768, 390]) {
    await page.setViewportSize({ width, height: 900 })
    try {
      await page.waitForFunction(() => document.documentElement.scrollWidth <= innerWidth + 1, null, { timeout: 5000 })
    } catch {
      await page.screenshot({ path: '/workspace/.local/browser/buy-overflow-failure.png', fullPage: true })
      const overflow = await page.evaluate(() => [...document.querySelectorAll('body *')].filter(node => node.getBoundingClientRect().right > innerWidth + 1 && getComputedStyle(node).display !== 'none').slice(0, 12).map(node => ({ tag: node.tagName, css: node.className, width: node.getBoundingClientRect().width, text: node.textContent?.slice(0, 100) })))
      throw new Error(`BUY 摘牌 ${width}px 布局溢出：${JSON.stringify(overflow)}`)
    }
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `BUY 摘牌 ${width}px 横向溢出`)
  }
  await page.screenshot({ path: '/workspace/.local/browser/buy-accept-mobile.png', fullPage: true })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.screenshot({ path: '/workspace/.local/browser/buy-accept-desktop.png', fullPage: true })
  const outgoing = page.waitForRequest(request => request.method() === 'POST' && request.url().endsWith(`/api/listings/${listing.id}/accept`))
  await dialog.getByRole('button', { name: '确认摘牌' }).click()
  const payload = (await outgoing).postDataJSON()
  assert(payload.inventoryNoteId === fixtures[0].id && payload.quantity === '10.001', '未提交明确源库存或数量精度丢失')
  await dialog.waitFor({ state: 'hidden' })
  const stock = (await call('GET', '/inventory-notes')).find(row => row.id === fixtures[0].id)
  assert(stock.totalQuantity === '20.000' || stock.totalQuantity === '20', '选定库存未准确扣减')
  const order = (await call('GET', '/orders')).find(row => row.listingId === listing.id)
  assert(order && order.commodityName === fixtures[0].commodityName && order.warehouseId === fixtures[0].warehouseId, '订单未记录实际交付商品')
  assert(typeof order.quantity === 'string' && typeof order.price === 'string' && typeof order.amount === 'string', '订单数量和金额未使用精确字符串')
  for (const fixture of fixtures.slice(1)) await call('DELETE', `/inventory-notes/${fixture.id}`)
  await login('buyer01')
  const received = (await call('GET', '/inventory-notes')).find(row => row.commodityName === fixtures[0].commodityName)
  assert(received && received.status === 7 && Number(received.availableQuantity) === 0 && Number(received.frozenQuantity) === 10.001, '买方成交库存没有真实冻结或未限制提前交易')
  await page.goto(base + '/inventory')
  await page.getByText(received.commodityName, { exact: true }).waitFor()
  assert(await page.getByText('待交收（受限）', { exact: true }).count() > 0, '买方库存状态未清楚显示')
  await page.screenshot({ path: '/workspace/.local/browser/transfer-restricted-stock.png', fullPage: true })
  await call('POST', `/listings/${listing.id}/close`)
  console.log('BUY 定价/schema/单位、明确源库存、实际订单属性、精确金额、买方受限库存及五宽度通过；隔离订单保留用于后续交收验收')
}
