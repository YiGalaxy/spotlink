package com.bulk.trade.inventory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.commodity.entity.CommodityCategory;
import com.bulk.trade.commodity.mapper.CommodityCategoryMapper;
import com.bulk.trade.inventory.dto.InventoryRegisterRequest;
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
     * Registers goods and creates the note.
     *
     * <p>Simplification: the note goes straight to {@code IN_STOCK}. A real
     * platform has the designated warehouse confirm receipt before goods become
     * tradable — the platform records what a warehouse tells it, it does not
     * take the depositor's word. That confirmation step is where a
     * {@code PENDING_REVIEW} state would sit; the enum already reserves it.
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
        // Nothing is reserved yet, so available equals total and the database
        // check constraint is satisfied from the first write.
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
     * Cancels a note, but only when nothing is reserved against it.
     *
     * <p>Cancelling with an active freeze would leave that freeze pointing at
     * goods that no longer exist.
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

    /** Human-readable document number; the primary key stays a snowflake. */
    private String nextNoteNo() {
        return "IN" + LocalDateTime.now().format(NO_FORMAT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
    }
}
