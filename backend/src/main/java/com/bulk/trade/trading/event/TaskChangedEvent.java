package com.bulk.trade.trading.event;

/**
 * 当某一方需要处理的事情发生变化时抛出。
 *
 * <p>只携带<em>谁的</em>列表变了以及<em>为什么</em>变——绝不携带任务本身。
 * 在这里重算任务，会把第二份“什么算作待办”的定义塞进事件里，而这两份定义
 * 终将产生分歧；监听方改为通过 {@code TaskService} 重新拉取，于是这个问题
 * 只有一个答案，而且这个答案待在可以被测试的地方。
 *
 * <p>用事件而不是直接调用，与 {@link OrderTradedEvent} 一样：订单服务抛出
 * 它，并且从不知道存在通知渠道，因此该渠道可以被移除而交易代码不必改动。
 *
 * @param reason        用于日志的简短标签，例如 "摘牌待确认"
 * @param enterpriseIds 所有待办工作发生变化的企业——通常是双方，因为一方
 *                      的动作会改变另一方看到的内容，即便这并未给对方新增
 *                      待办
 */
public record TaskChangedEvent(String reason, Long... enterpriseIds) {
}
