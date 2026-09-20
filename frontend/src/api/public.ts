import { api } from './client'
import type { PublicStats } from '@/types/api'

/**
 * 平台整体数据，无需登录即可读取。
 *
 * <p>这个接口上的所有内容描述的都是整个交易场所。任何属于某一家企业的东西
 * 都不该放进来——访客没有企业，也就没有任何可以据以限定范围的主体。
 */
export function fetchPublicStats() {
  return api.get<PublicStats>('/public/stats')
}
