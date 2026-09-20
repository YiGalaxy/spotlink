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
 * @param text   the sentence to show, or null when the order is finished and
 *               nothing is pending from anyone
 * @param mine   true when the next move belongs to the viewer
 */
public record OrderProgress(String text, boolean mine) {

    private static final OrderProgress NOTHING_PENDING = new OrderProgress(null, false);

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

        return switch (status) {
            case OrderStatus.PENDING_CONFIRM ->
                    // Only the lister answers, and only a MANUAL listing ever
                    // leaves an order here, so the lister is the seller.
                    iAmBuyer
                            ? new OrderProgress("等对方确认摘牌", false)
                            : new OrderProgress("待我确认摘牌", true);

            case OrderStatus.CONFIRMED -> contractHint(contract, viewerEnterpriseId);

            case OrderStatus.CONTRACTED -> deliveryHint(order, iAmBuyer);

            case OrderStatus.DELIVERING ->
                    // Whoever receives confirms completion, and a spot trade is
                    // completed by the receiving side.
                    iAmBuyer
                            ? new OrderProgress("待我收货", true)
                            : new OrderProgress("等对方收货", false);

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
            return new OrderProgress("待起草合同", true);
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
            return new OrderProgress("等对方签署", false);
        }
        if (!iSigned && theySigned) {
            return new OrderProgress("待我签署", true);
        }
        return new OrderProgress("待双方签署", true);
    }

    /**
     * Signed and ready; nobody has moved the goods yet.
     *
     * <p>Which side acts depends on the delivery term, which is the whole
     * reason this case cannot be a single sentence: under 送到 the seller
     * ships, under 自提 the buyer collects, and the same order status means
     * opposite things to the two readers.
     */
    private static OrderProgress deliveryHint(Order order, boolean iAmBuyer) {
        boolean sellerShips = Listing.DeliveryMethod.DELIVERED.equals(order.getDeliveryMethod());

        if (sellerShips) {
            return iAmBuyer
                    ? new OrderProgress("等对方发货", false)
                    : new OrderProgress("待我发货", true);
        }
        return iAmBuyer
                ? new OrderProgress("待我提货", true)
                : new OrderProgress("等对方提货", false);
    }
}
