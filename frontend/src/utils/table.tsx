import type { TablePaginationConfig } from 'antd'

/** 可变长列表共用的分页配置。 */
export const LIST_PAGINATION: TablePaginationConfig = {
  defaultPageSize: 10,
  // 支持按需调整每页条数。
  showSizeChanger: true,
  pageSizeOptions: ['10', '20', '50'],
  // 始终显示总数，便于确认筛选结果。
  showTotal: (total, range) => `第 ${range[0]}-${range[1]} 条 / 共 ${total} 条`,
  hideOnSinglePage: false,
}

/** 按时间升序比较；表格默认可反向排序。 */
export function byTime<T extends { createdAt: string }>(a: T, b: T): number {
  return new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
}

/** 比较可空数字；缺值排在有效数字之后。 */
export function byNumberNullsLast<T>(
  pick: (row: T) => number | null | undefined,
): (a: T, b: T) => number {
  return (a, b) => {
    const left = pick(a)
    const right = pick(b)
    if (left == null && right == null) return 0
    if (left == null) return 1
    if (right == null) return -1
    return left - right
  }
}
