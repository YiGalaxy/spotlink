package com.spotlink.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotlink.inventory.dto.InventoryRegisterRequest;
import com.spotlink.inventory.mapper.InventoryNoteMapper;
import com.spotlink.inventory.service.InventoryService;
import com.spotlink.settlement.mapper.FreezeRecordMapper;
import com.spotlink.settlement.service.FreezeService;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.security.LoginUser;
import com.spotlink.trading.dto.ListingPublishRequest;
import com.spotlink.trading.entity.Listing;
import com.spotlink.trading.mapper.ListingMapper;
import com.spotlink.trading.service.ListingService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

/** 真实库存、冻结、挂牌同库验证；默认回滚，故障用例只清理自己创建的记录。 */
@Tag("integration")
@SpringBootTest
@Transactional
class ListingBoundaryTest {
    @Autowired ListingService listings;
    @Autowired InventoryService inventory;
    @Autowired InventoryNoteMapper notes;
    @Autowired FreezeRecordMapper freezes;
    @Autowired ListingMapper mapper;
    @Autowired FreezeService freezeService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SqlSession session;
    @Autowired com.spotlink.trading.service.TradingViewAssembler assembler;

    private Long owner() { return jdbc.queryForObject("SELECT enterprise_id FROM t_user WHERE username='seller01'", Long.class); }
    private LoginUser actor() { return LoginUser.builder().userId(1L).username("挂牌验收").enterpriseId(owner()).build(); }
    private com.spotlink.inventory.entity.InventoryNote note() {
        return inventory.register(new InventoryRegisterRequest(1003L, 2001L, "可信铝锭库存", "库存品牌", "库存产地",
                Map.of("al_content", new BigDecimal("99.7"), "质检批号", "中文扩展值"), new BigDecimal("100.001"), "吨", null), owner());
    }
    private ListingPublishRequest request(Long note, String quantity, String price, String priceType, String mode) {
        // 故意提交伪造的物理属性，SELL 应忽略这些字段并继承真实库存。
        return new ListingPublishRequest("SELL", note, 1007L, "伪造名称", "伪造品牌", "伪造产地",
                Map.of("伪造", true), new BigDecimal(quantity), "千克", price == null ? null : new BigDecimal(price),
                priceType, mode, 2002L, "SELF_PICKUP", null, OffsetDateTime.now().plusDays(2), null);
    }

    @Test void inheritsAllPhysicalAttributesAndReservesOnlyPublishedQuantity() throws Exception {
        var source = note();
        var listing = listings.publish(request(source.getId(), "20.001", "68000.1234", "FIXED", "MANUAL"), actor());
        var stored = mapper.selectById(listing.getId());
        assertThat(stored.getCategoryId()).isEqualTo(source.getCategoryId());
        assertThat(stored.getCommodityName()).isEqualTo(source.getCommodityName());
        assertThat(stored.getBrand()).isEqualTo(source.getBrand());
        assertThat(stored.getOrigin()).isEqualTo(source.getOrigin());
        assertThat(stored.getUnit()).isEqualTo("吨");
        assertThat(stored.getWarehouseId()).isEqualTo(source.getWarehouseId());
        assertThat(json.readTree(stored.getSpec())).isEqualTo(json.readTree(source.getSpec()));
        assertThat(stored.getPrice()).isEqualByComparingTo("68000.1234");
        assertThat(stored.getConfirmMode()).isEqualTo("MANUAL");
        var view = assembler.toListingViews(java.util.List.of(stored), owner()).getFirst();
        assertThat(view.spec()).containsEntry("质检批号", "中文扩展值");
        var wire = json.readTree(json.writeValueAsString(view));
        assertThat(wire.path("quantity").isTextual()).isTrue();
        assertThat(wire.path("price").asText()).isEqualTo("68000.1234");
        var stock = notes.selectById(source.getId());
        assertThat(stock.getTotalQuantity()).isEqualByComparingTo("100.001");
        assertThat(stock.getAvailableQuantity()).isEqualByComparingTo("80");
        assertThat(stock.getFrozenQuantity()).isEqualByComparingTo("20.001");
        var freeze = freezes.selectById(stored.getFreezeId());
        assertThat(freeze.getEntityId()).isEqualTo(source.getId());
        assertThat(freeze.getBizId()).isEqualTo(stored.getId());
        listings.close(stored.getId(), owner());
        stock = notes.selectById(source.getId());
        assertThat(stock.getAvailableQuantity()).isEqualByComparingTo("100.001");
        assertThat(stock.getFrozenQuantity()).isZero();
    }

