package com.spotlink.trading;

import com.spotlink.inventory.dto.InventoryRegisterRequest;
import com.spotlink.inventory.mapper.InventoryNoteMapper;
import com.spotlink.inventory.service.InventoryService;
import com.spotlink.settlement.mapper.FreezeRecordMapper;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.security.LoginUser;
import com.spotlink.trading.dto.ListingPublishRequest;
import com.spotlink.trading.dto.OrderAcceptRequest;
import com.spotlink.trading.mapper.GoodsTransferMapper;
import com.spotlink.trading.mapper.ListingMapper;
import com.spotlink.trading.mapper.OrderMapper;
import com.spotlink.trading.service.ListingService;
import com.spotlink.trading.service.OrderService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
@SpringBootTest
@Transactional
class ManualReservationIntegrationTest {
    @Autowired InventoryService inventory;
    @Autowired InventoryNoteMapper notes;
    @Autowired FreezeRecordMapper freezes;
    @Autowired ListingService listings;
    @Autowired ListingMapper listingMapper;
    @Autowired OrderService orders;
    @Autowired OrderMapper orderMapper;
    @Autowired GoodsTransferMapper transfers;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.apache.ibatis.session.SqlSession session;

    private LoginUser actor(String username) {
        return LoginUser.builder().userId(username.equals("seller01") ? 1L : 2L).username(username)
                .enterpriseId(jdbc.queryForObject("SELECT enterprise_id FROM t_user WHERE username=?", Long.class, username)).build();
    }
    private com.spotlink.trading.entity.Listing listing() {
        var source = inventory.register(new InventoryRegisterRequest(1003L, 2001L, "MANUAL独立预留验收", null, null,
                Map.of("al_content", new BigDecimal("99.7")), new BigDecimal("100"), "吨", null), actor("seller01").getEnterpriseId());
        return listings.publish(new ListingPublishRequest("SELL", source.getId(), null, null, null, null, null,
                new BigDecimal("20"), null, new BigDecimal("68000"), "FIXED", "MANUAL", null, "SELF_PICKUP", null,
                OffsetDateTime.now().plusDays(1), null), actor("seller01"));
    }
    private com.spotlink.trading.entity.Order accept(Long id, String quantity) {
        return orders.accept(id, new OrderAcceptRequest(new BigDecimal(quantity), null), actor("buyer01"));
    }
    @Test void eachPendingOrderOwnsItsReservationAndConfirmationDoesNotConsumeListingRemainder() {
        var listing = listing();
        var first = accept(listing.getId(), "8");
        var second = accept(listing.getId(), "6");
        var remaining = listingMapper.selectById(listing.getId());
        assertThat(first.getGoodsFreezeId()).isNotEqualTo(second.getGoodsFreezeId()).isNotEqualTo(remaining.getFreezeId());
        assertThat(freezes.selectById(first.getGoodsFreezeId()).getQuantity()).isEqualByComparingTo("8");
        assertThat(freezes.selectById(first.getGoodsFreezeId()).getBizId()).isEqualTo(first.getId());
        assertThat(freezes.selectById(second.getGoodsFreezeId()).getBizType()).isEqualTo("ORDER");
        assertThat(transfers.findByOrder(first.getId())).isNull();
        Long source = freezes.selectById(first.getGoodsFreezeId()).getEntityId();
        assertThat(notes.selectById(source).getTotalQuantity()).isEqualByComparingTo("100");
        orders.confirm(second.getId(), actor("seller01"));
        assertThat(transfers.findByOrder(second.getId()).getSourceFreezeId()).isEqualTo(second.getGoodsFreezeId());
        assertThat(notes.selectById(source).getTotalQuantity()).isEqualByComparingTo("94");
        assertThat(freezes.selectById(remaining.getFreezeId()).getQuantity()).isEqualByComparingTo("6");
        assertThat(freezes.selectById(first.getGoodsFreezeId()).getStatus()).isEqualTo("FROZEN");
        assertThatThrownBy(() -> orders.confirm(second.getId(), actor("seller01"))).isInstanceOf(BusinessException.class);
    }
    @Test void onlyListerCanConfirmAndDeadlineIsCheckedAtConfirmation() {
        var pending = accept(listing().getId(), "8");
        assertThatThrownBy(() -> orders.confirm(pending.getId(), actor("buyer01"))).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE t_order SET confirm_deadline=? WHERE id=?", java.time.LocalDateTime.now().minusMinutes(1), pending.getId());
        session.clearCache();
        assertThatThrownBy(() -> orders.confirm(pending.getId(), actor("seller01"))).isInstanceOf(BusinessException.class);
        assertThat(orderMapper.selectById(pending.getId()).getStatus()).isEqualTo("PENDING_CONFIRM");
        assertThat(transfers.findByOrder(pending.getId())).isNull();
        assertThat(freezes.selectById(pending.getGoodsFreezeId()).getStatus()).isEqualTo("FROZEN");
    }
    @Test void cancellingOnePendingOrderDoesNotReleaseAnotherOrdersGoods() {
        var listing = listing();
        var first = accept(listing.getId(), "8");
        var second = accept(listing.getId(), "6");
        orders.cancel(first.getId(), "撤回摘牌", actor("buyer01"));
        assertThat(freezes.selectById(first.getGoodsFreezeId()).getStatus()).isEqualTo("RELEASED");
        assertThat(freezes.selectById(second.getGoodsFreezeId()).getStatus()).isEqualTo("FROZEN");
        var restored = listingMapper.selectById(listing.getId());
        assertThat(restored.getRemainingQuantity()).isEqualByComparingTo("14");
        assertThat(freezes.selectById(restored.getFreezeId()).getQuantity()).isEqualByComparingTo("14");
        orders.confirm(second.getId(), actor("seller01"));
        assertThat(transfers.findByOrder(second.getId()).getQuantity()).isEqualByComparingTo("6");
    }
    @Test void acceptingAllQuantityStillLeavesAnIndependentOrderThatCanBeConfirmed() {
        var listing = listing();
        var pending = accept(listing.getId(), "20");
        assertThat(listingMapper.selectById(listing.getId()).getFreezeId()).isNull();
        orders.confirm(pending.getId(), actor("seller01"));
        assertThat(transfers.findByOrder(pending.getId()).getQuantity()).isEqualByComparingTo("20");
    }
}
