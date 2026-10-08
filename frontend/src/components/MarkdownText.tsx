import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Link } from 'react-router-dom'

/** 安全渲染顾问 Markdown；不启用原生 HTML。 */
export default function MarkdownText({ content, allowedLinks = [] }: { content: string; allowedLinks?: string[] }) {
  return (
    <div className="markdown-body">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        skipHtml
        components={{
          // 宽表格在窄屏上横向滚动，避免撑开对话区。
          table: ({ children }) => (
            <div className="md-table-wrap">
              <table>{children}</table>
            </div>
          ),
          a: ({ children, href }) => href && /^\/trading\?listing=\d{1,19}$/.test(href) && allowedLinks.includes(href)
            ? <Link to={href}>{children}</Link> : <span>{children}</span>,
          // 顾问无需加载模型生成的外部图片，避免跟踪和任意网络请求。
          img: () => null,
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
}
