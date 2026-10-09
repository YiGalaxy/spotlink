package com.spotlink.trading;

import com.spotlink.inventory.dto.InventoryRegisterRequest;
import com.spotlink.inventory.mapper.InventoryNoteMapper;
import com.spotlink.inventory.service.InventoryService;
import com.spotlink.inventory.entity.InventoryNote;
import com.spotlink.shared.security.LoginUser;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.trading.dto.ListingPublishRequest;
import com.spotlink.trading.dto.OrderAcceptRequest;
import com.spotlink.trading.entity.GoodsTransfer;
import com.spotlink.trading.entity.OrderStatus;
import com.spotlink.trading.mapper.GoodsTransferMapper;
import com.spotlink.trading.mapper.ListingMapper;
import com.spotlink.trading.service.ListingService;
import com.spotlink.trading.service.OrderService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
@SpringBootTest
@Transactional
class GoodsTransferIntegrationTest {
    @Autowired InventoryService inventory;
    @Autowired InventoryNoteMapper notes;
    @Autowired ListingService listings;
    @Autowired ListingMapper listingMapper;
    @Autowired OrderService orders;
    @Autowired GoodsTransferMapper transfers;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.apache.ibatis.session.SqlSession session;
    @Autowired com.spotlink.settlement.service.FreezeService freezes;

