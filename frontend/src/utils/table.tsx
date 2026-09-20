import type { TablePaginationConfig } from 'antd'

/**
 * 供会变长的列表共用的表格行为。
 *
 * <p>之所以抽出来，是因为交易界面上的三个列表对同一个问题已经漂移成了三种不同
 * 的答案——一个每页十条第但没有条数选择器，一个每页十条、只要只有一页就把控件
 * 藏起来，还有一个根本不分页——而一个不分页的列表，出事之前看上去都没问题。
 */
export const LIST_PAGINATION: TablePaginationConfig = {
  defaultPageSize: 10,
  // 条数选择器才是这个文件存在的意义。十行适合扫一眼，却不适合对账，
  // 而只有正在看的人才知道自己在做哪一件事。
  showSizeChanger: true,
  pageSizeOptions: ['10', '20', '50'],
  // 始终显示，哪怕只有一页：「共 7 条」不必数行数就能回答「我的筛选到底
  // 有没有命中东西」。
  showTotal: (total, range) => `第 ${range[0]}-${range[1]} 条 / 共 ${total} 条`,
  hideOnSinglePage: false,
}

/**
 * 作为比较器，最旧在前。
 *
 * <p>Ant Design 配合 `defaultSortOrder: 'descend'` 会把它反过来用，于是这一列
 * 不必再写第二个比较器就能以最新在前打开——而用户仍可以点穿到最旧在前，这正是
 * 对着账单逐笔核对的人需要的。
 */
export function byTime<T extends { createdAt: string }>(a: T, b: T): number {
  return new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
}

/**
 * 比较可能缺值的数字，把「缺值」当作它自己的一种次序，而不是当作零。
 *
 * <p>可议价的挂牌没有价格，未起草的订单没有金额。若当作零，升序时它们会排在
 * 每一个真实数字之下，降序时则排在每一个之上——两种情况下都把真正最大的那个值
 * 埋进了一列破折号后面。缺值无论选哪个方向都排在最后。
 *
 * <p>注意：该列降序时这段逻辑会被反过来执行，所以「缺值最后」的行为在那里被
 * Ant Design 反转了。这一点是被接受而非被对抗的：降序排序里出现在最上方的破折号
 * 看得见，而且一眼就知道是破折号；而夹在真实数字中间的破折号，读起来像 bug。
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
