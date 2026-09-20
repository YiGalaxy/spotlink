import type { MessageInstance } from 'antd/es/message/interface'

/**
 * A single message instance resolved from the Ant Design App context.
 *
 * <p>The static `message.error(...)` helpers cannot read the theme or locale
 * context, so they warn and render unstyled. Axios interceptors live outside
 * React and cannot call hooks, so the instance is registered once from the root
 * component and used from there.
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
