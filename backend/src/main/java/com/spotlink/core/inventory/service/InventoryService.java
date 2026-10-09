package com.spotlink.inventory.service;

import com.spotlink.commodity.entity.CommodityCategory;
import com.spotlink.commodity.service.access.CommodityCategoryAccess;
import com.spotlink.inventory.dto.InventoryRegisterRequest;
import com.spotlink.inventory.dto.InventoryUpdateRequest;
import com.spotlink.inventory.entity.InventoryNote;
import com.spotlink.inventory.mapper.InventoryNoteMapper;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.web.ResultCode;
import com.spotlink.warehouse.entity.Warehouse;
import com.spotlink.warehouse.service.access.WarehouseAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private static final DateTimeFormatter NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final InventoryNoteMapper inventoryNoteMapper;
    private final CommodityCategoryAccess categoryAccess;
    private final WarehouseAccess warehouseAccess;
    private final ObjectMapper objectMapper;
    private final InventoryRules rules;

    /**
     * 登记货物并创建库存单。
     *
     * <p>这是一处简化：库存单直接进入 {@code IN_STOCK}。真实平台会先由指定仓库确认
     * 收货，货物才可交易——平台记录的是仓库告诉它的结果，而不是听信存货方的一面之词。
     * 那个确认环节正是 {@code PENDING_REVIEW} 状态该待的位置；枚举里已经为它留好了
     * 位置。
     */
    @Transactional
    public InventoryNote register(InventoryRegisterRequest request, Long enterpriseId) {
        InventoryRules.quantity(request.quantity());
        CommodityCategory category = loadActiveLeaf(request.categoryId());
        rules.spec(category, request.spec());
        if (request.unit() != null && !request.unit().isBlank()
                && !category.getUnit().equals(request.unit())) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "单位必须与品类一致：" + category.getUnit());
        }

        Warehouse warehouse = warehouseAccess.selectById(request.warehouseId());
        if (warehouse == null || !Integer.valueOf(1).equals(warehouse.getStatus())) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "交收仓库不存在或已停用");
        }

        InventoryNote note = new InventoryNote();
        note.setNoteNo(nextNoteNo());
        note.setEnterpriseId(enterpriseId);
        note.setCategoryId(request.categoryId());
        note.setWarehouseId(request.warehouseId());
        note.setCommodityName(request.commodityName());
        note.setBrand(request.brand());
        note.setOrigin(request.origin());
        note.setSpec(writeSpec(request.spec()));

        BigDecimal quantity = request.quantity();
        note.setTotalQuantity(quantity);
        // 此时还没有任何占用，所以可用量等于总量，数据库的 CHECK 约束从第一次写入
        // 起就是满足的。
        note.setAvailableQuantity(quantity);
        note.setFrozenQuantity(BigDecimal.ZERO);
        note.setUnit(category.getUnit());
        note.setStatus(InventoryNote.Status.IN_STOCK);
        note.setVersion(0);
        note.setRemark(request.remark());

        inventoryNoteMapper.insert(note);
        log.info("Inventory note {} registered by enterprise {}: {} {} of {}",
                note.getNoteNo(), enterpriseId, quantity.toPlainString(),
                note.getUnit(), note.getCommodityName());
        return note;
    }

    public List<InventoryNote> listMine(Long enterpriseId, Integer status) {
        return inventoryNoteMapper.findOwned(enterpriseId, status);
    }

    public InventoryNote get(Long id, Long enterpriseId) {
        return loadOwned(id, enterpriseId);
    }

    /**
     * 纠正库存单的描述性字段。
     *
     * <p>数量、仓库和单位不可编辑——原因见
     * {@link com.spotlink.inventory.dto.InventoryUpdateRequest}。这里能改的，只有
     * 文员可能打错的东西。
     *
     * <p>存在冻结数量时只允许修改备注，防止库存与已发布的商品属性发生分歧。
     */
    @Transactional
    public InventoryNote update(Long id, InventoryUpdateRequest request, Long enterpriseId) {
        InventoryNote note = loadOwned(id, enterpriseId);
        if (request.version() != null && !request.version().equals(note.getVersion())) {
            throw BusinessException.of(ResultCode.CONFLICT, "库存已被其他操作修改，请刷新后重新编辑");
        }

        if (note.getStatus() != null
                && (note.getStatus() == InventoryNote.Status.DELIVERED
                 || note.getStatus() == InventoryNote.Status.CANCELLED)) {
            throw BusinessException.of(ResultCode.INVENTORY_NOTE_NOT_AVAILABLE,
                    note.getStatus() == InventoryNote.Status.DELIVERED
                            ? "已交收的库存单不能再修改"
                            : "已注销的库存单不能再修改");
        }

        Map<String, Object> updatedSpec = mergeSpec(note, request);
        boolean descriptiveChange = !note.getCategoryId().equals(request.categoryId())
                || !note.getCommodityName().equals(request.commodityName())
                || !java.util.Objects.equals(note.getBrand(), request.brand())
                || !java.util.Objects.equals(note.getOrigin(), request.origin())
                || !sameSpec(note.getSpec(), updatedSpec);
        if (note.getFrozenQuantity().signum() > 0 && descriptiveChange) {
            throw BusinessException.of(ResultCode.CONFLICT, "存在冻结数量时仅可修改备注，请先解除挂牌或订单");
        }
        // 纯备注变更不依赖品类仍在架，允许维护已停用品类的历史库存记录。
        if (descriptiveChange) {
            CommodityCategory category = loadActiveLeaf(request.categoryId());
            rules.spec(category, updatedSpec);
            if (!note.getUnit().equals(category.getUnit())) {
                throw BusinessException.of(ResultCode.BAD_REQUEST, "新旧品类单位不一致，请重新登记库存");
            }
        }

        note.setCategoryId(request.categoryId());
        note.setCommodityName(request.commodityName());
        note.setBrand(request.brand());
        note.setOrigin(request.origin());
        note.setSpec(writeSpec(updatedSpec));
        note.setRemark(request.remark());

        // 数量没有被触碰，所以那个平衡约束不可能受影响；乐观锁依然在防着并发编辑。
        if (inventoryNoteMapper.updateById(note) == 0) {
            throw BusinessException.of(ResultCode.CONFLICT, "该库存单正在被其他操作修改，请重试");
        }
        log.info("Inventory note {} updated by enterprise {}", note.getNoteNo(), enterpriseId);
        return note;
    }

    /**
     * 注销一张库存单，但仅限它上面没有任何占用时。
     *
     * <p>带着生效中的冻结去注销，会让那个冻结指向已经不存在的货物。
     */
    @Transactional
    public void cancel(Long id, Long enterpriseId) {
        InventoryNote note = loadOwned(id, enterpriseId);

        if (note.getFrozenQuantity().signum() > 0) {
            throw BusinessException.of(ResultCode.CONFLICT,
                    "该库存单有 " + note.getFrozenQuantity().stripTrailingZeros().toPlainString()
                            + " " + note.getUnit() + " 处于冻结状态，请先解除挂牌或订单");
        }
        if (note.getStatus() != null && note.getStatus() == InventoryNote.Status.DELIVERED) {
            throw BusinessException.of(ResultCode.INVENTORY_NOTE_NOT_AVAILABLE, "已交收的库存单不能注销");
        }

        note.setStatus(InventoryNote.Status.CANCELLED);
        if (inventoryNoteMapper.updateById(note) == 0) {
            throw BusinessException.of(ResultCode.CONFLICT, "该库存单正在被其他操作修改，请重试");
        }
        log.info("Inventory note {} cancelled by enterprise {}", note.getNoteNo(), enterpriseId);
    }

    private InventoryNote loadOwned(Long id, Long enterpriseId) {
        InventoryNote note = inventoryNoteMapper.selectById(id);
        if (note == null || !note.getEnterpriseId().equals(enterpriseId)) {
            throw BusinessException.of(ResultCode.INVENTORY_NOTE_NOT_FOUND);
        }
        return note;
    }

    private CommodityCategory loadActiveLeaf(Long id) {
        CommodityCategory category = categoryAccess.selectById(id);
        if (category == null || !Integer.valueOf(1).equals(category.getStatus())) {
            throw BusinessException.of(ResultCode.CATEGORY_NOT_FOUND);
        }
        if (categoryAccess.hasChildren(id)) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "请选择具体的叶子品类");
        }
        return category;
    }

    private boolean sameSpec(String stored, Map<String, Object> incoming) {
        try {
            return objectMapper.readTree(stored).equals(objectMapper.readTree(writeSpec(incoming)));
        } catch (Exception e) {
            return false;
        }
    }

    /** 同品类保留 API 未回传的扩展键；已定义字段仍须由请求提供并通过校验。 */
    private Map<String, Object> mergeSpec(InventoryNote note, InventoryUpdateRequest request) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (note.getCategoryId().equals(request.categoryId())) {
            try {
                Map<String, Object> stored = objectMapper.readValue(note.getSpec(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                CommodityCategory category = categoryAccess.selectById(note.getCategoryId());
                java.util.Set<String> schemaKeys = new java.util.HashSet<>();
                if (category != null) objectMapper.readTree(category.getSpecSchema()).forEach(field -> schemaKeys.add(field.path("key").asText()));
                stored.forEach((key, value) -> { if (!schemaKeys.contains(key)) merged.put(key, value); });
            } catch (Exception e) {
                throw BusinessException.of(ResultCode.BAD_REQUEST, "既有规格数据无法读取，请联系平台维护");
            }
        }
        if (request.spec() != null) merged.putAll(request.spec());
        return merged;
    }

    private String writeSpec(Map<String, Object> spec) {
        if (spec == null || spec.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(spec);
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "规格参数格式不正确");
        }
    }

    /** 便于人读的单据编号；主键仍然是 Snowflake。 */
    private String nextNoteNo() {
        return "IN" + LocalDateTime.now().format(NO_FORMAT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
    }
}
