package com.spotlink.advisor.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotlink.advisor.dto.AdvisorProductReference;
import com.spotlink.identity.entity.Enterprise;
import com.spotlink.identity.mapper.EnterpriseMapper;
import com.spotlink.trading.entity.Listing;
import com.spotlink.trading.mapper.ListingMapper;
import com.spotlink.warehouse.entity.Warehouse;
import com.spotlink.warehouse.mapper.WarehouseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/** 采购检索只读取未过期的公开挂牌。筛选及排序在数据库执行，模型不拥有 SQL 接口。 */
@Component
@RequiredArgsConstructor
public class ProcurementAdvisorTools {
    private final ListingMapper listings;
    private final WarehouseMapper warehouses;
    private final EnterpriseMapper enterprises;
    private final ObjectMapper json;

    @Tool(name = "get_listing_details", description = "按挂牌编号查询有效公开挂牌详情：规格、品牌产地、单价、余量、卖家、仓库、交付、付款条款、有效期和查看入口。用于对指定挂牌追问，不读取撤牌或过期的私有记录。")
    public String getListingDetails(@ToolParam(description = "挂牌编号，如LS开头的完整编号，不是页面URL里的数字ID") String listingNo) {
        if (listingNo == null || listingNo.isBlank() || listingNo.length() > 80) return "请提供有效的完整挂牌编号。";
        List<Listing> found = listings.selectList(publicQuery().eq(Listing::getListingNo, listingNo.trim()).last("LIMIT 1"));
        return render(found, found.size(), "指定有效公开挂牌详情");
    }

    @Tool(name = "find_purchase_options", description = "查询在售货物或比价。可按商品、仓库省市、余量、单价和交付方式筛选，按价格或余量排序。返回真实商品卡片与详情路径。仓库位置是货物交收地，不是卖家公司注册地址；单价不代表含运费。不同单位不可直接比较。")
    public String findPurchaseOptions(
            @ToolParam(required = false, description = "商品名称关键字，如电解铜；留空查询全部") String keyword,
            @ToolParam(required = false, description = "起运仓库省市关键字，不是目的地；可选") String location,
            @ToolParam(required = false, description = "最低剩余数量，可选；筛数量时同时提供 unit") BigDecimal minQuantity,
            @ToolParam(required = false, description = "最高挂牌单价，可选；筛价格时同时提供 unit") BigDecimal maxPrice,
            @ToolParam(required = false, description = "数量及单价的单位，如吨；比较时要保持相同单位") String unit,
            @ToolParam(required = false, description = "SELF_PICKUP 自提、DELIVERED 送到；留空不限") String deliveryMethod,
            @ToolParam(required = false, description = "PRICE_ASC 单价升序、QUANTITY_DESC 余量降序、LATEST 最新；默认 PRICE_ASC") String sortBy) {
        if (!validText(keyword) || !validText(location) || !validText(unit)) return "商品、地点或单位不能超过80个字符。";
        if (!validNumber(minQuantity) || !validNumber(maxPrice)) return "数量或单价必须在0到1000000000之间，最多6位小数。";
        if ((minQuantity != null || maxPrice != null) && (unit == null || unit.isBlank())) return "请补充数量和单价单位，例如吨，避免比较不同计量单位。";
        String delivery = normal(deliveryMethod);
        if (delivery != null && !Set.of("SELF_PICKUP", "DELIVERED").contains(delivery)) return "交付方式只能是自提或送到。";
        String sort = normal(sortBy);
        if (sort == null) sort = "PRICE_ASC";
        if (!Set.of("PRICE_ASC", "QUANTITY_DESC", "LATEST").contains(sort)) return "排序方式只能为价格升序、余量降序或最新。";
        LambdaQueryWrapper<Listing> query = publicQuery().eq(Listing::getSide, Listing.Side.SELL);
        if (keyword != null && !keyword.isBlank()) query.apply("commodity_name LIKE {0} ESCAPE '!'", like(keyword));
        if (location != null && !location.isBlank()) {
            List<Long> ids = warehouses.selectList(Wrappers.<Warehouse>lambdaQuery()
                    .and(q -> q.apply("province LIKE {0} ESCAPE '!'", like(location))
                            .or().apply("city LIKE {0} ESCAPE '!'", like(location))
                            .or().apply("name LIKE {0} ESCAPE '!'", like(location)))
                    .last("LIMIT 500")).stream().map(Warehouse::getId).toList();
            if (ids.isEmpty()) return "没有查到该省市或仓库的在售货物。";
            query.in(Listing::getWarehouseId, ids);
        }
        if (unit != null && !unit.isBlank()) query.eq(Listing::getUnit, unit.trim());
        if (minQuantity != null) query.ge(Listing::getRemainingQuantity, minQuantity);
        if (maxPrice != null) query.le(Listing::getPrice, maxPrice);
        if (delivery != null) query.eq(Listing::getDeliveryMethod, delivery);
        long total = listings.selectCount(query);
        // 排序片段来自固定枚举，用户输入绝不拼接 SQL。
        String order = switch (sort) {
            case "QUANTITY_DESC" -> "remaining_quantity DESC, id DESC";
            case "LATEST" -> "id DESC";
            default -> "(price IS NULL) ASC, price ASC, id DESC";
        };
        List<Listing> found = listings.selectList(query.last("ORDER BY " + order + " LIMIT " + ToolCallRecorder.rowLimit()));
        return render(found, total, "在售公开挂牌；排序=" + sort);
    }

