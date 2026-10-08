import { useId, useState } from 'react'

const products = [
  { pattern: /电解铜|铜/, file: 'commodity-copper', label: '电解铜板' },
  { pattern: /铝/, file: 'commodity-aluminum', label: '铝锭' },
  { pattern: /锌/, file: 'commodity-zinc', label: '锌锭' },
  { pattern: /碳酸锂/, file: 'commodity-lithium-carbonate', label: '碳酸锂粉末' },
  { pattern: /氢氧化锂/, file: 'commodity-lithium-hydroxide', label: '氢氧化锂颗粒' },
  { pattern: /精铟|铟/, file: 'commodity-indium', label: '精铟金属条' },
]

/** 品类示意插画，不作为挂牌货物的实物照片。 */
export default function CommodityArtwork({
  name,
  hero = false,
  purpose = 'product',
}: {
  name: string
  hero?: boolean
  purpose?: 'product' | 'login'
}) {
  const [failed, setFailed] = useState<string | null>(null)
  const product = products.find(item => item.pattern.test(name))
  const file = purpose === 'login' ? 'login-warehouse' : hero ? 'home-commodity-hero' : product?.file
  const src = file ? `/images/${file}.webp` : undefined
  if (src && failed !== src) return (
    <img src={src} width={hero || purpose === 'login' ? 1200 : 800} height={800}
      className={`commodity-art commodity-photo${hero ? ' hero-art' : ''}`}
      alt={`${purpose === 'login' ? '金属材料仓储场景' : hero ? '铜板与铝锭' : product?.label}，AI 生成示意图`}
      loading={hero || purpose === 'login' ? 'eager' : 'lazy'} decoding="async"
      onError={() => setFailed(src)} />
  )
  return <VectorArtwork name={name} hero={hero} />
}

