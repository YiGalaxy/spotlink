package com.bulk.trade.settlement.service;

import com.bulk.trade.inventory.entity.InventoryNote;
import com.bulk.trade.inventory.mapper.InventoryNoteMapper;
import com.bulk.trade.settlement.entity.FreezeRecord;
import com.bulk.trade.settlement.mapper.FreezeRecordMapper;
import com.bulk.trade.shared.exception.BusinessException;
import com.bulk.trade.shared.web.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reserves and releases goods and money.
 *
 * <p>Both kinds of freeze follow the same path, which is why one service and
 * one table cover them. What differs is only the unit being reserved.
 *
 * <p><b>Every quantity change is a compare-and-set.</b> The note is read, its
 * new figures computed, and the write issued as {@code UPDATE ... WHERE id = ?
 * AND version = ?} by way of the {@code @Version} field. If another request
 * changed the row in between, zero rows are affected and the caller is told to
 * retry rather than silently overwriting the other change. Without this, two
 * listings created at the same moment could each reserve the same goods and
 * both appear valid until settlement failed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FreezeService {

    private static final DateTimeFormatter NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final InventoryNoteMapper inventoryNoteMapper;
    private final FreezeRecordMapper freezeRecordMapper;

    /**
     * Reads a live freeze without touching it.
     *
     * <p>Callers need it to find which note a listing's goods actually sit on:
     * a freeze stores the note id, and a listing does not.
     */
    public FreezeRecord findFrozen(Long enterpriseId, Long freezeId) {
        return loadFrozen(enterpriseId, freezeId);
    }

    // ------------------------------------------------------------------
    // Goods
    // ------------------------------------------------------------------

    /**
     * Reserves goods from an inventory note.
     *
     * @param quantity must be positive and no greater than the available amount
     */
    @Transactional
    public FreezeRecord freezeInventory(Long enterpriseId,
                                        Long noteId,
                                        BigDecimal quantity,
                                        String bizType,
                                        Long bizId,
                                        String reason) {

        InventoryNote note = loadOwnedNote(enterpriseId, noteId);

        if (!InventoryNote.Status.isTradable(note.getStatus())) {
            throw BusinessException.of(ResultCode.INVENTORY_NOTE_NOT_AVAILABLE);
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "冻结数量必须大于 0");
        }
        if (note.getAvailableQuantity().compareTo(quantity) < 0) {
            throw BusinessException.of(ResultCode.INVENTORY_QUANTITY_INSUFFICIENT,
                    "可用数量 %s %s，不足以冻结 %s %s".formatted(
                            note.getAvailableQuantity().stripTrailingZeros().toPlainString(),
                            note.getUnit(),
                            quantity.stripTrailingZeros().toPlainString(),
                            note.getUnit()));
        }

        applyQuantityChange(note, quantity.negate(), quantity);

        FreezeRecord record = new FreezeRecord();
        record.setFreezeNo(nextNo("FZ"));
        record.setEnterpriseId(enterpriseId);
        record.setEntityType(FreezeRecord.EntityType.INVENTORY);
        record.setEntityId(noteId);
        record.setQuantity(quantity);
        record.setBizType(bizType);
        record.setBizId(bizId);
        record.setStatus(FreezeRecord.Status.FROZEN);
        record.setReason(reason);
        freezeRecordMapper.insert(record);

        log.info("Froze {} {} on note {} for {} (bizId={})",
                quantity.toPlainString(), note.getUnit(), noteId, bizType, bizId);
        return record;
    }

    /**
     * Returns reserved goods to the available pool.
     *
     * <p>Used when the thing a freeze was made for goes away — a listing is
     * cancelled or expires, an order falls through.
     */
    @Transactional
    public void releaseInventory(Long enterpriseId, Long freezeId) {
        FreezeRecord record = loadFrozen(enterpriseId, freezeId);

        InventoryNote note = inventoryNoteMapper.selectById(record.getEntityId());
        if (note == null) {
            throw BusinessException.of(ResultCode.INVENTORY_NOTE_NOT_FOUND);
        }

        applyQuantityChange(note, record.getQuantity(), record.getQuantity().negate());
        markReleased(record);

        log.info("Released freeze {} ({} {})", freezeId,
                record.getQuantity().toPlainString(), note.getUnit());
    }

    /**
     * Spends reserved goods on the deal they were reserved for.
     *
     * <p>The goods leave the note entirely rather than returning to available —
     * this is what distinguishes a completed sale from a cancelled listing.
     */
    @Transactional
    public void consumeInventory(Long enterpriseId, Long freezeId) {
        FreezeRecord record = loadFrozen(enterpriseId, freezeId);

        InventoryNote note = inventoryNoteMapper.selectById(record.getEntityId());
        if (note == null) {
            throw BusinessException.of(ResultCode.INVENTORY_NOTE_NOT_FOUND);
        }

        // Quantity leaves both pools: total drops by the consumed amount.
        note.setTotalQuantity(note.getTotalQuantity().subtract(record.getQuantity()));
        applyQuantityChange(note, BigDecimal.ZERO, record.getQuantity().negate());

        // A note with nothing left is delivered, not merely fully frozen.
        if (note.getTotalQuantity().signum() == 0) {
            note.setStatus(InventoryNote.Status.DELIVERED);
            if (inventoryNoteMapper.updateById(note) == 0) {
                throw concurrentModification();
            }
        }

        record.setStatus(FreezeRecord.Status.CONSUMED);
        record.setReleasedAt(java.time.OffsetDateTime.now());
        freezeRecordMapper.updateById(record);

        log.info("Consumed freeze {} ({} {})", freezeId,
                record.getQuantity().toPlainString(), note.getUnit());
    }

    /**
     * Spends part of a freeze.
     *
     * <p>Needed because a listing reserves a whole batch while trades take it in
     * parts: a seller lists 100 tonnes, a buyer takes 30, and 70 must stay
     * reserved for the next buyer.
     *
     * <p><b>Implemented by closing the original record and opening a new one for
     * the remainder</b>, rather than by editing the quantity in place. A freeze
     * row is a statement that a specific amount was reserved at a specific
     * moment; rewriting its amount would erase what was reserved when, which is
     * exactly the question asked when two parties disagree later. Two rows cost
     * nothing and keep the trail honest.
     *
     * @param quantity must be positive and no greater than the frozen amount
     * @return the id of the freeze now holding the remainder, or null when the
     *         whole reservation was spent. <b>The caller must keep this.</b>
     *         The original record is closed rather than rewritten, so a caller
     *         that still points at it is pointing at a settled reservation —
     *         which is how a partly-sold listing ended up unable to sell again
     *         or to release what was left.
     */
    @Transactional
    public Long consumeInventoryPartial(Long enterpriseId, Long freezeId, BigDecimal quantity) {
        FreezeRecord record = loadFrozen(enterpriseId, freezeId);

        if (quantity == null || quantity.signum() <= 0) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "消耗数量必须大于 0");
        }
        if (record.getQuantity().compareTo(quantity) < 0) {
            throw BusinessException.of(ResultCode.CONFLICT,
                    "冻结数量 %s 少于要消耗的 %s".formatted(
                            record.getQuantity().stripTrailingZeros().toPlainString(),
                            quantity.stripTrailingZeros().toPlainString()));
        }

        InventoryNote note = inventoryNoteMapper.selectById(record.getEntityId());
        if (note == null) {
            throw BusinessException.of(ResultCode.INVENTORY_NOTE_NOT_FOUND);
        }

        // The goods leave: total drops, frozen drops by the same amount.
        note.setTotalQuantity(note.getTotalQuantity().subtract(quantity));
        applyQuantityChange(note, BigDecimal.ZERO, quantity.negate());

        BigDecimal remainder = record.getQuantity().subtract(quantity);
        markConsumed(record);

        Long remainderId = null;
        if (remainder.signum() > 0) {
            FreezeRecord next = new FreezeRecord();
            next.setFreezeNo(nextNo("FZ"));
            next.setEnterpriseId(record.getEnterpriseId());
            next.setEntityType(record.getEntityType());
            next.setEntityId(record.getEntityId());
            next.setQuantity(remainder);
            next.setBizType(record.getBizType());
            next.setBizId(record.getBizId());
            next.setStatus(FreezeRecord.Status.FROZEN);
            next.setReason("部分消耗后剩余");
            freezeRecordMapper.insert(next);
            remainderId = next.getId();
        }

        if (note.getTotalQuantity().signum() == 0) {
            note.setStatus(InventoryNote.Status.DELIVERED);
            if (inventoryNoteMapper.updateById(note) == 0) {
                throw concurrentModification();
            }
        }

        log.info("Consumed {} of freeze {}; remainder {} held by {}",
                quantity.toPlainString(), freezeId, remainder.toPlainString(),
                remainderId == null ? "(nothing)" : remainderId);
        return remainderId;
    }

    /**
     * Records which business object a freeze was made for.
     *
     * <p>Separate from creation because the two are not always known at the
     * same moment: a listing is frozen before it is inserted, so its id does
     * not exist yet. An explicit follow-up makes that ordering visible instead
     * of leaving a null that looks like "this freeze belongs to nothing".
     */
    @Transactional
    public void attributeTo(Long freezeId, Long bizId) {
        FreezeRecord record = freezeRecordMapper.selectById(freezeId);
        if (record == null || bizId == null) {
            return;
        }
        record.setBizId(bizId);
        freezeRecordMapper.updateById(record);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Applies a quantity change under the optimistic lock.
     *
     * <p>A zero-row result means the row changed since it was read, so the
     * computed figures are stale. Retrying is the caller's decision — a human
     * pressing a button can simply press it again, and inventing an automatic
     * retry here would hide the contention instead of surfacing it.
     */
    private void applyQuantityChange(InventoryNote note,
                                     BigDecimal availableDelta,
                                     BigDecimal frozenDelta) {
        note.setAvailableQuantity(note.getAvailableQuantity().add(availableDelta));
        note.setFrozenQuantity(note.getFrozenQuantity().add(frozenDelta));
        note.setStatus(deriveStatus(note));

        if (inventoryNoteMapper.updateById(note) == 0) {
            throw concurrentModification();
        }
    }

    private int deriveStatus(InventoryNote note) {
        if (note.getFrozenQuantity().signum() == 0) {
            return InventoryNote.Status.IN_STOCK;
        }
        if (note.getAvailableQuantity().signum() == 0) {
            return InventoryNote.Status.FULLY_FROZEN;
        }
        return InventoryNote.Status.PARTIALLY_FROZEN;
    }

    /**
     * Loads a note only if the caller's enterprise owns it.
     *
     * <p>Reporting "not found" rather than "forbidden" avoids confirming that
     * another company's note id exists.
     */
    private InventoryNote loadOwnedNote(Long enterpriseId, Long noteId) {
        InventoryNote note = inventoryNoteMapper.selectById(noteId);
        if (note == null || !note.getEnterpriseId().equals(enterpriseId)) {
            throw BusinessException.of(ResultCode.INVENTORY_NOTE_NOT_FOUND);
        }
        return note;
    }

    private FreezeRecord loadFrozen(Long enterpriseId, Long freezeId) {
        FreezeRecord record = freezeRecordMapper.selectById(freezeId);
        if (record == null || !record.getEnterpriseId().equals(enterpriseId)) {
            throw BusinessException.of(ResultCode.FREEZE_RECORD_NOT_FOUND);
        }
        if (!record.isFrozen()) {
            throw BusinessException.of(ResultCode.FREEZE_ALREADY_RELEASED);
        }
        return record;
    }

    private void markConsumed(FreezeRecord record) {
        record.setStatus(FreezeRecord.Status.CONSUMED);
        record.setReleasedAt(java.time.OffsetDateTime.now());
        freezeRecordMapper.updateById(record);
    }

    private void markReleased(FreezeRecord record) {
        record.setStatus(FreezeRecord.Status.RELEASED);
        record.setReleasedAt(java.time.OffsetDateTime.now());
        freezeRecordMapper.updateById(record);
    }

    private BusinessException concurrentModification() {
        return BusinessException.of(ResultCode.CONFLICT,
                "该库存单正在被其他操作修改，请刷新后重试");
    }

    /**
     * Business-facing document number. Snowflake ids are the primary key; this
     * is what a person reads out over the phone.
     */
    private String nextNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_FORMAT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
    }
}
