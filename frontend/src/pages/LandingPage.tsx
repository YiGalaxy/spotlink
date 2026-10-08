import { useEffect, useMemo, useState } from 'react'
import { Alert, Button, Empty, Modal, Skeleton } from 'antd'
import {
  ArrowRightOutlined,
  SearchOutlined,
  RobotOutlined,
  ShopOutlined,
  RightOutlined,
} from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { fetchPublicStats } from '@/api/public'
import { fetchQuotes } from '@/api/market'
import { fetchMarket } from '@/api/trading'
import { fetchCategoryTree } from '@/api/inventory'
import { identityKey, useAuthStore } from '@/store/auth'
import type { CategoryNode, ListingView } from '@/types/api'
import CommodityArtwork from '@/components/CommodityArtwork'

function leaves(nodes: CategoryNode[]): CategoryNode[] {
  return nodes.flatMap((node) =>
    node.children.length ? leaves(node.children) : [node],
  )
}
const format = (value: number) =>
  new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4 }).format(value)

export default function LandingPage() {
  const [params, setParams] = useSearchParams()
  const keyword = params.get('q') ?? ''
  const categoryId = params.get('category') ?? undefined
  const side = params.get('side') === 'BUY' ? 'BUY' : 'SELL'
  const [draft, setDraft] = useState(keyword)
  const [selected, setSelected] = useState<ListingView | null>(null)
  useEffect(() => setDraft(keyword), [keyword])
  const user = useAuthStore((state) => state.user)
  const stats = useQuery({
    queryKey: identityKey('public-stats'),
    queryFn: fetchPublicStats,
  })
  const categories = useQuery({
    queryKey: identityKey('category-tree'),
    queryFn: fetchCategoryTree,
  })
  const quotes = useQuery({
    queryKey: identityKey('market-quotes'),
    queryFn: () => fetchQuotes(180),
  })
  const market = useQuery({
    queryKey: identityKey('market', categoryId, side, keyword),
    queryFn: () => fetchMarket(categoryId, side, keyword || undefined),
  })
  const listingRows = useMemo(
    () =>
      [...(market.data ?? [])].sort((a, b) =>
        b.createdAt.localeCompare(a.createdAt),
      ),
    [market.data],
  )
  const categoryLeaves = leaves(categories.data ?? [])
  const activeCategory = categoryLeaves.find((c) => c.id === categoryId)
  const changeFilter = (key: string, value?: string) => {
    setParams((current) => {
      const next = new URLSearchParams(current)
      if (value) next.set(key, value)
      else next.delete(key)
      return next
    })
  }
  const clearFilters = () => {
    setDraft('')
    setParams({})
  }
  const search = (value: string) => {
    setDraft(value)
    changeFilter('q', value.trim())
  }

  return (
    <div className="marketplace site-width">
      <section className="search-area" aria-label="搜索现货">
        <div className="search-caption">
          <strong>找好货，就上现货通</strong>
          <span>真实挂牌 · 直接采购</span>
        </div>
        <div className="search-wrap">
          <form
            className="market-search"
            onSubmit={(event) => {
              event.preventDefault()
              search(draft)
            }}
          >
            <SearchOutlined aria-hidden="true" />
            <input
              aria-label="搜索商品"
              placeholder="搜索商品名称，例如：电解铜、铝锭、碳酸锂"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
            />
            <button type="submit">搜索现货</button>
          </form>
          <div className="search-suggestions">
            <span>按品种找货</span>
            {categoryLeaves.slice(0, 5).map((c) => (
              <button
                key={c.id}
                onClick={() => {
                  changeFilter('category', c.id)
                  document.getElementById('goods')?.scrollIntoView()
                }}
              >
                {c.name}
              </button>
            ))}
          </div>
        </div>
        <Link className="publish-entry" to="/trading?tab=mine">
          <ShopOutlined />
          <span>
            我是供应商<small>发布我的挂牌 →</small>
          </span>
        </Link>
      </section>
      <section className="mall-showcase" aria-label="采购入口">
        <aside className="category-panel">
          <h2>
            <span className="category-lines" aria-hidden="true">
              ☰
            </span>{' '}
            商品分类
          </h2>
          {categories.isPending ? (
            <Skeleton active paragraph={{ rows: 5 }} />
          ) : categories.isError ? (
            <div className="panel-error">
              分类加载失败{' '}
              <button onClick={() => void categories.refetch()}>重试</button>
            </div>
          ) : (
            <div className="category-rows">
              {(categories.data ?? []).map((root) => (
                <div key={root.id} className="category-row">
                  <span className="category-marker" aria-hidden="true">
                    {root.name.slice(0, 1)}
                  </span>
                  <div>
                    <strong>{root.name}</strong>
                    <div>
                      {leaves([root])
                        .slice(0, 3)
                        .map((c) => (
                          <button
                            key={c.id}
                            className={categoryId === c.id ? 'selected' : ''}
                            onClick={() => changeFilter('category', c.id)}
                          >
                            {c.name}
                          </button>
                        ))}
                    </div>
                  </div>
                  <RightOutlined />
                </div>
              ))}
              {categories.data?.length === 0 && <p>暂无商品分类</p>}
            </div>
          )}
          <Link className="category-all" to="/trading">
            查看全部挂牌 <ArrowRightOutlined />
          </Link>
        </aside>
        <div className="showcase-center">
          <div className="sourcing-banner">
            <div className="banner-copy">
              <span className="eyebrow">SPOTLINK / 现货采购</span>
              <h1>
                大宗好货
                <br />
                直接找到。
              </h1>
              <p>
                从一笔真实挂牌开始，
                <br />
                连接你的下一位生意伙伴。
              </p>
              <a href="#goods" className="banner-button">
                逛逛现货 <ArrowRightOutlined />
              </a>
            </div>
            <div className="banner-visual">
              <CommodityArtwork name="电解铜" hero />
              <span className="material-note">金属 · 品类示意</span>
              <span className="visual-caption">
                GOOD MATERIALS.
                <br />
                BETTER CONNECTIONS.
              </span>
            </div>
          </div>
          <div className="service-strip">
            <span>
              <b>01</b> 看现货 <small>价格与可购量公开</small>
            </span>
            <span>
              <b>02</b> 问顾问 <small>了解规则与交易流程</small>
            </span>
            <span>
              <b>03</b> 做交易 <small>挂牌到交收全程留痕</small>
            </span>
          </div>
        </div>
        <aside className="buyer-panel">
          <div className="buyer-greeting">
            <span className="greeting-avatar">
              {user?.realName?.slice(0, 1) ?? 'Hi'}
            </span>
            <h2>
              {user
                ? `你好，${user.realName || user.username}`
                : '欢迎来到现货通'}
            </h2>
            <p>{user?.enterpriseName || '逛市场、找现货，从这里开始'}</p>
          </div>
          <Link className="member-button" to={user ? '/dashboard' : '/login'}>
            {user ? '进入我的工作台' : '登录企业账号'} <ArrowRightOutlined />
          </Link>
          <div className="buyer-shortcuts">
            <Link to="/trading?tab=orders">我的订单</Link>
            <Link to="/inventory">库存管理</Link>
            <Link to="/enterprise">企业信息</Link>
          </div>
          <div className="advisor-promo">
            <RobotOutlined />
            <strong>采购有疑问？问 AI 顾问</strong>
            <p>
              找商品、查库存、了解交易规则，
              <br />
              用一句话开始。
            </p>
            <Link to="/advisor">
              开始咨询 <ArrowRightOutlined />
            </Link>
          </div>
        </aside>
      </section>
      <section className="quote-strip" aria-label="平台成交行情">
        <div className="quote-label">
          <strong>现货行情</strong>
          <Link to="/market">查看走势 →</Link>
        </div>
        {quotes.isPending ? (
          <Skeleton active paragraph={false} />
        ) : quotes.isError ? (
          <span className="quote-empty">
            行情加载失败{' '}
            <button onClick={() => void quotes.refetch()}>重试</button>
          </span>
        ) : (
          (quotes.data ?? [])
            .filter((q) => q.latestPrice !== null)
            .slice(0, 4)
            .map((q) => (
              <Link to="/market" className="quote-item" key={q.categoryId}>
                <span>{q.categoryName}</span>
                <b>
                  {format(q.latestPrice!)} <small>元/{q.unit}</small>
                </b>
                <em
                  className={
                    (q.changePercent ?? 0) < 0 ? 'price-down' : 'price-up'
                  }
                >
                  {q.changePercent == null
                    ? '暂无对比'
                    : `${q.changePercent >= 0 ? '+' : ''}${q.changePercent.toFixed(2)}%`}
                </em>
              </Link>
            ))
        )}
        {!quotes.isPending &&
          !quotes.isError &&
          !(quotes.data ?? []).some((q) => q.latestPrice !== null) && (
            <span className="quote-empty">
              暂无平台成交行情，挂牌报价可在下方查看。
            </span>
          )}
      </section>
      <section id="goods" className="goods-section" aria-label="现货商品">
        <div className="goods-heading">
          <div>
            <span className="eyebrow">现货在这里</span>
            <h2>
              {keyword
                ? `“${keyword}”的搜索结果`
                : activeCategory
                  ? activeCategory.name
                  : '发现好货'}
              <small>真实挂牌，按最新发布排序</small>
            </h2>
          </div>
          <Link to="/trading">
            全部挂牌 <ArrowRightOutlined />
          </Link>
        </div>
        <div className="goods-toolbar">
          <div role="group" aria-label="挂牌方向">
            <button
              className={side === 'SELL' ? 'active' : ''}
              onClick={() => changeFilter('side', 'SELL')}
            >
              供应现货
            </button>
            <button
              className={side === 'BUY' ? 'active' : ''}
              onClick={() => changeFilter('side', 'BUY')}
            >
              采购需求
            </button>
          </div>
          <div className="category-chips">
            <button
              className={!categoryId ? 'active' : ''}
              onClick={() => changeFilter('category')}
            >
              全部品种
            </button>
            {categoryLeaves.map((c) => (
              <button
                key={c.id}
                className={categoryId === c.id ? 'active' : ''}
                onClick={() => changeFilter('category', c.id)}
              >
                {c.name}
              </button>
            ))}
          </div>
        </div>
        {(keyword || categoryId) && (
          <div className="filter-summary">
            {activeCategory?.name} {keyword && `关键词：${keyword}`}{' '}
            <button onClick={clearFilters}>清除筛选</button>
          </div>
        )}
        {market.isPending ? (
          <div className="goods-grid">
            {[0, 1, 2, 3, 4].map((n) => (
              <div className="goods-skeleton" key={n}>
                <Skeleton.Image active />
                <Skeleton active paragraph={{ rows: 2 }} />
              </div>
            ))}
          </div>
        ) : market.isError ? (
          <Alert
            type="error"
            showIcon
            message="暂时无法加载挂牌"
            description="请检查服务连接后重试。"
            action={<Button onClick={() => void market.refetch()}>重试</Button>}
          />
        ) : listingRows.length === 0 ? (
          <div className="goods-empty">
            <Empty description="当前没有符合条件的有效挂牌" />
            <Button onClick={clearFilters}>查看全部现货</Button>
            <Link to="/trading">前往挂牌大厅</Link>
          </div>
        ) : (
          <div className="goods-grid">
            {listingRows.slice(0, 20).map((row) => (
              <button
                key={row.id}
                className="product-card"
                onClick={() => setSelected(row)}
                aria-label={`查看${row.commodityName}挂牌详情`}
              >
                <div className="product-visual">
                  <CommodityArtwork
                    name={`${row.categoryName} ${row.commodityName}`}
                  />
                  <span className="listing-badge">
                    {row.side === 'SELL' ? '现货供应' : '采购需求'}
                  </span>
                  <span className="art-label">品类示意</span>
                </div>
                <div className="product-body">
                  <span className="product-category">
                    {row.categoryName} · {row.origin || '产地未注明'}
                  </span>
                  <h3>{row.commodityName}</h3>
                  <p className="product-spec">
                    {[row.brand, ...Object.values(row.spec)]
                      .filter(Boolean)
                      .map(String)
                      .join(' / ') || '规格详见挂牌'}
                  </p>
                  <div className="product-price">
                    {row.priceType === 'NEGOTIABLE' ? (
                      <strong>价格面议</strong>
                    ) : (
                      <>
                        <span>¥</span>
                        <strong>
                          {row.price == null ? '—' : format(row.price)}
                        </strong>
                        <small>/{row.unit}</small>
                      </>
                    )}
                  </div>
                  <div className="product-meta">
                    <span>
                      {row.side === 'SELL' ? '可购' : '需求'}{' '}
                      {format(row.remainingQuantity)} {row.unit}
                    </span>
                    <span>{row.deliveryMethodText}</span>
                  </div>
                  <div className="product-supplier">
                    <ShopOutlined />
                    <span>{row.enterpriseName}</span>
                    <ArrowRightOutlined />
                  </div>
                </div>
              </button>
            ))}
          </div>
        )}
        {listingRows.length > 20 && (
          <Link className="more-goods" to="/trading">
            去挂牌大厅查看更多商品 →
          </Link>
        )}
      </section>
      <div className="platform-proof">
        <span>连接真实现货市场</span>
        {stats.isSuccess && (
          <>
            <strong>
              {format(stats.data.enterpriseCount)}
              <small>家入驻企业</small>
            </strong>
            <strong>
              {format(stats.data.openListingCount)}
              <small>笔在挂挂牌</small>
            </strong>
            <strong>
              {format(stats.data.tradeCount)}
              <small>笔累计成交</small>
            </strong>
          </>
        )}
        <Link to="/market">了解平台行情 →</Link>
      </div>
      <Modal
        title="挂牌详情"
        open={Boolean(selected)}
        onCancel={() => setSelected(null)}
        footer={null}
        width={620}
      >
        {selected && (
          <div className="listing-detail">
            <CommodityArtwork
              name={`${selected.categoryName} ${selected.commodityName}`}
            />
            <small>品类示意，非实物照片</small>
            <h2>{selected.commodityName}</h2>
            <p className="detail-price">
              {selected.priceText}{' '}
              <span>
                {selected.priceType === 'FIXED' ? `元/${selected.unit}` : ''}
              </span>
            </p>
            <dl>
              {[
                ['挂牌号', selected.listingNo],
                ['挂牌企业', selected.enterpriseName],
                ['品类', selected.categoryName],
                [
                  '品牌 / 产地',
                  [selected.brand, selected.origin]
                    .filter(Boolean)
                    .join(' / ') || '未注明',
                ],
                [
                  '规格',
                  Object.entries(selected.spec)
                    .map(([k, v]) => `${k}：${String(v)}`)
                    .join(' / ') || '未注明',
                ],
                [
                  '剩余数量',
                  `${format(selected.remainingQuantity)} ${selected.unit}`,
                ],
                ['仓库', selected.warehouseName || '未指定'],
                ['交收方式', selected.deliveryMethodText],
                ['成交方式', selected.confirmModeText],
                ['有效期', selected.validUntil.replace('T', ' ')],
              ].map(([label, value]) => (
                <div key={label}>
                  <dt>{label}</dt>
                  <dd>{value}</dd>
                </div>
              ))}
            </dl>
            <Link
              className="member-button"
              to={`/trading?listing=${encodeURIComponent(selected.id)}&side=${selected.side}`}
            >
              前往大厅查看与摘牌 <ArrowRightOutlined />
            </Link>
          </div>
        )}
      </Modal>
    </div>
  )
}