    @Test void defaultsAutoAndDoesNotCarryStalePriceOnNegotiableListing() {
        var source = note();
        var listing = listings.publish(request(source.getId(), "1", "-1", "NEGOTIABLE", null), actor());
        assertThat(listing.getPrice()).isNull();
        assertThat(listing.getConfirmMode()).isEqualTo("AUTO");
    }

    @Test void rejectsQuantityAndFixedPriceBeforeAnyFreezeIncludingInferredPriceType() {
        var source = note();
        for (String quantity : java.util.List.of("0", "-1", "0.0015", "1000000000000000", "100.002")) {
            assertThatThrownBy(() -> listings.publish(request(source.getId(), quantity, "1", "FIXED", null), actor()))
                    .isInstanceOf(BusinessException.class);
        }
        for (String price : java.util.List.of("0", "-1", "1.00001", "1000000000000000")) {
            assertThatThrownBy(() -> listings.publish(request(source.getId(), "1", price, null, null), actor()))
                    .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> listings.publish(request(source.getId(), "1", null, "FIXED", null), actor()))
                .isInstanceOf(BusinessException.class);
        assertThat(notes.selectById(source.getId()).getFrozenQuantity()).isZero();
    }

    @Test void rejectsForeignUnavailableAndDisabledSources() {
        var source = note();
        var other = LoginUser.builder().userId(2L).enterpriseId(owner() + 1234).build();
        assertThatThrownBy(() -> listings.publish(request(source.getId(), "1", "1", "FIXED", null), other))
                .isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE t_inventory_note SET status=6 WHERE id=?", source.getId());
        session.clearCache();
        assertThatThrownBy(() -> listings.publish(request(source.getId(), "1", "1", "FIXED", null), actor()))
                .isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE t_inventory_note SET status=2 WHERE id=?", source.getId());
        jdbc.update("UPDATE t_warehouse SET status=0 WHERE id=2001");
        session.clearCache();
        assertThatThrownBy(() -> listings.publish(request(source.getId(), "1", "1", "FIXED", null), actor()))
                .isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE t_warehouse SET status=1 WHERE id=2001");
        jdbc.update("UPDATE t_commodity_category SET status=0 WHERE id=1003");
        session.clearCache();
        assertThatThrownBy(() -> listings.publish(request(source.getId(), "1", "1", "FIXED", null), actor()))
                .isInstanceOf(BusinessException.class);
    }

    @Test void refusesStaleInventorySnapshotAndDoesNotReserveEditedGoods() {
        var source = note();
        jdbc.update("UPDATE t_inventory_note SET commodity_name='更新后的名称', version=version+1 WHERE id=?", source.getId());
        session.clearCache();
        assertThatThrownBy(() -> freezeService.freezeInventory(owner(), source.getId(), BigDecimal.ONE,
                "LISTING", null, "版本测试", source.getVersion())).isInstanceOf(BusinessException.class);
        assertThat(notes.selectById(source.getId()).getFrozenQuantity()).isZero();
    }

    @Test void failedReleaseDoesNotCloseListingOrHideStockConflict() {
        var source = note();
        var listing = listings.publish(request(source.getId(), "1", "1", "FIXED", null), actor());
        jdbc.update("UPDATE t_freeze_record SET status='RELEASED' WHERE id=?", listing.getFreezeId());
        session.clearCache();
        assertThatThrownBy(() -> listings.close(listing.getId(), owner())).isInstanceOf(BusinessException.class);
        assertThat(mapper.selectById(listing.getId()).getStatus()).isEqualTo(Listing.Status.OPEN);
        assertThat(notes.selectById(source.getId()).getFrozenQuantity()).isEqualByComparingTo("1");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void databaseFailureAfterFreezingRollsBackStockAndReservation() {
        var source = note();
        long freezeCount = freezes.selectCount(null);
        var valid = request(source.getId(), "1", "1", "FIXED", null);
        var bad = new ListingPublishRequest(valid.side(), valid.inventoryNoteId(), null, null, null, null, null,
                valid.quantity(), null, valid.price(), valid.priceType(), null, null, valid.deliveryMethod(), null,
                valid.validUntil(), "x".repeat(513));
        try {
            assertThatThrownBy(() -> listings.publish(bad, actor())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(notes.selectById(source.getId()).getFrozenQuantity()).isZero();
            assertThat(notes.selectById(source.getId()).getAvailableQuantity()).isEqualByComparingTo("100.001");
            assertThat(freezes.selectCount(null)).isEqualTo(freezeCount);
        } finally {
            jdbc.update("DELETE FROM t_inventory_note WHERE id=? AND commodity_name='可信铝锭库存'", source.getId());
        }
    }
}
