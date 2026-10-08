async (page) => {
  const base = 'http://localhost:38080'
  const assert = (condition, text) => { if (!condition) throw new Error(text) }
  await page.goto(`${base}/login`)
  await page.getByLabel('用户名', { exact: true }).fill('seller01')
  await page.getByLabel('密码', { exact: true }).fill('Admin@123')
  await page.getByRole('button', { name: /登\s*录/, exact: true }).click()
  await page.waitForURL(`${base}/`)
  // 仅专用验收库；通过真实发布接口建立带库存冻结的挂牌。
  const fixtureCount = await page.evaluate(async () => {
    const { state } = JSON.parse(localStorage.getItem('spotlink-auth'))
    const headers = { Authorization: `Bearer ${state.accessToken}`, 'Content-Type': 'application/json' }
    const notes = (await (await fetch('/api/inventory-notes', { headers })).json()).data
    const existing = (await (await fetch('/api/listings/market', { headers })).json()).data
    const testNotes = notes.filter(n => n.noteNo.startsWith('MALL-QA-'))
    for (const note of testNotes) {
      if (existing.some(l => l.commodityName === note.commodityName)) continue
      const response = await fetch('/api/listings', { method: 'POST', headers, body: JSON.stringify({
        side: 'SELL', inventoryNoteId: note.id, categoryId: note.categoryId,
        commodityName: note.commodityName, spec: { 等级: '验收样品' }, brand: '隔离验收', origin: '测试环境',
        quantity: 30, unit: note.unit, price: 72360 + testNotes.indexOf(note) * 127, priceType: 'FIXED',
        warehouseId: note.warehouseId, validUntil: new Date(Date.now() + 86400000 * 7).toISOString(),
      }) })
      if ((await response.json()).code !== 0) throw new Error('真实发布验收挂牌失败')
    }
    return testNotes.length
  })
  assert(fixtureCount >= 2, '分类验收场景不足')
  await page.getByRole('button', { name: '王经理', exact: false }).hover()
  await page.getByText('退出登录', { exact: true }).click()
  await page.waitForURL(`${base}/`)
  await page.reload()
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.getByRole('heading', { name: '发现好货' }).waitFor()
  await page.locator('.product-card').first().waitFor()
  await page.locator('.category-gallery-grid img').last().scrollIntoViewIfNeeded()
  await page.waitForFunction(() => {
    const images = [...document.querySelectorAll('.category-gallery-grid img')]
    return images.length === 6 && images.every(img => img.complete && img.naturalWidth === 800)
  })
  const imageSources = await page.locator('.category-gallery-grid img').evaluateAll(images => images.map(img => new URL(img.src).pathname))
  assert(new Set(imageSources).size === 6, '六个品类没有对应独立商品图')
  await page.getByRole('button', { name: '浏览铝锭现货', exact: true }).click()
  await page.getByRole('heading', { name: /^铝锭/ }).waitFor()
  await page.locator('.product-card img').first().waitFor()
  assert((await page.locator('.product-card img').first().getAttribute('src')).includes('commodity-aluminum'), '挂牌商品图未按品类匹配')
  await page.getByRole('button', { name: '清除筛选' }).click()
  await page.getByRole('heading', { name: '发现好货' }).waitFor()
  await page.evaluate(() => scrollTo(0, 0))
  await page.reload()
  await page.locator('.product-card').first().waitFor()
  assert(await page.locator('.ant-layout-sider').count() === 0, '公开首页仍有后台侧栏')
  await page.keyboard.press('Tab')
  assert(await page.locator('.skip-link').evaluate(el => el === document.activeElement && el.getBoundingClientRect().top >= 0), '键盘跳过导航入口不可见')
  await page.keyboard.press('Enter')
  assert(await page.locator('main').evaluate(el => el === document.activeElement), '跳过导航没有聚焦内容')
  await page.screenshot({ path: '/workspace/.local/browser/mall-desktop.png', fullPage: true })
  await page.getByLabel('搜索商品', { exact: true }).fill('电解铜')
  await page.getByRole('button', { name: '搜索现货' }).click()
  await page.waitForURL('**/?q=*')
  await page.getByRole('heading', { name: /^“电解铜”的搜索结果/ }).waitFor()
  await page.locator('.product-card').first().waitFor()
  assert((await page.locator('.product-card h3').allTextContents()).every(n => n.includes('电解铜')), '搜索结果混入其他商品')
  await page.getByRole('button', { name: '清除筛选' }).click()
  await page.getByRole('heading', { name: '发现好货' }).waitFor()
  await page.locator('.category-chips').getByRole('button', { name: '铝锭', exact: true }).click()
  await page.getByRole('heading', { name: /^铝锭/ }).waitFor()
  await page.locator('.product-card').first().waitFor()
  assert((await page.locator('.product-card h3').allTextContents()).every(n => n.includes('铝锭')), '分类筛选无效')
  await page.goBack()
  await page.getByRole('heading', { name: '发现好货' }).waitFor()
  assert(await page.getByLabel('搜索商品', { exact: true }).inputValue() === '', '后退没有同步搜索框')
  await page.locator('.category-chips').getByRole('button', { name: '铝锭', exact: true }).click()
  await page.getByRole('button', { name: '采购需求', exact: true }).click()
  await page.getByText('当前没有符合条件的有效挂牌', { exact: true }).waitFor()
  await page.getByRole('button', { name: '查看全部现货', exact: true }).click()
  await page.locator('.product-card').first().waitFor()
  const selectedName = await page.locator('.product-card h3').first().innerText()
  await page.locator('.product-card').first().click()
  await page.getByRole('dialog').waitFor()
  await page.getByRole('link', { name: /前往大厅查看与摘牌/ }).click()
  await page.waitForURL('**/trading?listing=*')
  await page.getByText('已定位你从商城选择的挂牌').waitFor()
  await page.getByRole('cell').filter({ has: page.getByText(selectedName, { exact: true }) }).waitFor()
  await page.goto(`${base}/trading?tab=orders`)
  await page.getByRole('button', { name: '前往登录' }).click()
  await page.getByLabel('用户名', { exact: true }).fill('buyer01')
  await page.getByLabel('密码', { exact: true }).fill('Admin@123')
  await page.getByRole('button', { name: /登\s*录/, exact: true }).click()
  await page.waitForURL('**/trading?tab=orders')
  await page.getByRole('tab', { name: /我的订单/ }).waitFor()
  await page.goto(base)
  await page.locator('.product-card').first().waitFor()
  await page.getByRole('button', { name: /李采购/ }).focus()
  await page.keyboard.press('Enter')
  await page.getByText('退出登录', { exact: true }).waitFor()
  await page.keyboard.press('Escape')
  await page.getByText('退出登录', { exact: true }).waitFor({ state: 'hidden' })
  for (const width of [320, 768, 1024]) {
    await page.setViewportSize({ width, height: 844 })
    const sized = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth,
      elements: [...document.querySelectorAll('body *')].filter(el => el.getBoundingClientRect().right > innerWidth + 1 && getComputedStyle(el).position !== 'absolute').slice(0, 8).map(el => ({ tag: el.tagName, class: el.className, right: el.getBoundingClientRect().right })) }))
    if (sized.scroll > sized.width) await page.screenshot({ path: `/workspace/.local/browser/mall-overflow-${width}.png`, fullPage: true })
    assert(sized.scroll <= sized.width, `${width}px首页横向溢出：${JSON.stringify(sized)}`)
  }
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: '/workspace/.local/browser/mall-mobile.png', fullPage: true })
  const overflow = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth,
    elements: [...document.querySelectorAll('body *')].filter(el => el.getBoundingClientRect().right > innerWidth + 1 && getComputedStyle(el).position !== 'absolute').slice(0, 12).map(el => ({ tag: el.tagName, class: el.className, right: el.getBoundingClientRect().right })) }))
  assert(overflow.scroll <= overflow.width, `手机首页横向溢出：${JSON.stringify(overflow)}`)
  await page.goto(`${base}/login`)
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), '手机登录横向溢出')
  await page.screenshot({ path: '/workspace/.local/browser/mall-login.png', fullPage: true })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.waitForFunction(() => {
    const image = document.querySelector('.login-intro img')
    return image && image.complete && image.naturalWidth === 1200
  })
  await page.screenshot({ path: '/workspace/.local/browser/mall-login-desktop.png', fullPage: true })
  await page.route('**/images/commodity-copper.webp', route => route.fulfill({ status: 404, body: '' }))
  await page.goto(base)
  await page.locator('.category-gallery-grid button').first().locator('svg.commodity-art').waitFor()
  assert(await page.locator('.category-gallery-grid button').first().getByText('电解铜', { exact: true }).count() === 1, '图片故障影响品类导航')
  await page.unroute('**/images/commodity-copper.webp')
  await page.route('**/api/listings/market*', route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 503, message: '验收服务故障' }) }))
  await page.goto(base)
  await page.getByText('暂时无法加载挂牌', { exact: true }).waitFor()
  await page.unroute('**/api/listings/market*')
  await page.getByRole('button', { name: /重\s*试/, exact: true }).click()
  await page.locator('.product-card').first().waitFor()
  console.log('商城真实搜索/分类/方向/详情/订单登录返回、手机宽度、错误重试验证通过')
}
