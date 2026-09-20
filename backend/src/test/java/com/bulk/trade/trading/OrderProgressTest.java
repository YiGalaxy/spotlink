package com.bulk.trade.trading;

import com.bulk.trade.contract.entity.Contract;
import com.bulk.trade.trading.entity.Listing;
import com.bulk.trade.trading.entity.Order;
import com.bulk.trade.trading.entity.OrderStatus;
import com.bulk.trade.trading.service.OrderProgress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whose move it is, checked without a database.
 *
 * <p>Pure data in, one sentence out, so this runs in milliseconds and can
 * afford to cover every branch. Worth covering exhaustively because the
 * failure is quiet: a hint that says "待我签署" to the party who already signed
 * sends someone to look for a button that is not there, and nothing about the
 * screen would look wrong.
 *
 * <p>The cases that matter most are the ones where the same order yields
 * opposite sentences to its two readers. Those are asserted in pairs rather
 * than one at a time, because a bug that got the mapping backwards would pass
 * a test that only ever looked at one side.
 */
class OrderProgressTest {

    private static final Long BUYER = 900_000_001L;
    private static final Long SELLER = 900_000_002L;

    // ------------------------------------------------------------------
    // Waiting for a lister's answer
    // ------------------------------------------------------------------

    @Test
    @DisplayName("待确认摘牌：只有挂牌方看到的是自己的活")
    void acceptanceIsOnlyTheListersMove() {
        Order order = order(OrderStatus.PENDING_CONFIRM, Listing.DeliveryMethod.SELF_PICKUP);

        assertThat(hintFor(order, null, SELLER).text()).isEqualTo("待我确认摘牌");
        assertThat(hintFor(order, null, SELLER).mine()).isTrue();

        assertThat(hintFor(order, null, BUYER).text()).isEqualTo("等对方确认摘牌");
        assertThat(hintFor(order, null, BUYER).mine()).isFalse();
    }

    // ------------------------------------------------------------------
    // Contract
    // ------------------------------------------------------------------

    @Test
    @DisplayName("还没起草：两方看到的都是待办")
    void undraftedIsEveryonesMove() {
        Order order = order(OrderStatus.CONFIRMED, Listing.DeliveryMethod.SELF_PICKUP);

        // Either party may draft, so neither is told to wait.
        assertThat(hintFor(order, null, BUYER)).isEqualTo(new OrderProgress("待起草合同", true));
        assertThat(hintFor(order, null, SELLER)).isEqualTo(new OrderProgress("待起草合同", true));
    }

    @Test
    @DisplayName("已起草未签署：签过的人等，没签的人动")
    void signatureSplitsByWhoHasSigned() {
        Order order = order(OrderStatus.CONFIRMED, Listing.DeliveryMethod.SELF_PICKUP);

        Contract buyerSigned = pendingSignContract();
        buyerSigned.setBuyerSignedAt(OffsetDateTime.now());

        // The buyer has signed: it is the seller's move, and the buyer is told
        // to wait rather than shown a button the server would reject.
        assertThat(hintFor(order, buyerSigned, SELLER)).isEqualTo(new OrderProgress("待我签署", true));
        assertThat(hintFor(order, buyerSigned, BUYER)).isEqualTo(new OrderProgress("等对方签署", false));

        Contract sellerSigned = pendingSignContract();
        sellerSigned.setSellerSignedAt(OffsetDateTime.now());
        assertThat(hintFor(order, sellerSigned, BUYER)).isEqualTo(new OrderProgress("待我签署", true));
        assertThat(hintFor(order, sellerSigned, SELLER)).isEqualTo(new OrderProgress("等对方签署", false));
    }

    @Test
    @DisplayName("双方都没签：待双方签署")
    void neitherSigned() {
        Order order = order(OrderStatus.CONFIRMED, Listing.DeliveryMethod.SELF_PICKUP);
        assertThat(hintFor(order, pendingSignContract(), BUYER).text()).isEqualTo("待双方签署");
        assertThat(hintFor(order, pendingSignContract(), BUYER).mine()).isTrue();
    }

    // ------------------------------------------------------------------
    // Delivery — the pair that must not be the same sentence
    // ------------------------------------------------------------------

