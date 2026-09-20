package com.bulk.trade.trading;

import com.bulk.trade.trading.entity.OrderStatus;
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
        Set<String> lister = OrderStatus.allowedFrom(OrderStatus.PENDING_CONFIRM, true);
        Set<String> counterparty = OrderStatus.allowedFrom(OrderStatus.PENDING_CONFIRM, false);

        assertThat(lister).containsExactlyInAnyOrder(OrderStatus.CONFIRMED, OrderStatus.CANCELLED);
        // Declining your own offer is not something anyone needs protecting
        // from, so walking away stays open to both.
        assertThat(counterparty).containsExactly(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("only PENDING_CONFIRM is role-sensitive")
    void otherStatusesIgnoreTheCaller() {
        for (String status : ALL) {
            if (OrderStatus.PENDING_CONFIRM.equals(status)) {
                continue;
            }
            assertThat(OrderStatus.allowedFrom(status, false))
                    .as("actions as counterparty from %s", status)
                    .isEqualTo(OrderStatus.allowedFrom(status, true));
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
