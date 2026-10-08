package com.spotlink.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotlink.commodity.entity.CommodityCategory;
import com.spotlink.inventory.service.InventoryRules;
import com.spotlink.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class InventoryRulesTest {
    InventoryRules rules = new InventoryRules(new ObjectMapper());

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.0015", "1000000000000000"})
    void rejectsQuantityBeforeDatabaseCanRoundIt(String value) {
        assertThatThrownBy(() -> InventoryRules.quantity(new BigDecimal(value))).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "1.2300", "999999999999999.999"})
    void acceptsExactBoundaries(String value) {
        InventoryRules.quantity(new BigDecimal(value));
    }

    @Test void validatesRequiredTypeAndPercentageWhileKeepingUnknownChineseKeys() {
        var category = new CommodityCategory();
        category.setSpecSchema("""
            [{"key":"al_content","label":"铝含量","type":"number","unit":"%","required":true}]
            """);
        rules.spec(category, Map.of("al_content", new BigDecimal("99.7"), "质检批号", "验收一号"));
        for (var invalid : java.util.List.<Map<String, Object>>of(Map.of(), Map.of("al_content", "99.7"), Map.of("al_content", 101))) {
            assertThatThrownBy(() -> rules.spec(category, invalid)).isInstanceOf(BusinessException.class);
        }
    }
}