/** 尚无图片的品类或图片加载失败时显示矢量备用图。 */
function VectorArtwork({ name, hero }: {
  name: string
  hero: boolean
}) {
  const id = useId().replace(/:/g, '')
  const grain = /粮|麦|玉米|豆|农|稻/.test(name)
  const copper = /铜|有色/.test(name)
  const ingot = /锭|铟/.test(name)
  const energy = /煤|焦|能源|矿/.test(name)
  const chemical = /化工|塑|橡|油|聚|酸锂|氧化锂/.test(name)
  const color = copper
    ? '#b77240'
    : grain
      ? '#caa260'
      : energy
        ? '#525858'
        : chemical
          ? '#bdc6be'
          : '#8c9c9f'
  return (
    <svg
      viewBox="0 0 400 240"
      className={hero ? 'commodity-art hero-art' : 'commodity-art'}
      aria-hidden="true"
    >
      <defs>
        <linearGradient id={`${id}metal`} x1="0" y1="0" x2="0.7" y2="1">
          <stop stopColor="#edf1ed" />
          <stop offset=".35" stopColor={color} />
          <stop offset=".65" stopColor="#d8dfda" />
          <stop offset="1" stopColor={color} />
        </linearGradient>
        <radialGradient id={`${id}inner`}>
          <stop stopColor="#343e3d" />
          <stop offset=".6" stopColor="#566260" />
          <stop offset="1" stopColor={color} />
        </radialGradient>
        <filter id={`${id}shadow`}>
          <feGaussianBlur stdDeviation="7" />
        </filter>
      </defs>
      <ellipse
        cx="209"
        cy="205"
        rx="135"
        ry="14"
        fill="#493a2c"
        opacity=".15"
        filter={`url(#${id}shadow)`}
      />
      {copper || ingot ? (
        <g>
          {[0, 1, 2, 3].map((n) => (
            <g
              key={n}
              transform={`translate(${(n % 2) * 95} ${-Math.floor(n / 2) * 42})`}
            >
              <path
                d="M76 154 L139 117 L220 137 L167 175 Z"
                fill={copper ? '#d2a278' : '#d2d9d6'}
                stroke={color}
              />
              <path
                d="M76 154 L167 175 L170 196 L80 174 Z"
                fill={copper ? '#a66a42' : '#8c9c9f'}
                stroke={color}
              />
              <path
                d="M167 175 L220 137 L216 160 L170 196 Z"
                fill={copper ? '#be8254' : '#b1bdbb'}
                stroke={color}
              />
              <path
                d="M105 137 L191 158"
                fill="none"
                stroke={copper ? '#e6bd98' : '#eef1ed'}
                strokeWidth="2"
              />
            </g>
          ))}
        </g>
      ) : grain ? (
        <g>
          {[0, 1, 2].map((n) => (
            <g
              key={n}
              transform={`translate(${96 + n * 76} ${70 + (n % 2) * 26}) rotate(${n * 6 - 6})`}
            >
              <path
                d="M12 22 Q40 4 68 22 L78 112 Q40 132 0 112 Z"
                fill={n === 1 ? '#bda078' : '#d7bd92'}
                stroke="#ab8d60"
              />
              <path
                d="M12 22 Q40 38 68 22 M9 90 Q40 98 73 90"
                fill="none"
                stroke="#a38961"
              />
              <path
                d="M40 45 V78 M40 51 l-9 -6 M40 61 l10 -8 M40 70 l-9 -6"
                fill="none"
                stroke="#8e744b"
                strokeWidth="2"
              />
            </g>
          ))}
        </g>
      ) : energy ? (
        <g>
          {[0, 1, 2, 3, 4, 5, 6].map((n) => (
            <path
              key={n}
              d="M-35 10 L-22 -23 L8 -33 L37 -7 L24 25 L-8 31 Z"
              transform={`translate(${120 + (n % 4) * 53} ${157 - Math.floor(n / 4) * 40}) rotate(${n * 25})`}
              fill={n % 2 ? '#58615e' : '#707976'}
              stroke="#8e9590"
            />
          ))}
        </g>
      ) : chemical ? (
        <g>
          {[0, 1, 2].map((n) => (
            <g
              key={n}
              transform={`translate(${88 + n * 84} ${65 + (n % 2) * 24})`}
            >
              <path
                d="M0 14 L0 105 Q32 125 64 105 L64 14 Z"
                fill={`url(#${id}metal)`}
                stroke="#9ba59e"
              />
              <ellipse
                cx="32"
                cy="14"
                rx="32"
                ry="11"
                fill="#e0e6df"
                stroke="#9ba59e"
              />
              <path
                d="M0 38 Q32 52 64 38 M0 85 Q32 100 64 85"
                stroke="#88978e"
                fill="none"
                strokeWidth="3"
              />
              <rect
                x="16"
                y="53"
                width="32"
                height="25"
                rx="2"
                fill="#ebe9df"
              />
              <path d="M25 63 h14 M25 68 h10" stroke="#828e85" />
            </g>
          ))}
        </g>
      ) : (
        <g>
          <path
            d="M124 62 L246 35 Q324 58 315 140 L214 195 L109 143 Z"
            fill={`url(#${id}metal)`}
            stroke={color}
          />
          <path
            d="M124 62 L246 35 M140 76 L263 47 M150 94 L278 64 M158 118 L292 85 M160 144 L307 113"
            fill="none"
            stroke="#f6f8f4"
            opacity=".45"
          />
          <ellipse
            cx="153"
            cy="134"
            rx="62"
            ry="72"
            transform="rotate(-22 153 134)"
            fill={`url(#${id}metal)`}
            stroke="#718481"
          />
          {[52, 43, 34].map((r) => (
            <ellipse
              key={r}
              cx="153"
              cy="134"
              rx={r}
              ry={r * 1.16}
              transform="rotate(-22 153 134)"
              fill="none"
              stroke="#576c69"
              opacity=".6"
            />
          ))}
          <ellipse
            cx="153"
            cy="134"
            rx="24"
            ry="30"
            transform="rotate(-22 153 134)"
            fill={`url(#${id}inner)`}
          />
          <path
            d="M283 51 L287 151 M206 49 L210 191"
            stroke="#465551"
            strokeWidth="6"
            opacity=".55"
          />
        </g>
      )}
    </svg>
  )
}
