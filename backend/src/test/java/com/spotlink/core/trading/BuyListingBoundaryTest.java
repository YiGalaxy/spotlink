package com.spotlink.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotlink.inventory.dto.InventoryRegisterRequest;
import com.spotlink.inventory.entity.InventoryNote;
import com.spotlink.inventory.mapper.InventoryNoteMapper;
import com.spotlink.inventory.service.InventoryService;
import com.spotlink.settlement.mapper.FreezeRecordMapper;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.security.LoginUser;
import com.spotlink.trading.dto.ListingPublishRequest;
import com.spotlink.trading.dto.OrderAcceptRequest;
import com.spotlink.trading.entity.Listing;
import com.spotlink.trading.service.InventoryMatchService;
import com.spotlink.trading.service.ListingService;
import com.spotlink.trading.service.OrderService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BuyListingBoundaryTest {
    @Autowired ListingService listings;
    @Autowired InventoryMatchService matching;
    @Autowired InventoryService inventory;
    @Autowired InventoryNoteMapper notes;
    @Autowired OrderService orders;
    @Autowired FreezeRecordMapper freezes;
    @Autowired JdbcTemplate jdbc;
    @Autowired SqlSession session;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private Long tenant(String username) { return jdbc.queryForObject("SELECT enterprise_id FROM t_user WHERE username=?", Long.class, username); }
    private Long seller() { return tenant("seller01"); }
    private Long buyer() { return tenant("buyer01"); }
    private LoginUser actor(Long enterprise) { return LoginUser.builder().enterpriseId(enterprise).userId(1L).username("BUY验收").build(); }
    private ListingPublishRequest request(String priceType, String unit, Map<String, Object> spec, Long warehouse) {
        return new ListingPublishRequest("BUY", null, 1003L, "采购铝锭", null, null, spec, new BigDecimal("20.001"), unit,
                "FIXED".equals(priceType) ? new BigDecimal("68000.1234") : null, priceType, "AUTO", warehouse, "SELF_PICKUP", null,
                OffsetDateTime.now().plusDays(1), null);
    }
    private Listing listing(Long warehouse) {
        return listings.publish(request("FIXED", null, Map.of("al_content", new BigDecimal("99.700")), warehouse), actor(buyer()));
    }
    private InventoryNote stock(Long owner, Long warehouse, String purity, String quantity) {
        return inventory.register(new InventoryRegisterRequest(1003L, warehouse, "实际交付铝锭", "实际品牌", "实际产地",
                Map.of("al_content", new BigDecimal(purity), "质检批号", "原批次中文键"), new BigDecimal(quantity), "吨", null), owner);
    }

    @Test void publishesFixedAndNegotiableWithCategorySchemaAndUnit() {
        var fixed = listing(null);
        assertThat(fixed.getUnit()).isEqualTo("吨");
        assertThat(fixed.getPrice()).isEqualByComparingTo("68000.1234");
        assertThat(fixed.getFreezeId()).isNull();
        var negotiated = listings.publish(request("NEGOTIABLE", "吨", Map.of("al_content", 99.7), null), actor(buyer()));
        assertThat(negotiated.getPrice()).isNull();
        for (var bad : java.util.List.of(request("FIXED", "千克", Map.of("al_content", 99.7), null),
                request("FIXED", "吨", Map.of(), null), request("FIXED", "吨", Map.of("al_content", "99.7"), null))) {
            assertThatThrownBy(() -> listings.publish(bad, actor(buyer()))).isInstanceOf(BusinessException.class);
        }
    }

    @Test void candidatesMatchQuantityUnitWarehouseSpecAndTenant() {
        var wanted = listing(2001L);
        var right = stock(seller(), 2001L, "99.7", "10.001");
        stock(seller(), 2002L, "99.7", "10.001");
        stock(seller(), 2001L, "99.8", "10.001");
        stock(seller(), 2001L, "99.7", "0.001");
        stock(buyer(), 2001L, "99.7", "10.001");
        var wrongUnit = stock(seller(), 2001L, "99.7", "10.001");
        jdbc.update("UPDATE t_inventory_note SET unit='千克' WHERE id=?", wrongUnit.getId());
        session.clearCache();
        assertThat(matching.candidates(wanted.getId(), new BigDecimal("10.001"), seller()))
                .extracting(InventoryNote::getId).containsExactly(right.getId());
    }

    @Test void requiresExplicitSourceAndRejectsForgedSelectionBeforeChangingStock() {
        var wanted = listing(2001L);
        var other = stock(buyer(), 2001L, "99.7", "10.001");
        var wrong = stock(seller(), 2002L, "99.7", "10.001");
        for (Long id : new Long[] { null, other.getId(), wrong.getId() }) {
            assertThatThrownBy(() -> orders.accept(wanted.getId(), new OrderAcceptRequest(BigDecimal.ONE, null, id), actor(seller())))
                    .isInstanceOf(BusinessException.class);
        }
        assertThat(notes.selectById(wrong.getId()).getAvailableQuantity()).isEqualByComparingTo("10.001");
        assertThat(notes.selectById(other.getId()).getAvailableQuantity()).isEqualByComparingTo("10.001");
    }

    @Test void unrestrictedWarehouseUsesActualSourceAndPreservesFullSpec() {
        var wanted = listing(null);
        var source = stock(seller(), 2002L, "99.7", "30.001");
        var order = orders.accept(wanted.getId(), new OrderAcceptRequest(new BigDecimal("10.001"), null, source.getId()), actor(seller()));
        assertThat(order.getWarehouseId()).isEqualTo(2002L);
        assertThat(order.getCommodityName()).isEqualTo(source.getCommodityName());
        assertThat(order.getSpec()).contains("原批次中文键");
        assertThat(notes.selectById(source.getId()).getTotalQuantity()).isEqualByComparingTo("20");
        var received = notes.findOwned(buyer(), null).stream().filter(note -> note.getRemark() != null && note.getRemark().endsWith(source.getNoteNo())).findFirst().orElseThrow();
        assertThat(received.getWarehouseId()).isEqualTo(2002L);
        assertThat(received.getBrand()).isEqualTo("实际品牌");
        assertThat(received.getOrigin()).isEqualTo("实际产地");
        assertThat(received.getSpec()).contains("原批次中文键");
        assertThat(received.getTotalQuantity()).isEqualByComparingTo("10.001");
        var freeze = freezes.selectById(order.getGoodsFreezeId());
        assertThat(freeze.getEntityId()).isEqualTo(source.getId());
        assertThat(freeze.getBizId()).isEqualTo(order.getId());
    }

    @Test void expiredOrDisabledSourceCannotBeDeliveredEvenIfItWasPreviouslyShown() {
        var wanted = listing(2001L);
        var source = stock(seller(), 2001L, "99.7", "30");
        jdbc.update("UPDATE t_warehouse SET status=0 WHERE id=2001");
        session.clearCache();
        assertThat(matching.candidates(wanted.getId(), BigDecimal.ONE, seller())).isEmpty();
        assertThatThrownBy(() -> orders.accept(wanted.getId(), new OrderAcceptRequest(BigDecimal.ONE, null, source.getId()), actor(seller())))
                .isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE t_warehouse SET status=1 WHERE id=2001");
        jdbc.update("UPDATE t_listing SET valid_until=? WHERE id=?", java.time.LocalDateTime.now().minusDays(1), wanted.getId());
        session.clearCache();
        assertThatThrownBy(() -> matching.candidates(wanted.getId(), BigDecimal.ONE, seller())).isInstanceOf(BusinessException.class);
    }

    @Test void matchingEndpointOnlyReturnsTheAuthenticatedSellersOwnInventory() throws Exception {
        var wanted = listing(2001L);
        var source = stock(seller(), 2001L, "99.7", "10.001");
        stock(buyer(), 2001L, "99.7", "10.001");
        String token = json.readTree(mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"seller01\",\"password\":\"Admin@123\"}")).andReturn().getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
        mvc.perform(get("/api/listings/" + wanted.getId() + "/matching-inventory").param("quantity", "10.001")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(source.getId().toString()));
        mvc.perform(get("/api/listings/" + wanted.getId() + "/matching-inventory").param("quantity", "10.001"))
                .andExpect(status().isUnauthorized());
    }
}
