package com.spotlink.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotlink.commodity.entity.CommodityCategory;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.web.ResultCode;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

/** 库存数量和品类规格的共同约束；业务入口在数据库舍入前拒绝非法输入。 */
@Component
@RequiredArgsConstructor
public class InventoryRules {
    private final ObjectMapper json;

    public static void quantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0
                || quantity.stripTrailingZeros().scale() > 3
                || quantity.compareTo(new BigDecimal("999999999999999.999")) > 0) {
            reject("数量须大于 0，最多 15 位整数和 3 位小数");
        }
    }

    public void spec(CommodityCategory category, Map<String, Object> values) {
        var spec = values == null ? Map.<String, Object>of() : values;
        try {
            var schema = json.readTree(category.getSpecSchema());
            if (!schema.isArray()) throw new IllegalArgumentException("品类规格定义应为数组");
            for (var field : schema) {
                String key = field.path("key").asText();
                String label = field.path("label").asText(key);
                Object value = spec.get(key);
                if (value == null || value instanceof String text && text.isBlank()) {
                    if (field.path("required").asBoolean()) reject("请填写" + label);
                    continue;
                }
                switch (field.path("type").asText()) {
                    case "number" -> {
                        if (!(value instanceof Number)) reject(label + "必须是数字");
                        var number = new BigDecimal(value.toString());
                        if ("%".equals(field.path("unit").asText())
                                && (number.signum() < 0 || number.compareTo(new BigDecimal("100")) > 0)) {
                            reject(label + "须在 0 到 100 之间");
                        }
                    }
                    case "string" -> {
                        if (!(value instanceof String text) || text.length() > 256) reject(label + "须为不超过 256 字的文本");
                    }
                    default -> throw new IllegalArgumentException("不支持的品类规格类型");
                }
            }
            // 扩展规格可能由导入或旧版本写入，保留未知键；已定义字段仍严格校验。
            if (json.writeValueAsBytes(spec).length > 16384) reject("规格参数不得超过 16 KB");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "品类规格定义或参数格式不正确");
        }
    }

    private static void reject(String message) {
        throw BusinessException.of(ResultCode.BAD_REQUEST, message);
    }
}
