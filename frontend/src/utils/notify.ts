import type { MessageInstance } from 'antd/es/message/interface'

/** Axios 拦截器使用的 Ant Design message 实例。 */
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
