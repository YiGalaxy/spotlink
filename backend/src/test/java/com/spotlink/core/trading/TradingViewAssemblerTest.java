package com.spotlink.trading;

import com.spotlink.trading.dto.OrderView;
import com.spotlink.trading.entity.Order;
import com.spotlink.trading.entity.OrderStatus;
import com.spotlink.trading.service.TradingViewAssembler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rendering orders that have nothing attached to them.
 *
 * <p><b>Every case here is an order with the relations left null</b>, because
 * that is the shape that broke: the batch-wide lookups for listings and
 * contracts return an immutable empty map when no row in the batch has one, and
 * immutable maps reject a null key rather than returning null for it. So
 * rendering a page of orders where none had a contract raised a null pointer,
 * and the user saw nothing but 「系统繁忙」 — the platform telling them it was
 * busy when in fact it had a bug.
 *
 * <p>The bug hid for a while because it depended on the data: as soon as one
 * order in the batch had a contract the map was a HashMap, which tolerates a
 * null key, and everything rendered. Which is the reason this is a test and not
 * a comment — the failing input is the one a developer is least likely to try.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("订单视图：没有关联单据时也要能渲染")
class TradingViewAssemblerTest {

    private static final Long TENANT = 999_000_201L;

    @Autowired
    private TradingViewAssembler assembler;

    @Test
    @DisplayName("整批订单都没有合同、没有挂牌，仍然渲染得出来")
    void ordersWithNoRelationsRender() {
        // Nothing on this order points anywhere: no listing, no contract. The
        // batch therefore produces empty lookup maps for both.
        Order bare = order(OrderStatus.CONFIRMED);
        bare.setListingId(null);
        bare.setContractId(null);

        List<OrderView> views = assembler.toOrderViews(List.of(bare), TENANT);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).orderNo()).isEqualTo(bare.getOrderNo());
        // CONFIRMED with no contract: both parties may draft, so the hint is
        // the caller's move rather than an absence.
        assertThat(views.get(0).statusHint()).isEqualTo("待起草合同");
        assertThat(views.get(0).statusHintMine()).isTrue();
    }

    @Test
    @DisplayName("混合批次：有的有合同有的没有，两边的提示都对")
    void mixedBatchRendersBothKinds() {
        Order bare = order(OrderStatus.CONFIRMED);
        bare.setListingId(null);
        bare.setContractId(null);

        Order delivered = order(OrderStatus.CONTRACTED);
        delivered.setListingId(null);
        delivered.setContractId(null);
        delivered.setDeliveryMethod(com.spotlink.trading.entity.Listing.DeliveryMethod.DELIVERED);

        List<OrderView> views = assembler.toOrderViews(List.of(bare, delivered), TENANT);

        assertThat(views).hasSize(2);
        // TENANT is the buyer here, so a 送到 order is the seller's move to
        // ship and the buyer's to wait. Asserting the pair rather than the two
        // strings separately is the point: a mapping written backwards would
        // still produce two plausible sentences.
        assertThat(views).extracting(OrderView::statusHint)
                .containsExactlyInAnyOrder("待起草合同", "等对方发货");
        assertThat(views).filteredOn(v -> "等对方发货".equals(v.statusHint()))
                .allSatisfy(v -> assertThat(v.statusHintMine()).isFalse());
    }

    @Test
    @DisplayName("平台运营账号看订单也不会炸")
    void anOperatorRenders() {
        Order order = order(OrderStatus.CONFIRMED);
        order.setContractId(null);

        List<OrderView> views = assembler.toOrderViews(List.of(order), null);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).statusHint()).isNull();
    }

    private Order order(String status) {
        Order order = new Order();
        order.setOrderNo("TEST" + System.nanoTime());
        order.setBuyerId(TENANT);
        order.setSellerId(999_000_202L);
        order.setCategoryId(1002L);
        order.setCommodityName("回归测试电解铜");
        order.setSpec("{}");
        order.setQuantity(new BigDecimal("5"));
        order.setUnit("吨");
        order.setPrice(new BigDecimal("68000"));
        order.setAmount(new BigDecimal("340000"));
        order.setDeliveryMethod(com.spotlink.trading.entity.Listing.DeliveryMethod.SELF_PICKUP);
        order.setPaymentTerms("MARGIN_THEN_BALANCE");
        order.setStatus(status);
        order.setVersion(0);
        return order;
    }
}