    public String render(List<Listing> found, long total, String scope) {
        if (found.isEmpty()) return "没有符合条件的有效挂牌。可以放宽商品、地点、单价或余量条件。";
        List<Long> warehouseIds = found.stream().map(Listing::getWarehouseId).filter(Objects::nonNull).distinct().toList();
        List<Long> sellerIds = found.stream().map(Listing::getEnterpriseId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Warehouse> warehouseMap = warehouseIds.isEmpty() ? Map.of() : warehouses.selectBatchIds(warehouseIds).stream().collect(Collectors.toMap(Warehouse::getId, w -> w));
        Map<Long, Enterprise> sellerMap = sellerIds.isEmpty() ? Map.of() : enterprises.selectBatchIds(sellerIds).stream().collect(Collectors.toMap(Enterprise::getId, e -> e));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Listing item : found) {
            Warehouse warehouse = warehouseMap.get(item.getWarehouseId());
            Enterprise seller = sellerMap.get(item.getEnterpriseId());
            String place = warehouse == null ? "未登记" : text(warehouse.getName()) + "（" + text(warehouse.getProvince()) + " " + text(warehouse.getCity()) + "）";
            String sellerName = seller == null ? "未登记" : text(seller.getName());
            String delivery = Listing.DeliveryMethod.DELIVERED.equals(item.getDeliveryMethod()) ? "送到" : Listing.DeliveryMethod.SELF_PICKUP.equals(item.getDeliveryMethod()) ? "自提" : "未登记";
            String price = item.getPrice() == null ? "面议" : plain(item.getPrice()) + " 元/" + text(item.getUnit());
            AdvisorProductReference product = new AdvisorProductReference(item.getId(), text(item.getListingNo()), text(item.getCommodityName()), sellerName,
                    plain(item.getRemainingQuantity()) + " " + text(item.getUnit()), price, place, delivery);
            ToolCallRecorder.product(product);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("挂牌号", text(item.getListingNo()));
            row.put("商品", product.title());
            row.put("方向", item.getSide());
            row.put("规格", text(item.getSpec()));
            row.put("品牌", text(item.getBrand()));
            row.put("产地", text(item.getOrigin()));
            row.put("单价", price);
            row.put("余量", product.quantity());
            row.put("总挂牌量", plain(item.getQuantity()) + " " + text(item.getUnit()));
            row.put("卖家", sellerName);
            row.put("本方挂牌", Objects.equals(item.getEnterpriseId(), com.spotlink.shared.security.SecurityUtils.currentEnterpriseIdOrNull()));
            row.put("交收仓", place);
            row.put("交付", delivery);
            row.put("付款条款", text(item.getPaymentTerms()));
            row.put("成交方式", item.awaitsListerConfirm() ? "需挂牌方确认" : "摘牌即成交");
            row.put("有效期", item.getValidUntil() == null ? "未登记" : item.getValidUntil().atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString());
            row.put("详情", product.url());
            rows.add(row);
        }
        try {
            return json.writeValueAsString(Map.of("范围", scope, "查询时间", OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString(), "符合条件总数", total,
                    "显示数", rows.size(), "挂牌", rows, "说明", "挂牌报价不是成交价；未包含已确认运费。交收仓位置不是卖家公司注册地址。价格、余量和有效期以打开挂牌时为准。不同单位不可直接比较。"));
        } catch (Exception e) { throw new IllegalStateException("挂牌结果编码失败"); }
    }

    public static LambdaQueryWrapper<Listing> publicQuery() {
        return Wrappers.<Listing>lambdaQuery().in(Listing::getStatus, Listing.Status.OPEN, Listing.Status.PARTIALLY_FILLED)
                .gt(Listing::getRemainingQuantity, BigDecimal.ZERO).gt(Listing::getValidUntil, OffsetDateTime.now());
    }

    private static String like(String value) { return "%" + value.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"; }
    private static boolean validText(String value) { return value == null || value.length() <= 80; }
    private static boolean validNumber(BigDecimal value) { return value == null || (value.signum() >= 0 && value.compareTo(new BigDecimal("1000000000")) <= 0 && value.scale() <= 6); }
    private static String normal(String value) { return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT); }
    public static String text(String value) {
        if (value == null || value.isBlank()) return "未登记";
        String cleaned = value.replaceAll("[\\p{Cc}\\p{Cf}]", " ");
        return cleaned.substring(0, Math.min(cleaned.length(), 240));
    }
    private static String plain(BigDecimal value) { return value == null ? "未登记" : value.stripTrailingZeros().toPlainString(); }
}
