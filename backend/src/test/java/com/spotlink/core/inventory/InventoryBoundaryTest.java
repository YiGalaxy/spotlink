package com.spotlink.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotlink.inventory.dto.InventoryRegisterRequest;
import com.spotlink.inventory.dto.InventoryUpdateRequest;
import com.spotlink.inventory.service.InventoryService;
import com.spotlink.inventory.mapper.InventoryNoteMapper;
import com.spotlink.settlement.service.FreezeService;
import com.spotlink.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.util.Map;
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

/** 每个用例事务回滚；只使用 compose.test 的数据库，验证真实约束和服务事务。 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InventoryBoundaryTest {
    @Autowired InventoryService inventory;
    @Autowired InventoryNoteMapper notes;
    @Autowired FreezeService freezes;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private Long owner() {
        return jdbc.queryForObject("SELECT enterprise_id FROM t_user WHERE username='seller01'", Long.class);
    }

    private InventoryRegisterRequest request(Long category, Long warehouse, String quantity, String unit, Map<String, Object> spec) {
        return new InventoryRegisterRequest(category, warehouse, "库存边界验收", "验收品牌", "测试产地", spec,
                new BigDecimal(quantity), unit, "保留备注");
    }

    private com.spotlink.inventory.entity.InventoryNote register() {
        return inventory.register(request(1003L, 2001L, "100.001", null,
                Map.of("al_content", new BigDecimal("99.7"), "质检批号", "中文扩展")), owner());
    }

    @Test void validRegistrationKeepsPrecisionDefaultUnitAndExtendedSpec() {
        var note = register();
        var stored = notes.selectById(note.getId());
        assertThat(stored.getTotalQuantity()).isEqualByComparingTo("100.001");
        assertThat(stored.getAvailableQuantity()).isEqualByComparingTo(stored.getTotalQuantity());
        assertThat(stored.getFrozenQuantity()).isZero();
        assertThat(stored.getUnit()).isEqualTo("吨");
        assertThat(stored.getSpec()).contains("质检批号", "中文扩展");
    }

    @Test void rejectsInvalidRelationsUnitsAndDisabledRecordsWithoutInserting() {
        long before = notes.selectCount(null);
        for (var bad : java.util.List.of(
                request(1001L, 2001L, "1", "吨", Map.of()),
                request(1003L, 99999L, "1", "吨", Map.of("al_content", 99)),
                request(1003L, 2001L, "1", "千克", Map.of("al_content", 99)),
                request(1003L, 2001L, "0.0015", "吨", Map.of("al_content", 99)),
                request(1003L, 2001L, "1", "吨", Map.of()))) {
            assertThatThrownBy(() -> inventory.register(bad, owner())).isInstanceOf(BusinessException.class);
        }
        jdbc.update("UPDATE t_warehouse SET status=0 WHERE id=2001");
        assertThatThrownBy(this::register).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE t_warehouse SET status=1 WHERE id=2001");
        jdbc.update("UPDATE t_commodity_category SET status=0 WHERE id=1003");
        assertThatThrownBy(this::register).isInstanceOf(BusinessException.class);
        assertThat(notes.selectCount(null)).isEqualTo(before);
    }

    @Test void frozenIdentityAndCancellationAreBlockedButRemarkCanBeCorrected() {
        var note = register();
        var freeze = freezes.freezeInventory(owner(), note.getId(), new BigDecimal("30.001"), "LISTING", null, "边界测试");
        var unchanged = new InventoryUpdateRequest(1003L, note.getCommodityName(), note.getBrand(), note.getOrigin(),
                Map.of("al_content", new BigDecimal("99.7"), "质检批号", "中文扩展"), "新的备注");
        inventory.update(note.getId(), unchanged, owner());
        assertThat(notes.selectById(note.getId()).getRemark()).isEqualTo("新的备注");
        assertThatThrownBy(() -> inventory.cancel(note.getId(), owner())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> inventory.update(note.getId(), new InventoryUpdateRequest(1003L, "另一批货", note.getBrand(),
                note.getOrigin(), unchanged.spec(), "备注"), owner())).isInstanceOf(BusinessException.class);
        freezes.releaseInventory(owner(), freeze.getId());
        assertThat(notes.selectById(note.getId()).getAvailableQuantity()).isEqualByComparingTo("100.001");
        inventory.cancel(note.getId(), owner());
        assertThat(notes.selectById(note.getId()).getStatus()).isEqualTo(6);
    }

    @Test void partialConsumptionThenReleasePreservesBalanceAndCannotRepeat() {
        var note = register();
        var freeze = freezes.freezeInventory(owner(), note.getId(), new BigDecimal("30.001"), "LISTING", null, "边界测试");
        Long remainder = freezes.consumeInventoryPartial(owner(), freeze.getId(), new BigDecimal("10.001"));
        var stored = notes.selectById(note.getId());
        assertThat(stored.getTotalQuantity()).isEqualByComparingTo("90");
        assertThat(stored.getFrozenQuantity()).isEqualByComparingTo("20");
        assertThat(stored.getAvailableQuantity()).isEqualByComparingTo("70");
        assertThatThrownBy(() -> freezes.releaseInventory(owner(), freeze.getId())).isInstanceOf(BusinessException.class);
        freezes.releaseInventory(owner(), remainder);
        stored = notes.selectById(note.getId());
        assertThat(stored.getAvailableQuantity()).isEqualByComparingTo("90");
        assertThat(stored.getFrozenQuantity()).isZero();
    }

    @Test void sameCategoryEditPreservesExtensionKeysAndCanClearOptionalText() {
        var note = register();
        inventory.update(note.getId(), new InventoryUpdateRequest(1003L, note.getCommodityName(), null, null,
                Map.of("al_content", new BigDecimal("99.7")), null), owner());
        var stored = notes.selectById(note.getId());
        assertThat(stored.getBrand()).isNull();
        assertThat(stored.getOrigin()).isNull();
        assertThat(stored.getRemark()).isNull();
        assertThat(stored.getSpec()).contains("质检批号", "中文扩展");
        assertThat(stored.getTotalQuantity()).isEqualByComparingTo(note.getTotalQuantity());
        assertThatThrownBy(() -> inventory.update(note.getId(), new InventoryUpdateRequest(1003L,
                note.getCommodityName(), null, null, Map.of(), "缺少必填规格"), owner())).isInstanceOf(BusinessException.class);
    }

    @Test void staleFormCannotOverwriteLaterEditOrChangedFreezeVersion() {
        var note = register();
        int readVersion = note.getVersion();
        inventory.update(note.getId(), new InventoryUpdateRequest(1003L, note.getCommodityName(), note.getBrand(), note.getOrigin(),
                Map.of("al_content", new BigDecimal("99.7")), "同事的新备注", readVersion), owner());
        assertThatThrownBy(() -> inventory.update(note.getId(), new InventoryUpdateRequest(1003L, note.getCommodityName(),
                note.getBrand(), note.getOrigin(), Map.of("al_content", new BigDecimal("99.7")), "旧表单", readVersion), owner()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("刷新");
        assertThat(notes.selectById(note.getId()).getRemark()).isEqualTo("同事的新备注");
    }

    @Test void remarkOnlyEditWorksForFrozenInventoryOfDisabledCategory() {
        var note = register();
        freezes.freezeInventory(owner(), note.getId(), new BigDecimal("1"), "LISTING", null, "备注测试");
        jdbc.update("UPDATE t_commodity_category SET status=0 WHERE id=1003");
        inventory.update(note.getId(), new InventoryUpdateRequest(1003L, note.getCommodityName(), note.getBrand(), note.getOrigin(),
                Map.of("al_content", new BigDecimal("99.7")), "停用品类仍可修改备注"), owner());
        assertThat(notes.selectById(note.getId()).getRemark()).isEqualTo("停用品类仍可修改备注");
        assertThat(notes.selectById(note.getId()).getSpec()).contains("质检批号");
    }

    @Test void databaseRejectsUnbalancedNegativeAndMissingCategoryRows() {
        var note = register();
        assertThatThrownBy(() -> jdbc.update("UPDATE t_inventory_note SET available_quantity=available_quantity+1 WHERE id=?", note.getId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .rootCause().hasMessageContaining("ck_inventory_quantity_balance");
        assertThatThrownBy(() -> jdbc.update("UPDATE t_inventory_note SET available_quantity=-1, frozen_quantity=101.001 WHERE id=?", note.getId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .rootCause().hasMessageContaining("ck_inventory_quantity_non_negative");
        assertThatThrownBy(() -> jdbc.update("UPDATE t_inventory_note SET category_id=99999 WHERE id=?", note.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE t_inventory_note SET warehouse_id=99999 WHERE id=?", note.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void publicSchemaAndAuthenticatedRegistrationUseExactDecimalStrings() throws Exception {
        mvc.perform(get("/api/categories/tree")).andExpect(jsonPath("$.data[0].children[0].specSchema[0].key").value("cu_content"));
        var login = mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"seller01\",\"password\":\"Admin@123\"}")).andReturn();
        String token = json.readTree(login.getResponse().getContentAsString()).path("data").path("accessToken").asText();
        var payload = request(1003L, 2001L, "999999999999999.999", "吨", Map.of("al_content", 99));
        mvc.perform(post("/api/inventory-notes").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(json.writeValueAsString(payload)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.totalQuantity").value("999999999999999.999"));
        mvc.perform(post("/api/inventory-notes").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(json.writeValueAsString(request(1003L, 2001L, "0.0015", "吨", Map.of("al_content", 99)))))
                .andExpect(jsonPath("$.code").value(10000));
    }
}
