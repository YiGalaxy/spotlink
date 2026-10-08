import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

/** 安全渲染顾问 Markdown；不启用原生 HTML。 */
export default function MarkdownText({ content }: { content: string }) {
  return (
    <div className="markdown-body">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          // 宽表格在窄屏上横向滚动，避免撑开对话区。
          table: ({ children }) => (
            <div className="md-table-wrap">
              <table>{children}</table>
            </div>
          ),
          a: ({ children, href }) => (
            <a href={href} target="_blank" rel="noreferrer noopener">
              {children}
            </a>
          ),
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
}
