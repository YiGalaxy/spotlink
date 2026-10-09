package com.spotlink.trading.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotlink.commodity.service.access.CommodityCategoryAccess;
import com.spotlink.inventory.entity.InventoryNote;
import com.spotlink.inventory.service.InventoryRules;
import com.spotlink.inventory.service.access.InventoryNoteAccess;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.web.ResultCode;
import com.spotlink.trading.entity.Listing;
import com.spotlink.trading.mapper.ListingMapper;
import com.spotlink.warehouse.service.access.WarehouseAccess;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** BUY 摘牌必须明确选货；查询和最终成交共享同一匹配规则，不凭品类自动挑库存。 */
@Service
@RequiredArgsConstructor
public class InventoryMatchService {
    private final ListingMapper listings;
    private final InventoryNoteAccess notes;
    private final WarehouseAccess warehouses;
    private final CommodityCategoryAccess categories;
    private final ObjectMapper json;

    public List<InventoryNote> candidates(Long listingId, BigDecimal quantity, Long seller) {
        Listing listing = listings.selectById(listingId);
        if (listing == null) throw BusinessException.of(ResultCode.LISTING_NOT_FOUND);
        requireBuy(listing, quantity, seller);
        var candidates = notes.matchingCandidates(seller, listing.getCategoryId(), listing.getUnit(), listing.getWarehouseId(), quantity);
        if (candidates.isEmpty()) return List.of();
        var warehouseIds = candidates.stream().map(InventoryNote::getWarehouseId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if (warehouseIds.isEmpty()) return List.of();
        var active = warehouses.selectBatchIds(warehouseIds).stream().filter(warehouse -> Integer.valueOf(1).equals(warehouse.getStatus()))
                .map(com.spotlink.warehouse.entity.Warehouse::getId).collect(java.util.stream.Collectors.toSet());
        return candidates.stream().filter(note -> active.contains(note.getWarehouseId()) && matchesAttributes(listing, note, quantity)).toList();
    }

    public InventoryNote requireSource(Listing listing, Long noteId, BigDecimal quantity, Long seller) {
        requireBuy(listing, quantity, seller);
        if (noteId == null) throw BusinessException.of(ResultCode.BAD_REQUEST, "请明确选择用于交付的源库存单");
        InventoryNote note = notes.selectById(noteId);
        if (note == null || !Objects.equals(seller, note.getEnterpriseId())) {
            throw BusinessException.of(ResultCode.INVENTORY_NOTE_NOT_FOUND);
        }
        if (!matches(listing, note, quantity)) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "源库存的可用数量、单位、仓库或规格不符合采购要求");
        }
        return note;
    }

    private void requireBuy(Listing listing, BigDecimal quantity, Long seller) {
        InventoryRules.quantity(quantity);
        if (seller == null || Objects.equals(seller, listing.getEnterpriseId())) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "只有其他企业的卖方可以选择交付库存");
        }
        if (!Listing.Side.BUY.equals(listing.getSide())) throw BusinessException.of(ResultCode.BAD_REQUEST, "只有采购挂牌需要选择源库存");
        if (!listing.isOpenForTrade()) throw BusinessException.of(ResultCode.LISTING_ALREADY_CLOSED);
        if (listing.isExpired(OffsetDateTime.now())) throw BusinessException.of(ResultCode.LISTING_EXPIRED);
        if (quantity.compareTo(listing.getRemainingQuantity()) > 0) throw BusinessException.of(ResultCode.LISTING_QUANTITY_EXCEEDED);
        var category = categories.selectById(listing.getCategoryId());
        if (category == null || !Integer.valueOf(1).equals(category.getStatus())) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "采购品类不存在或已停用");
        }
    }

    private boolean matches(Listing listing, InventoryNote note, BigDecimal quantity) {
        var warehouse = note.getWarehouseId() == null ? null : warehouses.selectById(note.getWarehouseId());
        return warehouse != null && Integer.valueOf(1).equals(warehouse.getStatus()) && matchesAttributes(listing, note, quantity);
    }

    private boolean matchesAttributes(Listing listing, InventoryNote note, BigDecimal quantity) {
        if (!InventoryNote.Status.isTradable(note.getStatus()) || note.getAvailableQuantity().compareTo(quantity) < 0
                || !Objects.equals(listing.getCategoryId(), note.getCategoryId())
                || !Objects.equals(listing.getUnit(), note.getUnit())
                || listing.getWarehouseId() != null && !Objects.equals(listing.getWarehouseId(), note.getWarehouseId())
                || !textMatches(listing.getBrand(), note.getBrand()) || !textMatches(listing.getOrigin(), note.getOrigin())) return false;
        try {
            JsonNode wanted = json.readTree(listing.getSpec());
            JsonNode actual = json.readTree(note.getSpec());
            if (wanted == null || actual == null || !wanted.isObject() || !actual.isObject()) return false;
            var fields = wanted.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode requirement = field.getValue();
                if (requirement.isNull() || requirement.isTextual() && requirement.asText().isBlank()) continue;
                JsonNode value = actual.get(field.getKey());
                if (value == null) return false;
                if (requirement.isNumber() && value.isNumber()) {
                    if (requirement.decimalValue().compareTo(value.decimalValue()) != 0) return false;
                } else if (!requirement.equals(value)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean textMatches(String wanted, String actual) {
        return wanted == null || wanted.isBlank() || actual != null && wanted.strip().equals(actual.strip());
    }
}
