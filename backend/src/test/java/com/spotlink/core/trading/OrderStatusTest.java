package com.spotlink.trading;

import com.spotlink.trading.entity.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transition table, checked without a database.
 *
 * <p>Pure data, so this runs in milliseconds and can afford to be exhaustive:
 * every status is a key, every key's targets are themselves known statuses, and
 * terminal states lead nowhere. A table that is wrong here is wrong everywhere,
 * and the failure it causes — an order reaching a state nobody intended — is
 * one that would otherwise be found by a user rather than by a build.
 */
class OrderStatusTest {

    private static final Set<String> ALL = Set.of(
            OrderStatus.PENDING_CONFIRM, OrderStatus.CONFIRMED, OrderStatus.CONTRACTED,
            OrderStatus.DELIVERING, OrderStatus.COMPLETED, OrderStatus.CANCELLED);

    @Test
    @DisplayName("every status is a key, so a missing entry is a test failure not a surprise")
    void tableIsTotal() {
        for (String status : ALL) {
            assertThat(OrderStatus.allowedFrom(status))
                    .as("transitions declared for %s", status)
                    .isNotNull();
        }
        assertThat(OrderStatus.canTransition("NOT_A_STATUS", OrderStatus.CONFIRMED))
                .as("an unknown status permits nothing")
                .isFalse();
    }

    @Test
    @DisplayName("no transition leads to a status that does not exist")
    void targetsAreRealStatuses() {
        for (String status : ALL) {
            assertThat(OrderStatus.allowedFrom(status))
                    .as("targets from %s", status)
                    .allSatisfy(target -> assertThat(ALL).contains(target));
        }
    }

    @Test
    @DisplayName("terminal states lead nowhere")
    void terminalStatesAreTerminal() {
        assertThat(OrderStatus.allowedFrom(OrderStatus.COMPLETED)).isEmpty();
        assertThat(OrderStatus.allowedFrom(OrderStatus.CANCELLED)).isEmpty();
        assertThat(OrderStatus.isTerminal(OrderStatus.COMPLETED)).isTrue();
        assertThat(OrderStatus.isTerminal(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.isTerminal(OrderStatus.DELIVERING)).isFalse();
    }

    @Test
    @DisplayName("the happy path exists and delivery cannot be cancelled")
    void lifecycleShape() {
        assertThat(OrderStatus.canTransition(OrderStatus.PENDING_CONFIRM, OrderStatus.CONFIRMED)).isTrue();
        assertThat(OrderStatus.canTransition(OrderStatus.CONFIRMED, OrderStatus.CONTRACTED)).isTrue();
        assertThat(OrderStatus.canTransition(OrderStatus.CONTRACTED, OrderStatus.DELIVERING)).isTrue();
        assertThat(OrderStatus.canTransition(OrderStatus.DELIVERING, OrderStatus.COMPLETED)).isTrue();

        // Goods are physically moving by then; the remedy is not a state change.
        assertThat(OrderStatus.canTransition(OrderStatus.DELIVERING, OrderStatus.CANCELLED)).isFalse();
        // Nothing skips a step.
        assertThat(OrderStatus.canTransition(OrderStatus.PENDING_CONFIRM, OrderStatus.DELIVERING)).isFalse();
        assertThat(OrderStatus.canTransition(OrderStatus.CONFIRMED, OrderStatus.COMPLETED)).isFalse();
    }

    @Test
    @DisplayName("only the lister may answer a waiting acceptance")
    void answeringIsOnePartysMove() {
        Set<String> lister = OrderStatus.allowedFrom(OrderStatus.PENDING_CONFIRM, true, true);
        Set<String> counterparty = OrderStatus.allowedFrom(OrderStatus.PENDING_CONFIRM, false, true);

        assertThat(lister).containsExactlyInAnyOrder(OrderStatus.CONFIRMED, OrderStatus.CANCELLED);
        // Declining your own offer is not something anyone needs protecting
        // from, so walking away stays open to both.
        assertThat(counterparty).containsExactly(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("卖方放货，买方收货，各管一步")
    void deliveryIsTwoStepsWithDifferentOwners() {
        // 交收 is not one act either party performs: the seller releases the
        // goods and the buyer receives them. If both could do both, then
        // DELIVERING would stop meaning "the goods are out" and COMPLETED would
        // stop meaning "and they arrived" — the two states would collapse into
        // one.
        Set<String> sellerStarts = OrderStatus.allowedFrom(OrderStatus.CONTRACTED, false, true);
        Set<String> buyerStarts = OrderStatus.allowedFrom(OrderStatus.CONTRACTED, false, false);
        assertThat(sellerStarts).contains(OrderStatus.DELIVERING);
        assertThat(buyerStarts).doesNotContain(OrderStatus.DELIVERING);

        Set<String> buyerFinishes = OrderStatus.allowedFrom(OrderStatus.DELIVERING, false, false);
        Set<String> sellerFinishes = OrderStatus.allowedFrom(OrderStatus.DELIVERING, false, true);
        assertThat(buyerFinishes).contains(OrderStatus.COMPLETED);
        // Nothing at all for the seller here: the goods are out and the next
        // move is the receiver's. An empty set is the honest answer, and the
        // screen shows a wait rather than a button that would fail.
        assertThat(sellerFinishes).isEmpty();
    }

    @Test
    @DisplayName("三个阶段按调用者区分，其余一律不区分")
    void onlyThreeStatusesAreRoleSensitive() {
        for (String status : ALL) {
            if (OrderStatus.PENDING_CONFIRM.equals(status)
                    || OrderStatus.CONTRACTED.equals(status)
                    || OrderStatus.DELIVERING.equals(status)) {
                continue;
            }
            assertThat(OrderStatus.allowedFrom(status, false, false))
                    .as("actions as the other party from %s", status)
                    .isEqualTo(OrderStatus.allowedFrom(status, true, true));
        }
    }

    @Test
    @DisplayName("statuses render in Chinese, and an unknown one says so")
    void textIsAlwaysSomething() {
        for (String status : ALL) {
            assertThat(OrderStatus.text(status)).isNotBlank().isNotEqualTo("未知");
        }
        assertThat(OrderStatus.text(null)).isEqualTo("未知");
        assertThat(OrderStatus.text("SOMETHING_ELSE")).isEqualTo("未知");
    }
}
