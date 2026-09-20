import { api } from './client'
import type { EntityId, QuoteRow, SeriesData } from '@/types/api'

export type SeriesType =
  | 'TRADE_PRICE'
  | 'TRADE_VOLUME'
  | 'LISTING_VOLUME'
  | 'INVENTORY'

export function fetchQuotes(days = 90) {
  return api.get<QuoteRow[]>('/market/quotes', { days })
}

export function fetchSeries(type: SeriesType, categoryId?: EntityId, days = 30) {
  return api.get<SeriesData>('/market/series', {
    type,
    days,
    ...(categoryId ? { categoryId } : {}),
  })
}
