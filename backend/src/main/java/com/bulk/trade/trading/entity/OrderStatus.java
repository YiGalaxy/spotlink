package com.bulk.trade.trading.entity;

import java.util.Map;
import java.util.Set;

/**
 * 订单生命周期，以一张显式的状态迁移表表达。
 *
 * <p><b>为什么用表而不是 if 语句。</b>当迁移逻辑以零散的
 * {@code if (status == ...)} 判断散落在 service 中时，合法动作的集合只存在
 * 于作者脑中，每新增一个状态都有机会引入一条谁也不想要的路径。在这里，合法
 * 动作就是数据：一处可读，一处可测，而一条没被列出的迁移就是无法发生。
 *
 * <pre>
 *   PENDING_CONFIRM ──→ CONFIRMED ──→ CONTRACTED ──→ DELIVERING ──→ COMPLETED
 *         │                 │             │
 *         └─────────────────┴─────────────┴──→ CANCELLED
 * </pre>
 *
 * <p><b>{@code PENDING_CONFIRM} 只由 MANUAL 挂牌进入</b>——即条款要求摘牌须
 * 由挂牌方答复的挂牌。本身就构成要约的挂牌（{@code AUTO}）在摘牌时直接进入
 * {@code CONFIRMED}，因为那里合同已然成立，而一个“等待确认”状态描述的会是
 * 一个根本没人在问的问题。
 *
 * <p>在交收开始前都允许取消，因为交收开始才是货物实际移动的时刻。此后，
 * 救济手段就不再是订单状态变更了。
 */
public final class OrderStatus {

    public static final String PENDING_CONFIRM = "PENDING_CONFIRM";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String CONTRACTED = "CONTRACTED";
    public static final String DELIVERING = "DELIVERING";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    /**
     * 合法动作。不可变且完整：每个状态都作为键出现，终态映射到空集合，因此
     * 一处遗漏的条目会是编译期可见的疏漏，而不是无声的“什么都可以”。
     */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            PENDING_CONFIRM, Set.of(CONFIRMED, CANCELLED),
            CONFIRMED, Set.of(CONTRACTED, CANCELLED),
            CONTRACTED, Set.of(DELIVERING, CANCELLED),
            DELIVERING, Set.of(COMPLETED),
            COMPLETED, Set.of(),
            CANCELLED, Set.of()
    );

    private OrderStatus() {
    }

    public static boolean canTransition(String from, String to) {
        Set<String> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    public static boolean isTerminal(String status) {
        return COMPLETED.equals(status) || CANCELLED.equals(status);
    }

    public static Set<String> allowedFrom(String status) {
        return TRANSITIONS.getOrDefault(status, Set.of());
    }

    /**
     * 针对特定调用方的合法动作。
     *
     * <p><b>有三个状态对角色敏感，原因只有一个：双方都能做的步骤就是没人
     * 负责的步骤。</b>
     *
     * <ul>
     *   <li>{@code PENDING_CONFIRM} ——只有挂牌方来答复。若允许对手方按下
     *       确认，就等于让提出交易的一方替对方接受了这笔交易，这与根本没有
     *       确认环节是一回事。</li>
     *   <li>{@code CONTRACTED} ——只有卖方可以放货。交收始于货物移动，而只有
     *       其所有者才能移动它。买方按下这个按钮，等于买方在宣布别人已经
     *       发货。</li>
     *   <li>{@code DELIVERING} ——只有买方可以收货。完成意味着货物送到并被
     *       接受，这是关于收货方拿到了什么的陈述。卖方确认自己完成的交收，
     *       就是当事人自己给自己批改作业。</li>
     * </ul>
     *
     * <p>在以上每一种情况下，对手方只要订单仍可取消就仍可退出——否定自己
     * 的交易，不是谁需要被保护免受其害的事。
     *
     * @param callerIsLister 发布了本订单所摘的那份挂牌
     * @param callerIsSeller 是放货的一方
     */
    public static Set<String> allowedFrom(String status, boolean callerIsLister, boolean callerIsSeller) {
        if (PENDING_CONFIRM.equals(status) && !callerIsLister) {
            return Set.of(CANCELLED);
        }
        if (CONTRACTED.equals(status) && !callerIsSeller) {
            return Set.of(CANCELLED);
        }
        if (DELIVERING.equals(status) && callerIsSeller) {
            // 这里没有什么可以给卖方的：货已发出，下一步轮到收货方。空集合
            // 就是诚实的答案，界面显示等待状态，而不是显示一个按下去会失败
            // 的按钮。
            return Set.of();
        }
        return allowedFrom(status);
    }

    public static String text(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            // 命名依据是谁欠一个答复，而不仅仅是有答复待给出：读者的下一步
            // 动作取决于他站在哪一方。
            case PENDING_CONFIRM -> "待挂牌方确认";
            case CONFIRMED -> "已确认";
            case CONTRACTED -> "已签约";
            case DELIVERING -> "交收中";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
            default -> "未知";
        };
    }
}