    private LoginUser actor(String username) {
        return LoginUser.builder().userId(username.equals("seller01") ? 1L : 2L).username(username)
                .enterpriseId(jdbc.queryForObject("SELECT enterprise_id FROM t_user WHERE username=?", Long.class, username)).build();
    }
    private InventoryNote source() {
        return inventory.register(new InventoryRegisterRequest(1003L, 2001L, "过户验收铝锭", "真实品牌", "真实产地",
                Map.of("al_content", new BigDecimal("99.7"), "批号", "转移保留"), new BigDecimal("100"), "吨", null),
                actor("seller01").getEnterpriseId());
    }
    private com.spotlink.trading.entity.Listing publish(InventoryNote source, String quantity) {
        return listings.publish(new ListingPublishRequest("SELL", source.getId(), null, null, null, null, null,
                new BigDecimal(quantity), null, new BigDecimal("68000.1234"), "FIXED", "AUTO", null, "SELF_PICKUP",
                null, OffsetDateTime.now().plusDays(1), null), actor("seller01"));
    }
    @Test void movesExactlyTheAcceptedQuantityIntoRestrictedStockWithRealAssociations() {
        var source = source();
        var listing = publish(source, "20");
        var order = orders.accept(listing.getId(), new OrderAcceptRequest(new BigDecimal("1.001"), null), actor("buyer01"));
        var transfer = transfers.findByOrder(order.getId());
        var target = notes.selectById(transfer.getTargetNoteId());
        assertThat(transfer.getSourceNoteId()).isEqualTo(source.getId());
        assertThat(transfer.getQuantity()).isEqualByComparingTo("1.001");
        assertThat(transfer.getStatus()).isEqualTo(GoodsTransfer.TRANSFERRED);
        assertThat(target.getStatus()).isEqualTo(InventoryNote.Status.PENDING_DELIVERY);
        assertThat(target.getAvailableQuantity()).isZero();
        assertThat(target.getFrozenQuantity()).isEqualByComparingTo("1.001");
        assertThat(target.getSpec()).contains("转移保留");
        assertThat(target.getBrand()).isEqualTo("真实品牌");
        assertThat(notes.selectById(source.getId()).getTotalQuantity().add(target.getTotalQuantity())).isEqualByComparingTo("100");
        assertThat(order.getAmount()).isEqualByComparingTo("68068.1235");
        assertThat(jdbc.queryForObject("SELECT biz_id FROM t_freeze_record WHERE id=?", Long.class, transfer.getTargetFreezeId())).isEqualTo(order.getId());
        assertThatThrownBy(() -> freezes.freezeInventory(order.getBuyerId(), target.getId(), BigDecimal.ONE, "LISTING", null, "再次交易"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> inventory.cancel(target.getId(), order.getBuyerId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> orders.cancel(order.getId(), "不能单方返货", actor("buyer01"))).isInstanceOf(BusinessException.class);
    }
    @Test void buyerReceiptReleasesRestrictedStockAndDuplicateReceiptDoesNotChangeQuantities() {
        var source = source();
        var order = orders.accept(publish(source, "3").getId(), new OrderAcceptRequest(new BigDecimal("3"), null), actor("buyer01"));
        var transfer = transfers.findByOrder(order.getId());
        jdbc.update("UPDATE t_order SET status=? WHERE id=?", OrderStatus.DELIVERING, order.getId());
        // JdbcTemplate 不经过 MyBatis 本地缓存；显式清掉测试中的旧快照。
        session.clearCache();
        orders.complete(order.getId(), actor("buyer01"));
        var target = notes.selectById(transfer.getTargetNoteId());
        assertThat(target.getStatus()).isEqualTo(InventoryNote.Status.IN_STOCK);
        assertThat(target.getFrozenQuantity()).isZero();
        assertThat(target.getAvailableQuantity()).isEqualByComparingTo("3");
        assertThat(transfers.findByOrder(order.getId()).getStatus()).isEqualTo(GoodsTransfer.DELIVERED);
        assertThatThrownBy(() -> orders.complete(order.getId(), actor("buyer01"))).isInstanceOf(BusinessException.class);
        assertThat(notes.selectById(target.getId()).getAvailableQuantity()).isEqualByComparingTo("3");
    }
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void downstreamDatabaseFailureRollsBackBothStocksFreezesAndOrder() {
        var source = source();
        var listing = publish(source, "10");
        try {
            assertThatThrownBy(() -> orders.accept(listing.getId(), new OrderAcceptRequest(new BigDecimal("6"), "x".repeat(513)), actor("buyer01")))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            var stock = notes.selectById(source.getId());
            assertThat(stock.getTotalQuantity()).isEqualByComparingTo("100");
            assertThat(stock.getFrozenQuantity()).isEqualByComparingTo("10");
            assertThat(listingMapper.selectById(listing.getId()).getRemainingQuantity()).isEqualByComparingTo("10");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_order WHERE listing_id=?", Long.class, listing.getId())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_inventory_note WHERE enterprise_id=? AND commodity_name=?", Long.class,
                    actor("buyer01").getEnterpriseId(), source.getCommodityName())).isZero();
        } finally { cleanup(listing.getId(), source.getId()); }
    }
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void competingAcceptancesCannotSellTheSameRemainingQuantity() throws Exception {
        var source = source();
        var listing = publish(source, "10");
        var buyer = actor("buyer01");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> attempt = () -> {
                start.await(10, TimeUnit.SECONDS);
                try { orders.accept(listing.getId(), new OrderAcceptRequest(new BigDecimal("6"), null), buyer); return true; }
                catch (BusinessException | org.springframework.dao.ConcurrencyFailureException expected) { return false; }
            };
            var first = pool.submit(attempt);
            var second = pool.submit(attempt);
            start.countDown();
            assertThat(java.util.List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
            assertThat(listingMapper.selectById(listing.getId()).getRemainingQuantity()).isEqualByComparingTo("4");
            assertThat(notes.selectById(source.getId()).getTotalQuantity()).isEqualByComparingTo("94");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_goods_transfer g JOIN t_order o ON o.id=g.order_id WHERE o.listing_id=?", Long.class, listing.getId())).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT SUM(n.total_quantity) FROM t_inventory_note n WHERE n.id=? OR n.id IN (SELECT g.target_note_id FROM t_goods_transfer g JOIN t_order o ON o.id=g.order_id WHERE o.listing_id=?)", BigDecimal.class, source.getId(), listing.getId())).isEqualByComparingTo("100");
        } finally { cleanup(listing.getId(), source.getId()); }
    }
    private void cleanup(Long listingId, Long sourceId) {
        var targets = jdbc.queryForList("SELECT target_note_id FROM t_goods_transfer WHERE source_note_id=?", Long.class, sourceId);
        jdbc.update("DELETE FROM t_goods_transfer WHERE source_note_id=?", sourceId);
        jdbc.update("DELETE s FROM t_order_status_log s JOIN t_order o ON o.id=s.order_id WHERE o.listing_id=?", listingId);
        jdbc.update("DELETE FROM t_order WHERE listing_id=?", listingId);
        jdbc.update("DELETE FROM t_listing WHERE id=?", listingId);
        for (Long target : targets) {
            jdbc.update("DELETE FROM t_freeze_record WHERE entity_id=? AND entity_type='INVENTORY'", target);
            jdbc.update("DELETE FROM t_inventory_note WHERE id=?", target);
        }
        jdbc.update("DELETE FROM t_freeze_record WHERE entity_id=? AND entity_type='INVENTORY'", sourceId);
        jdbc.update("DELETE FROM t_inventory_note WHERE id=? AND commodity_name='过户验收铝锭'", sourceId);
    }
}
