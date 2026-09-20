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
 * 预留与释放货物和资金。
 *
 * <p>两种冻结走的是同一条路径，这正是由一个 service 和一张表覆盖它们的
 * 原因。不同的只是被预留的单位。
 *
 * <p><b>每一次数量变更都是一次比较并设置（compare-and-set）。</b>读出库存单，
 * 算出新数字，然后依靠 {@code @Version} 字段把写入表达为
 * {@code UPDATE ... WHERE id = ? AND version = ?}。如果期间有另一个请求改动了
 * 该行，受影响行数为零，调用方会被告知重试，而不是无声地覆盖掉另一处改动。
 * 没有这一层，两份在同一时刻创建的挂牌可能各自预留同一批货物，而且两者都会
 * 看起来有效，直到结算失败为止。
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
     * 读取一笔仍然有效的冻结，不做任何改动。
     *
     * <p>调用方需要它来找出某份挂牌的货物究竟落在哪张库存单上：冻结记录存有
     * 库存单 id，而挂牌没有。
     */
    public FreezeRecord findFrozen(Long enterpriseId, Long freezeId) {
        return loadFrozen(enterpriseId, freezeId);
    }

    // ------------------------------------------------------------------
    // 货物
    // ------------------------------------------------------------------

    /**
     * 从一张库存单中预留货物。
     *
     * @param quantity 必须为正，且不大于可用数量
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
     * 把预留的货物退回可用池。
     *
     * <p>用于一笔冻结所服务的对象消失了的时候——挂牌被取消或过期，订单落空。
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
     * 把预留的货物花在它们当初为之预留的那笔交易上。
     *
     * <p>货物彻底离开这张库存单，而不是退回可用——这正是已完成的销售与一纸
     * 被撤销的挂牌之间的区别。
     */
    @Transactional
    public void consumeInventory(Long enterpriseId, Long freezeId) {
        FreezeRecord record = loadFrozen(enterpriseId, freezeId);

        InventoryNote note = inventoryNoteMapper.selectById(record.getEntityId());
        if (note == null) {
            throw BusinessException.of(ResultCode.INVENTORY_NOTE_NOT_FOUND);
        }

        // 数量从两个池子里同时离开：总量按被消耗的数量下降。
        note.setTotalQuantity(note.getTotalQuantity().subtract(record.getQuantity()));
        applyQuantityChange(note, BigDecimal.ZERO, record.getQuantity().negate());

        // 一点都不剩的库存单是已交付，而不仅仅是被全部冻结。
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
     * 消耗一笔冻结的一部分。
     *
     * <p>之所以需要它，是因为一份挂牌预留的是一整批货，而交易是分批摘走的：
     * 卖方挂出 100 吨，一个买方摘走 30 吨，剩下的 70 吨必须继续为下一位买方
     * 预留着。
     *
     * <p><b>实现方式是关闭原记录、为剩余部分另开一条新记录</b>，而不是就地
     * 改写数量。一条冻结记录是一个陈述：某个具体数量在某个具体时刻被预留了；
     * 改写它的数量会抹掉“何时预留了多少”，而这恰恰是日后双方产生分歧时要问
     * 的那个问题。多用一行不花什么代价，却能让这条轨迹保持诚实。
     *
     * @param quantity 必须为正，且不大于已冻结的数量
     * @return 现在持有剩余部分的那条冻结记录的 id；若整笔预留都被消耗光则为
     *         null。<b>调用方必须保存它。</b>原记录是被关闭而不是被改写，因此
     *         仍然指向它的调用方，指向的是一笔已结清的预留——一份被部分售出
     *         的挂牌，正是这样落到了既无法再次出售、也无法释放剩余货物的
     *         境地。
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

        // 货物离开：总量下降，冻结量下降同样的数额。
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
     * 记录一笔冻结是为哪个业务对象而做。
     *
     * <p>与创建分开，是因为这两件事并不总在同一时刻已知：挂牌在被插入之前
     * 就被冻结了，所以它的 id 当时还不存在。一个显式的后续调用让这个先后次序
     * 可见，而不是留下一个看起来像“这笔冻结不属于任何东西”的 null。
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
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 在乐观锁之下施加一次数量变更。
     *
     * <p>返回零行意味着该行自读出之后已被改动，因此算出的数字已经过时。是否
     * 重试由调用方决定——一个按下按钮的人再按一次就行了，而在这里发明一套
     * 自动重试，只会掩盖竞争而不是把它暴露出来。
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
     * 仅在调用方企业拥有该库存单时才加载它。
     *
     * <p>报告“未找到”而不是“无权访问”，可以避免确认其他公司的库存单 id
     * 确实存在。
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
     * 面向业务的单据编号。Snowflake id 是主键；而这个编号是人会在电话里念
     * 出来的那个。
     */
    private String nextNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_FORMAT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
    }
}
