package com.spotlink.settlement;

import com.spotlink.inventory.entity.InventoryNote;
import com.spotlink.inventory.mapper.InventoryNoteMapper;
import com.spotlink.settlement.entity.FreezeRecord;
import com.spotlink.settlement.mapper.FreezeRecordMapper;
import com.spotlink.settlement.service.FreezeService;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.web.ResultCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the freeze lifecycle against a real database.
 *
 * <p>Deliberately not a mock-based unit test. What is being checked is whether
 * the three quantity columns stay consistent across a sequence of real writes,
 * including the check constraint and the optimistic lock — none of which a mock
 * would exercise.
 *
 * <p>All rows created here are removed afterwards. An earlier version set the
 * soft-delete column by hand and called updateById, which does nothing —
 * {@code @TableLogic} makes MyBatis-Plus ignore that field on update — and the
 * leftover rows turned up in the application's inventory list. Cleanup now goes
 * through deleteById, which is the supported path.
 *
 * <p>Requires the docker compose stack from the README to be running.
 */
@org.junit.jupiter.api.Tag("integration")
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("库存冻结生命周期")
class FreezeServiceTest {

    /** Deliberately not a real enterprise id, so nothing here touches live data. */
    private static final Long TEST_ENTERPRISE_ID = 999_000_001L;

    @Autowired
    private FreezeService freezeService;

    @Autowired
    private InventoryNoteMapper inventoryNoteMapper;

    @Autowired
    private FreezeRecordMapper freezeRecordMapper;

    private Long noteId;
    private final List<Long> freezeIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        InventoryNote note = new InventoryNote();
        note.setNoteNo("TEST" + System.nanoTime());
        note.setEnterpriseId(TEST_ENTERPRISE_ID);
        note.setCategoryId(1002L);
        note.setWarehouseId(2001L);
        note.setCommodityName("测试电解铜");
        note.setSpec("{}");
        note.setTotalQuantity(new BigDecimal("100"));
        note.setAvailableQuantity(new BigDecimal("100"));
        note.setFrozenQuantity(BigDecimal.ZERO);
        note.setUnit("吨");
        note.setStatus(InventoryNote.Status.IN_STOCK);
        note.setVersion(0);
        inventoryNoteMapper.insert(note);
        noteId = note.getId();
    }

    @AfterEach
    void tearDown() {
        freezeIds.forEach(freezeRecordMapper::deleteById);
        freezeIds.clear();
        if (noteId != null) {
            // deleteById, not a hand-set `deleted` field — see the class note.
            inventoryNoteMapper.deleteById(noteId);
        }
    }

    @Test
    @DisplayName("部分冻结：可用减少，冻结增加，总量不变")
    void partialFreeze() {
        freeze(new BigDecimal("30"), FreezeRecord.BizType.LISTING);

        InventoryNote note = inventoryNoteMapper.selectById(noteId);
        assertThat(note.getTotalQuantity()).isEqualByComparingTo("100");
        assertThat(note.getAvailableQuantity()).isEqualByComparingTo("70");
        assertThat(note.getFrozenQuantity()).isEqualByComparingTo("30");
        assertThat(note.getStatus()).isEqualTo(InventoryNote.Status.PARTIALLY_FROZEN);
    }

    @Test
    @DisplayName("全部冻结后状态变为 FULLY_FROZEN，再冻结会被拒绝")
    void fullFreezeThenReject() {
        freeze(new BigDecimal("100"), FreezeRecord.BizType.LISTING);

        InventoryNote note = inventoryNoteMapper.selectById(noteId);
        assertThat(note.getAvailableQuantity()).isEqualByComparingTo("0");
        assertThat(note.getStatus()).isEqualTo(InventoryNote.Status.FULLY_FROZEN);

        // The over-request must be refused rather than silently driving the
        // available quantity negative.
        assertThatThrownBy(() -> freezeService.freezeInventory(
                TEST_ENTERPRISE_ID, noteId, new BigDecimal("1"),
                FreezeRecord.BizType.LISTING, null, "超额挂牌"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.INVENTORY_QUANTITY_INSUFFICIENT);
    }

    @Test
    @DisplayName("解冻：冻结量回到可用量，状态回到在库")
    void release() {
        FreezeRecord freeze = freeze(new BigDecimal("40"), FreezeRecord.BizType.LISTING);

        freezeService.releaseInventory(TEST_ENTERPRISE_ID, freeze.getId());

        InventoryNote note = inventoryNoteMapper.selectById(noteId);
        assertThat(note.getAvailableQuantity()).isEqualByComparingTo("100");
        assertThat(note.getFrozenQuantity()).isEqualByComparingTo("0");
        assertThat(note.getStatus()).isEqualTo(InventoryNote.Status.IN_STOCK);

        FreezeRecord stored = freezeRecordMapper.selectById(freeze.getId());
        assertThat(stored.getStatus()).isEqualTo(FreezeRecord.Status.RELEASED);
        assertThat(stored.getReleasedAt()).isNotNull();
    }

    @Test
    @DisplayName("解冻后重复解冻会被拒绝")
    void doubleReleaseRejected() {
        FreezeRecord freeze = freeze(new BigDecimal("10"), FreezeRecord.BizType.LISTING);
        freezeService.releaseInventory(TEST_ENTERPRISE_ID, freeze.getId());

        assertThatThrownBy(() -> freezeService.releaseInventory(TEST_ENTERPRISE_ID, freeze.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.FREEZE_ALREADY_RELEASED);
    }

    @Test
    @DisplayName("消耗：冻结量不回到可用量，总量随之减少")
    void consumeRemovesGoods() {
        FreezeRecord freeze = freeze(new BigDecimal("60"), FreezeRecord.BizType.ORDER);

        freezeService.consumeInventory(TEST_ENTERPRISE_ID, freeze.getId());

        InventoryNote note = inventoryNoteMapper.selectById(noteId);
        // The goods left: total drops, available and frozen return to 0.
        assertThat(note.getTotalQuantity()).isEqualByComparingTo("40");
        assertThat(note.getAvailableQuantity()).isEqualByComparingTo("40");
        assertThat(note.getFrozenQuantity()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("全部消耗后状态变为已交收")
    void consumeAllMarksDelivered() {
        FreezeRecord freeze = freeze(new BigDecimal("100"), FreezeRecord.BizType.ORDER);

        freezeService.consumeInventory(TEST_ENTERPRISE_ID, freeze.getId());

        InventoryNote note = inventoryNoteMapper.selectById(noteId);
        assertThat(note.getTotalQuantity()).isEqualByComparingTo("0");
        assertThat(note.getStatus()).isEqualTo(InventoryNote.Status.DELIVERED);
    }

    @Test
    @DisplayName("跨企业操作被拒绝，且报「不存在」而非「无权限」")
    void otherEnterpriseCannotFreeze() {
        assertThatThrownBy(() -> freezeService.freezeInventory(
                888_000_002L, noteId, new BigDecimal("1"),
                FreezeRecord.BizType.LISTING, null, "越权"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResultCode())
                .isEqualTo(ResultCode.INVENTORY_NOTE_NOT_FOUND);
    }

    /** Freezes and remembers the row so tearDown can remove it. */
    private FreezeRecord freeze(BigDecimal quantity, String bizType) {
        FreezeRecord record = freezeService.freezeInventory(
                TEST_ENTERPRISE_ID, noteId, quantity, bizType, null, "测试");
        freezeIds.add(record.getId());
        return record;
    }
}
