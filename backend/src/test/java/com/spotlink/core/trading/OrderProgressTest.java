package com.spotlink.trading;

import com.spotlink.contract.entity.Contract;
import com.spotlink.trading.entity.Listing;
import com.spotlink.trading.entity.Order;
import com.spotlink.trading.entity.OrderStatus;
import com.spotlink.trading.service.OrderProgress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whose move it is, checked without a database.
 *
 * <p>Pure data in, one sentence out, so this runs in milliseconds and can
 * afford to cover every branch. Worth covering exhaustively because the failure
 * is quiet: a hint that says "待我签署" to the party who already signed sends
 * someone looking for a button that is not there, and nothing on the screen
 * would look wrong.
 *
 * <p><b>The delivery cases assert a sequence, not just a pair.</b> Either party
 * acting first would look plausible in isolation — the earlier version of this
 * rule gave the buyer the first move on a 自提 order, which is wrong because
 * the goods have not been released yet. What settles it is that the seller
 * moves first under both delivery terms and the buyer second, so the tests are
 * written to fail if that order is ever reversed.
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
        assertThat(hintFor(order, null, SELLER).nextAction()).isEqualTo("确认成交");

        assertThat(hintFor(order, null, BUYER).text()).isEqualTo("等对方确认摘牌");
        assertThat(hintFor(order, null, BUYER).mine()).isFalse();
        // No label for a move that is not yours: the client draws a button from
        // this, and a label here would draw one the server refuses.
        assertThat(hintFor(order, null, BUYER).nextAction()).isNull();
    }

    // ------------------------------------------------------------------
    // Contract
    // ------------------------------------------------------------------

    @Test
    @DisplayName("还没起草：两方看到的都是待办")
    void undraftedIsEveryonesMove() {
        Order order = order(OrderStatus.CONFIRMED, Listing.DeliveryMethod.SELF_PICKUP);

        // Either party may draft, so neither is told to wait.
        assertThat(hintFor(order, null, BUYER).text()).isEqualTo("待起草合同");
        assertThat(hintFor(order, null, SELLER).text()).isEqualTo("待起草合同");
        assertThat(hintFor(order, null, BUYER).mine()).isTrue();
        assertThat(hintFor(order, null, BUYER).nextAction()).isEqualTo("起草合同");
    }

    @Test
    @DisplayName("已起草未签署：签过的人等，没签的人动")
    void signatureSplitsByWhoHasSigned() {
        Order order = order(OrderStatus.CONFIRMED, Listing.DeliveryMethod.SELF_PICKUP);

        Contract buyerSigned = pendingSignContract();
        buyerSigned.setBuyerSignedAt(OffsetDateTime.now());

        assertThat(hintFor(order, buyerSigned, SELLER).text()).isEqualTo("待我签署");
        assertThat(hintFor(order, buyerSigned, SELLER).nextAction()).isEqualTo("签署合同");
        assertThat(hintFor(order, buyerSigned, BUYER).text()).isEqualTo("等对方签署");
        assertThat(hintFor(order, buyerSigned, BUYER).nextAction()).isNull();

        Contract sellerSigned = pendingSignContract();
        sellerSigned.setSellerSignedAt(OffsetDateTime.now());
        assertThat(hintFor(order, sellerSigned, BUYER).text()).isEqualTo("待我签署");
        assertThat(hintFor(order, sellerSigned, SELLER).text()).isEqualTo("等对方签署");
    }

    @Test
    @DisplayName("双方都没签：待双方签署")
    void neitherSigned() {
        Order order = order(OrderStatus.CONFIRMED, Listing.DeliveryMethod.SELF_PICKUP);
        assertThat(hintFor(order, pendingSignContract(), BUYER).text()).isEqualTo("待双方签署");
        assertThat(hintFor(order, pendingSignContract(), BUYER).mine()).isTrue();
    }

    // ------------------------------------------------------------------
    // Delivery — a sequence, in both directions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("已签约：无论送到还是自提，都是卖方先动")
    void theSellerAlwaysReleasesFirst() {
        // The earlier version of this rule gave the buyer the first move on a
        // 自提 order, which cannot be right: nobody collects goods that have
        // not been released. The two terms differ in wording, not in who owes
        // the move.
        Order ships = order(OrderStatus.CONTRACTED, Listing.DeliveryMethod.DELIVERED);
        assertThat(hintFor(ships, null, SELLER).text()).isEqualTo("待我发货");
        assertThat(hintFor(ships, null, SELLER).nextAction()).isEqualTo("确认发货");
        assertThat(hintFor(ships, null, BUYER).text()).isEqualTo("等对方发货");
        assertThat(hintFor(ships, null, BUYER).mine()).isFalse();

        Order picksUp = order(OrderStatus.CONTRACTED, Listing.DeliveryMethod.SELF_PICKUP);
        assertThat(hintFor(picksUp, null, SELLER).text()).isEqualTo("待我放货");
        assertThat(hintFor(picksUp, null, SELLER).nextAction()).isEqualTo("确认放货");
        assertThat(hintFor(picksUp, null, BUYER).text()).isEqualTo("等对方放货");
        assertThat(hintFor(picksUp, null, BUYER).mine()).isFalse();
    }

    @Test
    @DisplayName("交收中：然后轮到买方，无论送到还是自提")
    void thenTheBuyerReceives() {
        Order ships = order(OrderStatus.DELIVERING, Listing.DeliveryMethod.DELIVERED);
        assertThat(hintFor(ships, null, BUYER).text()).isEqualTo("待我收货");
        assertThat(hintFor(ships, null, BUYER).nextAction()).isEqualTo("确认收货");
        assertThat(hintFor(ships, null, SELLER).text()).isEqualTo("等对方收货");
        assertThat(hintFor(ships, null, SELLER).mine()).isFalse();

        Order picksUp = order(OrderStatus.DELIVERING, Listing.DeliveryMethod.SELF_PICKUP);
        assertThat(hintFor(picksUp, null, BUYER).text()).isEqualTo("待我提货");
        assertThat(hintFor(picksUp, null, BUYER).nextAction()).isEqualTo("确认提货");
        assertThat(hintFor(picksUp, null, SELLER).text()).isEqualTo("等对方提货");
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
        OrderProgress progress = hintFor(order, null, null);
        assertThat(progress.text()).isNull();
        assertThat(progress.mine()).isFalse();
        assertThat(progress.nextAction()).isNull();
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
        // asserted separately so the exception is a decision rather than a hole
        // in the test above. Either party may draft, so telling one of them to
        // wait would invent a rule the platform does not have — and a seller
        // waiting for a buyer to draft is a deal that stalls while both sides
        // assume the other is on it.
        Order order = order(OrderStatus.CONFIRMED, Listing.DeliveryMethod.DELIVERED);

        assertThat(hintFor(order, null, BUYER).mine()).isTrue();
        assertThat(hintFor(order, null, SELLER).mine()).isTrue();
    }

    @Test
    @DisplayName("有按钮标签的一定是该我动，反之亦然")
    void aLabelImpliesOwnership() {
        // The client draws a button whenever nextAction is present, so the two
        // fields have to agree in every state — a label on a move that is not
        // the viewer's draws a button the server refuses.
        for (String status : new String[]{
                OrderStatus.PENDING_CONFIRM, OrderStatus.CONFIRMED,
                OrderStatus.CONTRACTED, OrderStatus.DELIVERING,
                OrderStatus.COMPLETED, OrderStatus.CANCELLED}) {
            for (String method : new String[]{
                    Listing.DeliveryMethod.DELIVERED, Listing.DeliveryMethod.SELF_PICKUP}) {
                Order order = order(status, method);
                for (Long viewer : new Long[]{BUYER, SELLER}) {
                    OrderProgress progress = hintFor(order, null, viewer);
                    assertThat(progress.nextAction() != null)
                            .as("label implies ownership for %s/%s", status, method)
                            .isEqualTo(progress.mine());
                }
            }
        }
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
