package com.bulk.trade.trading.service;

import com.bulk.trade.contract.entity.Contract;
import com.bulk.trade.trading.entity.Listing;
import com.bulk.trade.trading.entity.Order;
import com.bulk.trade.trading.entity.OrderStatus;

/**
 * Says whose move it is, in the viewer's own terms.
 *
 * <p><b>Why the status alone is not enough.</b> "已签约" and "交收中" describe
 * where an order is, not what it is waiting for. A seller and a buyer looking
 * at the same 交收中 order need opposite sentences, and neither can tell from
 * the word whether they are the one being waited on. With twenty orders, that
 * turns "what should I do" into a search.
 *
 * <p>So every hint here answers one question — <em>is the next move mine or
 * theirs</em> — and is written from the caller's side. The two orders below
 * differ only in who is reading:
 *
 * <pre>
 *   卖方视角：等对方收货
 *   买方视角：待我收货
 * </pre>
 *
 * <p><b>{@code mine} is carried separately from the sentence</b> because the
 * client sorts on it and colours by it. A client that had to parse the wording
 * to decide whether to highlight a row would be one rephrasing away from
 * silently breaking, and the wording is the part most likely to be rephrased.
 *
 * @param text       the sentence to show, or null when the order is finished
 *                   and nothing is pending from anyone
 * @param mine       true when the next move belongs to the viewer
 * @param nextAction the label for the button that performs the move, or null
 *                   when the move is not the viewer's. Separate from
 *                   {@code text} because the two say different things: 待我发货
 *                   describes a situation, 确认发货 performs an act
 */
public record OrderProgress(String text, boolean mine, String nextAction) {

    private static final OrderProgress NOTHING_PENDING = new OrderProgress(null, false, null);

    /**
     * Works out the hint for one order and one viewer.
     *
     * @param contract the order's contract, or null when none has been drafted.
     *                 Needed because "已签约" does not say who signed.
     */
    public static OrderProgress of(Order order, Contract contract, Long viewerEnterpriseId) {
        if (viewerEnterpriseId == null) {
            // A platform operator is not a party, so no move is theirs. The
            // console uses the raw status instead.
            return NOTHING_PENDING;
        }
        boolean iAmBuyer = viewerEnterpriseId.equals(order.getBuyerId());
        String status = order.getStatus();

        boolean arrives = Listing.DeliveryMethod.DELIVERED.equals(order.getDeliveryMethod());

        return switch (status) {
            case OrderStatus.PENDING_CONFIRM ->
                    // Only the lister answers, and only a MANUAL listing ever
                    // leaves an order here, so the lister is the seller.
                    iAmBuyer
                            ? new OrderProgress("等对方确认摘牌", false, null)
                            : new OrderProgress("待我确认摘牌", true, "确认成交");

            case OrderStatus.CONFIRMED -> contractHint(contract, viewerEnterpriseId);

            // The seller releases the goods, whichever way they then travel.
            // 送到 means shipping them; 自提 means making them available for
            // collection — the seller's act either way, and the buyer waits.
            case OrderStatus.CONTRACTED -> iAmBuyer
                    ? new OrderProgress(arrives ? "等对方发货" : "等对方放货", false, null)
                    : new OrderProgress(arrives ? "待我发货" : "待我放货", true,
                            arrives ? "确认发货" : "确认放货");

            // Then the buyer receives. 送到 is 收货, 自提 is 提货 — the same
            // handover described from the two ends of the journey.
            case OrderStatus.DELIVERING -> iAmBuyer
                    ? new OrderProgress(arrives ? "待我收货" : "待我提货", true,
                            arrives ? "确认收货" : "确认提货")
                    : new OrderProgress(arrives ? "等对方收货" : "等对方提货", false, null);

            // Finished either way: nothing for anyone to do.
            default -> NOTHING_PENDING;
        };
    }

    /**
     * Between confirmation and signature.
     *
     * <p>Either party may draft — hence "待起草合同" rather than naming a side —
     * but signing is per-party, so once a draft exists the sentence splits by
     * who has already signed.
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
