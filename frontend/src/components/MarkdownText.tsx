import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

/**
 * 渲染顾问的回答。
 *
 * <p>助手输出 Markdown，因为这是让语言模型吐出可读表格或列表最省事的方式
 * ——但 Markdown 同时也是一段可以夹带原生 HTML 的纯字符串，所以这里走
 * react-markdown 渲染，而不是作为 HTML 注入。react-markdown 构建的是 React
 * 元素，并丢弃一切它不认识的东西，因此工具结果里的 `<script>` 无法变成标记。
 *
 * <p>不用 `rehype-raw`：允许原生 HTML 恰好会抵消掉这层保护。
 */
export default function MarkdownText({ content }: { content: string }) {
  return (
    <div className="markdown-body">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          // 表格正是这里值得渲染 Markdown 的主要原因，所以给它一个滚动容器：
          // 在窄屏上，一张宽表格不能把整个对话栏撑开。
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
