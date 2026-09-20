import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

/**
 * Renders an advisor answer.
 *
 * <p>Assistants emit Markdown because it is the cheapest way to get a readable
 * table or list out of a language model — but Markdown is also a plain string
 * that can carry raw HTML, so it is rendered through react-markdown rather than
 * injected as HTML. react-markdown builds React elements and drops anything it
 * does not recognise, so a `<script>` in a tool result cannot become markup.
 *
 * <p>No `rehype-raw`: allowing raw HTML would undo exactly that protection.
 */
export default function MarkdownText({ content }: { content: string }) {
  return (
    <div className="markdown-body">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          // Tables are the main reason Markdown is worth rendering here, so
          // they get a scroll container: a wide table must not stretch the
          // whole chat column on a narrow screen.
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
