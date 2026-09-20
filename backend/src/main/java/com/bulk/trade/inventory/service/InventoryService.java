package com.bulk.trade.inventory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.commodity.entity.CommodityCategory;
import com.bulk.trade.commodity.mapper.CommodityCategoryMapper;
import com.bulk.trade.inventory.dto.InventoryRegisterRequest;
import com.bulk.trade.inventory.dto.InventoryUpdateRequest;
import com.bulk.trade.inventory.entity.InventoryNote;
import com.bulk.trade.inventory.mapper.InventoryNoteMapper;
import com.bulk.trade.shared.exception.BusinessException;
import com.bulk.trade.shared.web.ResultCode;
import com.bulk.trade.warehouse.entity.Warehouse;
import com.bulk.trade.warehouse.mapper.WarehouseMapper;
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
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private static final DateTimeFormatter NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final InventoryNoteMapper inventoryNoteMapper;
    private final CommodityCategoryMapper categoryMapper;
    private final WarehouseMapper warehouseMapper;
    private final ObjectMapper objectMapper;

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
        CommodityCategory category = categoryMapper.selectById(request.categoryId());
        if (category == null || !Integer.valueOf(1).equals(category.getStatus())) {
            throw BusinessException.of(ResultCode.CATEGORY_NOT_FOUND);
        }

        Warehouse warehouse = warehouseMapper.selectById(request.warehouseId());
        if (warehouse == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "交收仓库不存在");
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
        note.setUnit(request.unit() == null || request.unit().isBlank()
                ? category.getUnit()
                : request.unit());
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
        var query = Wrappers.<InventoryNote>lambdaQuery()
                .eq(InventoryNote::getEnterpriseId, enterpriseId)
                .orderByDesc(InventoryNote::getId);
        if (status != null) {
            query.eq(InventoryNote::getStatus, status);
        }
        return inventoryNoteMapper.selectList(query);
    }

    public InventoryNote get(Long id, Long enterpriseId) {
        return loadOwned(id, enterpriseId);
    }

    /**
     * 纠正库存单的描述性字段。
     *
     * <p>数量、仓库和单位不可编辑——原因见
     * {@link com.bulk.trade.inventory.dto.InventoryUpdateRequest}。这里能改的，只有
     * 文员可能打错的东西。
     *
     * <p>货物处于冻结状态时仍然允许编辑：品牌名写错并不影响被占用的数量，而禁止纠正
     * 只会让这个错误一直留在那里，直到挂牌结束为止。
     */
    @Transactional
    public InventoryNote update(Long id, InventoryUpdateRequest request, Long enterpriseId) {
        InventoryNote note = loadOwned(id, enterpriseId);

        if (note.getStatus() != null
                && (note.getStatus() == InventoryNote.Status.DELIVERED
                 || note.getStatus() == InventoryNote.Status.CANCELLED)) {
            throw BusinessException.of(ResultCode.INVENTORY_NOTE_NOT_AVAILABLE,
                    note.getStatus() == InventoryNote.Status.DELIVERED
                            ? "已交收的库存单不能再修改"
                            : "已注销的库存单不能再修改");
        }

        CommodityCategory category = categoryMapper.selectById(request.categoryId());
        if (category == null) {
            throw BusinessException.of(ResultCode.CATEGORY_NOT_FOUND);
        }

        note.setCategoryId(request.categoryId());
        note.setCommodityName(request.commodityName());
        note.setBrand(request.brand());
        note.setOrigin(request.origin());
        note.setSpec(writeSpec(request.spec()));
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