    @Test
    @DisplayName("送到：卖方发货，买方等")
    void deliveredSplitsBySide() {
        Order order = order(OrderStatus.CONTRACTED, Listing.DeliveryMethod.DELIVERED);

        assertThat(hintFor(order, null, SELLER)).isEqualTo(new OrderProgress("待我发货", true));
        assertThat(hintFor(order, null, BUYER)).isEqualTo(new OrderProgress("等对方发货", false));
    }

    @Test
    @DisplayName("自提：买方提货，卖方等")
    void selfPickupSplitsTheOtherWay() {
        Order order = order(OrderStatus.CONTRACTED, Listing.DeliveryMethod.SELF_PICKUP);

        assertThat(hintFor(order, null, BUYER)).isEqualTo(new OrderProgress("待我提货", true));
        assertThat(hintFor(order, null, SELLER)).isEqualTo(new OrderProgress("等对方提货", false));
    }

    @Test
    @DisplayName("交收中：收货方确认，另一方等")
    void deliveringSplitsBySide() {
        Order order = order(OrderStatus.DELIVERING, Listing.DeliveryMethod.DELIVERED);

        assertThat(hintFor(order, null, BUYER)).isEqualTo(new OrderProgress("待我收货", true));
        assertThat(hintFor(order, null, SELLER)).isEqualTo(new OrderProgress("等对方收货", false));
    }

    // ------------------------------------------------------------------
    // The edges
    // ------------------------------------------------------------------

    @Test
    @DisplayName("已完成和已取消：两边都没有待办")
    void finishedOrdersSayNothing() {
        for (String status : new String[]{OrderStatus.COMPLETED, OrderStatus.CANCELLED}) {
            Order order = order(status, Listing.DeliveryMethod.SELF_PICKUP);
            assertThat(hintFor(order, null, BUYER).text()).as(status).isNull();
            assertThat(hintFor(order, null, SELLER).text()).as(status).isNull();
        }
    }

    @Test
    @DisplayName("平台运营账号不是当事人，永远没有待办")
    void anOperatorOwesNothing() {
        Order order = order(OrderStatus.DELIVERING, Listing.DeliveryMethod.SELF_PICKUP);
        assertThat(hintFor(order, null, null)).isEqualTo(new OrderProgress(null, false));
    }

    @Test
    @DisplayName("独占动作：恰好一方认为该自己动")
    void exclusiveMovesHaveExactlyOneOwner() {
        // The property behind every pair above, stated once: if both readers
        // were told "待我…", both would act; if neither were, nobody would.
        for (String status : new String[]{
                OrderStatus.PENDING_CONFIRM, OrderStatus.CONTRACTED, OrderStatus.DELIVERING}) {
            Order order = order(status, Listing.DeliveryMethod.DELIVERED);
            assertThat(hintFor(order, null, BUYER).mine() ^ hintFor(order, null, SELLER).mine())
                    .as("ownership of the next move for %s", status)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("起草合同是共有动作：两方都认为该自己动，且这是有意的")
    void draftingIsDeliberatelyShared() {
        // The one status where the exclusive-ownership property does not hold,
        // asserted separately so that the exception is a decision rather than a
        // hole in the test above. Either party may draft a contract, so telling
        // one of them to wait would invent a rule the platform does not have —
        // and the seller waiting for a buyer to draft is a deal that stalls
        // while both parties assume the other is on it.
        Order order = order(OrderStatus.CONFIRMED, Listing.DeliveryMethod.DELIVERED);

        assertThat(hintFor(order, null, BUYER).mine()).isTrue();
        assertThat(hintFor(order, null, SELLER).mine()).isTrue();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private OrderProgress hintFor(Order order, Contract contract, Long viewer) {
        return OrderProgress.of(order, contract, viewer);
    }

    private Order order(String status, String deliveryMethod) {
        Order order = new Order();
        order.setOrderNo("TEST" + status);
        order.setBuyerId(BUYER);
        order.setSellerId(SELLER);
        order.setQuantity(new BigDecimal("10"));
        order.setUnit("吨");
        order.setStatus(status);
        order.setDeliveryMethod(deliveryMethod);
        return order;
    }

    private Contract pendingSignContract() {
        Contract contract = new Contract();
        contract.setContractNo("CT-TEST");
        contract.setBuyerId(BUYER);
        contract.setSellerId(SELLER);
        contract.setStatus(Contract.Status.PENDING_SIGN);
        return contract;
    }
}
