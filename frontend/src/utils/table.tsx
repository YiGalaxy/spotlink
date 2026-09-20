import type { TablePaginationConfig } from 'antd'

/**
 * Shared table behaviour for lists that grow.
 *
 * <p>Extracted because the three lists on the trading screen had drifted into
 * three different answers to the same question — one paginated at ten with no
 * size control, one paginated at ten and hid the controls whenever there was
 * only a page, one did not paginate at all — and a list that does not paginate
 * is fine until the day it is not.
 */
export const LIST_PAGINATION: TablePaginationConfig = {
  defaultPageSize: 10,
  // The size control is the point of this file. Ten rows is right for scanning
  // and wrong for reconciling, and only the person looking knows which they are
  // doing.
  showSizeChanger: true,
  pageSizeOptions: ['10', '20', '50'],
  // Always shown, even for a single page: "共 7 条" answers "did my filter
  // match anything" without counting rows.
  showTotal: (total, range) => `第 ${range[0]}-${range[1]} 条 / 共 ${total} 条`,
  hideOnSinglePage: false,
}

/**
 * Oldest first, as a comparator.
 *
 * <p>Ant Design applies `defaultSortOrder: 'descend'` by reversing this, so the
 * column opens newest-first without a second comparator — and the user can
 * still click through to oldest-first, which someone reconciling against a
 * statement will want.
 */
export function byTime<T extends { createdAt: string }>(a: T, b: T): number {
  return new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
}

/**
 * Compares numbers that may be absent, treating "absent" as its own ordering
 * rather than as zero.
 *
 * <p>A negotiable listing has no price and an undrafted order has no amount.
 * As zeros they would sort below every real figure ascending and above every
 * one descending — in both cases burying the largest actual value behind a
 * column of dashes. Absent values go last whichever direction is chosen.
 *
 * <p>Note this runs reversed when the column is descending, so the nulls-last
 * behaviour is inverted there by Ant Design. That is accepted rather than
 * fought: a dash at the top of a descending sort is visible and obviously a
 * dash, whereas a dash in the middle of real numbers reads as a bug.
 */
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
