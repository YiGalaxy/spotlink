package com.bulk.trade.trading.service;

import com.bulk.trade.contract.entity.Contract;
import com.bulk.trade.trading.entity.Listing;
import com.bulk.trade.trading.entity.Order;
import com.bulk.trade.trading.entity.OrderStatus;

/**
 * 用查看者自己的说法讲清楚现在轮到谁。
 *
 * <p><b>为什么光有状态不够。</b>“已签约”和“交收中”描述的是订单位于何处，
 * 而不是它在等什么。卖方和买方看着同一笔“交收中”的订单，需要的是两句话义
 * 相反的话，而两人都无法从这个词本身判断出被等的那个人是不是自己。订单攒到
 * 二十笔时，这就会把“我该做什么”变成一场搜索。
 *
 * <p>所以这里的每一条提示都只回答一个问题——<em>下一步是我的还是对方的
 * </em>——并且是从调用方的立场写的。下面两笔订单唯一的区别就在于读者是谁：
 *
 * <pre>
 *   卖方视角：等对方收货
 *   买方视角：待我收货
 * </pre>
 *
 * <p><b>{@code mine} 与那句话分开携带</b>，因为客户端要据此排序和着色。一个
 * 必须靠解析文案才能决定是否高亮某一行的客户端，离悄悄挂掉只差一次措辞调整，
 * 而文案恰恰是最可能被调整的部分。
 *
 * @param text       要展示的句子；当订单已终结、没有任何人需要动作时为 null
 * @param mine       当下一步属于查看者时为 true
 * @param nextAction 执行该动作的按钮标签；当该动作不属于查看者时为 null。
 *                   它与 {@code text} 分开，因为二者说的是不同的事：待我发货
 *                   描述一种处境，确认发货执行一个动作
 */
public record OrderProgress(String text, boolean mine, String nextAction) {

    private static final OrderProgress NOTHING_PENDING = new OrderProgress(null, false, null);

    /**
     * 为某一笔订单和某一个查看者算出提示语。
     *
     * @param contract 该订单的合同，尚未起草时为 null。之所以需要它，是因为
     *                 “已签约”并没有说明是谁签了。
     */
    public static OrderProgress of(Order order, Contract contract, Long viewerEnterpriseId) {
        if (viewerEnterpriseId == null) {
            // 平台运营方不是当事方，因此没有任何一步属于他。控制台改用原始
            // 状态来展示。
            return NOTHING_PENDING;
        }
        boolean iAmBuyer = viewerEnterpriseId.equals(order.getBuyerId());
        String status = order.getStatus();

        boolean arrives = Listing.DeliveryMethod.DELIVERED.equals(order.getDeliveryMethod());

        return switch (status) {
            case OrderStatus.PENDING_CONFIRM ->
                    // 只有挂牌方来答复，而且只有 MANUAL 挂牌才会把订单留在
                    // 这个状态，所以挂牌方即卖方。
                    iAmBuyer
                            ? new OrderProgress("等对方确认摘牌", false, null)
                            : new OrderProgress("待我确认摘牌", true, "确认成交");

            case OrderStatus.CONFIRMED -> contractHint(contract, viewerEnterpriseId);

            // 无论货物随后怎么走，放货的都是卖方。送到意味着发货；自提意味着
            // 备好货等待提取——两种情况下都是卖方的动作，买方在等。
            case OrderStatus.CONTRACTED -> iAmBuyer
                    ? new OrderProgress(arrives ? "等对方发货" : "等对方放货", false, null)
                    : new OrderProgress(arrives ? "待我发货" : "待我放货", true,
                            arrives ? "确认发货" : "确认放货");

            // 随后由买方收货。送到是收货，自提是提货——同一次交接，从旅程的
            // 两端各自描述。
            case OrderStatus.DELIVERING -> iAmBuyer
                    ? new OrderProgress(arrives ? "待我收货" : "待我提货", true,
                            arrives ? "确认收货" : "确认提货")
                    : new OrderProgress(arrives ? "等对方收货" : "等对方提货", false, null);

            // 无论哪种都已结束：没有任何人需要做什么。
            default -> NOTHING_PENDING;
        };
    }

    /**
     * 介于确认与签署之间的状态。
     *
     * <p>任何一方都可以起草——所以才说“待起草合同”而不点名某一方——但签署是
     * 逐方的，因此草稿一旦存在，这句话就要按谁已经签了来分别表述。
     */
    private static OrderProgress contractHint(Contract contract, Long viewerEnterpriseId) {
        if (contract == null) {
            return new OrderProgress("待起草合同", true, "起草合同");
        }
        if (!Contract.Status.PENDING_SIGN.equals(contract.getStatus())) {
            return NOTHING_PENDING;
        }

        boolean iAmBuyer = contract.isBuyer(viewerEnterpriseId);
        boolean iSigned = iAmBuyer
                ? contract.getBuyerSignedAt() != null
                : contract.getSellerSignedAt() != null;
        boolean theySigned = iAmBuyer
                ? contract.getSellerSignedAt() != null
                : contract.getBuyerSignedAt() != null;

        if (iSigned && !theySigned) {
            return new OrderProgress("等对方签署", false, null);
        }
        if (!iSigned && theySigned) {
            return new OrderProgress("待我签署", true, "签署合同");
        }
        return new OrderProgress("待双方签署", true, "签署合同");
    }
}
