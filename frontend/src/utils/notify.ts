import type { MessageInstance } from 'antd/es/message/interface'

/**
 * 从 Ant Design App 上下文中解析出的单个 message 实例。
 *
 * <p>静态的 `message.error(...)` 辅助函数读不到主题和语言上下文，所以它们会
 * 报警告并且渲染成没有样式的样子。axios 拦截器活在 React 之外，不能调用
 * hook，因此这个实例由根组件注册一次，之后统一从这里取用。
 */
let messageApi: MessageInstance | null = null

export function registerMessageApi(api: MessageInstance) {
  messageApi = api
}

export function notifyError(content: string) {
  if (messageApi) {
    void messageApi.error(content)
  } else {
    console.error(content)
  }
}

export function notifySuccess(content: string) {
  if (messageApi) {
    void messageApi.success(content)
  }
}
