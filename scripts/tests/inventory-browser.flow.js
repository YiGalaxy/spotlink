async (page) => {
  const base = 'http://localhost:38080'
  const assert = (condition, message) => { if (!condition) throw new Error(message) }
  const stamp = Date.now()
  await page.goto(`${base}/login`)
  await page.getByLabel('用户名', { exact: true }).fill('seller01')
  await page.getByLabel('密码', { exact: true }).fill('Admin@123')
  await page.getByRole('button', { name: /登\s*录/, exact: true }).click()
  await page.waitForURL(`${base}/`)
  await page.goto(`${base}/inventory`)
  await page.getByRole('heading', { name: '我的库存' }).waitFor()
  for (const [category, field, unit, quantity] of [
    ['铝锭', '铝含量 (%)', '吨', '10.001'], ['精铟', '铟含量 (%)', '千克', '2.003'],
  ]) {
    await page.getByRole('button', { name: /登记入库/ }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('品类', { exact: true }).click()
    await page.getByTitle(new RegExp(category)).last().click()
    await dialog.getByLabel('商品名称', { exact: true }).fill(`库存验收-${category}-${stamp}`)
    await dialog.getByLabel('交收仓库', { exact: true }).click()
    await page.getByTitle('WH-HZ-01 杭州金属材料交割仓', { exact: true }).click()
    await dialog.getByLabel('数量', { exact: true }).fill('0.0015')
    await dialog.getByLabel(field, { exact: true }).fill('99.7')
    await dialog.getByRole('button', { name: '确认登记' }).click()
    await dialog.getByText('最多 15 位整数和 3 位小数', { exact: true }).waitFor()
    await dialog.getByLabel('数量', { exact: true }).fill(quantity)
    assert(await dialog.getByPlaceholder('选择品类后显示', { exact: true }).inputValue() === unit, '品类默认单位错误')
    assert(await dialog.getByLabel('铜含量 (%)', { exact: true }).count() === 0, '非铜品类仍展示铜规格')
    await dialog.getByRole('button', { name: '确认登记' }).click()
    await dialog.waitFor({ state: 'hidden' })
    await page.getByRole('cell').getByText(`库存验收-${category}-${stamp}`, { exact: true }).waitFor()
  }
  const fixture = await page.evaluate(async stamp => {
    const { state } = JSON.parse(localStorage.getItem('spotlink-auth'))
    const headers = { Authorization: `Bearer ${state.accessToken}`, 'Content-Type': 'application/json' }
    const result = await (await fetch('/api/inventory-notes', { method: 'POST', headers,
      body: JSON.stringify({ categoryId: '1003', warehouseId: '2001',
        commodityName: `扩展规格验收-${stamp}`, quantity: '999999999999999.999', unit: '吨',
        spec: { al_content: 99.7, 质检批号: '中文保留键' }, remark: '原始备注' }) })).json()
    if (result.code !== 0) throw new Error(result.message)
    return { id: result.data.id, name: result.data.commodityName }
  }, stamp)
  await page.reload()
  await page.getByRole('button', { name: `修改${fixture.name}`, exact: true }).click()
  await page.getByRole('dialog').getByLabel('备注', { exact: true }).fill('新的备注')
  await page.getByRole('dialog').getByRole('button', { name: /保\s*存/, exact: true }).click()
  await page.getByRole('dialog').waitFor({ state: 'hidden' })
  const listingId = await page.evaluate(async fixture => {
    const { state } = JSON.parse(localStorage.getItem('spotlink-auth'))
    const headers = { Authorization: `Bearer ${state.accessToken}`, 'Content-Type': 'application/json' }
    const note = (await (await fetch(`/api/inventory-notes/${fixture.id}`, { headers })).json()).data
    if (note.spec['质检批号'] !== '中文保留键' || note.remark !== '新的备注') throw new Error('编辑丢失扩展规格或备注')
    if (note.totalQuantity !== '999999999999999.999') throw new Error('大数量接口精度丢失')
    const listing = await (await fetch('/api/listings', { method: 'POST', headers, body: JSON.stringify({
      side: 'SELL', inventoryNoteId: note.id, categoryId: note.categoryId, commodityName: note.commodityName,
      quantity: 1, price: 100, priceType: 'FIXED', unit: note.unit, warehouseId: note.warehouseId,
      spec: note.spec, validUntil: new Date(Date.now() + 86400000).toISOString(),
    }) })).json()
    if (listing.code !== 0) throw new Error(listing.message)
    return listing.data.id
  }, fixture)
  await page.reload()
  await page.getByRole('button', { name: `修改${fixture.name}`, exact: true }).click()
  assert(await page.getByRole('dialog').getByLabel('商品名称', { exact: true }).isDisabled(), '冻结后仍可改商品身份')
  await page.getByRole('dialog').getByRole('button', { name: /取\s*消/, exact: true }).click()
  const closed = await page.evaluate(async id => {
    const { state } = JSON.parse(localStorage.getItem('spotlink-auth'))
    return (await (await fetch(`/api/listings/${id}/close`, { method: 'POST',
      headers: { Authorization: `Bearer ${state.accessToken}` } })).json()).code
  }, listingId)
  assert(closed === 0, '真实撤牌未释放库存')
  await page.reload()
  await page.getByRole('button', { name: `修改${fixture.name}`, exact: true }).waitFor()
  const row = page.getByRole('row').filter({ has: page.getByText(fixture.name, { exact: true }) })
  await row.getByRole('button', { name: /注\s*销/, exact: true }).click()
  await page.getByRole('tooltip').getByRole('button', { name: /注\s*销/, exact: true }).click()
  await row.getByText('已注销', { exact: true }).waitFor()
  const summary = await page.locator('.ant-statistic-content').allTextContents()
  assert(summary.some(text => text.includes('吨') && text.includes('千克')), '汇总混合了不同单位')
  await page.screenshot({ path: '/workspace/.local/browser/inventory-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: '/workspace/.local/browser/inventory-mobile.png', fullPage: true })
  const sizing = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth,
    elements: [...document.querySelectorAll('body *')].filter(el => el.getBoundingClientRect().right > innerWidth + 1)
      .slice(0, 12).map(el => ({ tag: el.tagName, class: el.className, right: el.getBoundingClientRect().right })) }))
  assert(sizing.scroll <= sizing.width, `库存页手机溢出：${JSON.stringify(sizing)}`)
  await page.route('**/api/inventory-notes*', route => route.fulfill({ status: 503, body: '{"code":503,"message":"验收故障"}', contentType: 'application/json' }))
  await page.reload()
  await page.getByText('暂时无法加载库存或基础资料', { exact: true }).waitFor()
  await page.unroute('**/api/inventory-notes*')
  await page.getByRole('button', { name: /重\s*试/, exact: true }).click()
  await page.getByRole('button', { name: `修改${fixture.name}`, exact: true }).waitFor()
  await page.getByText('暂时无法加载库存或基础资料', { exact: true }).waitFor({ state: 'hidden' })
  return '库存登记、精度、非铜规格、单位分组、扩展键、冻结限制、注销和故障重试通过'
}
