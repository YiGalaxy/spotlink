package com.bulk.trade.settlement.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A reservation of goods or money.
 *
 * <p><b>Why one table for both.</b> A listing freezes goods; an order freezes
 * money. Both mean "this much of that thing is reserved for this reason, until
 * released or consumed" — same lifecycle, same release path, same need for an
 * audit trail. Two tables would duplicate the state machine and the release
 * logic, and the duplication would drift.
 *
 * <p>The {@code ck_freeze_payload} constraint enforces that a row reserves
 * exactly one thing, in the right unit: a quantity for goods, an amount for
 * money, never both and never neither.
 *
 * <p>Freezes are append-only in spirit. A released freeze keeps its row and
 * gains a status and timestamp; deleting it would destroy the record of what
 * was reserved when.
 */
@Getter
@Setter
@TableName("t_freeze_record")
public class FreezeRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String freezeNo;

    private Long enterpriseId;

    /** {@link EntityType#INVENTORY} or {@link EntityType#FUND}. */
    private String entityType;

    /** Inventory note id for goods, account id for money. */
    private Long entityId;

    /** Set for goods freezes; null for money. */
    private BigDecimal quantity;

    /** Set for money freezes; null for goods. */
    private BigDecimal amount;

    /** What caused it — {@link BizType}. */
    private String bizType;

    private Long bizId;

    /** {@link Status}. Stored as text so the database stays readable. */
    private String status;

    private String reason;
    private OffsetDateTime releasedAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    public boolean isFrozen() {
        return Status.FROZEN.equals(status);
    }

    public static final class EntityType {
        public static final String INVENTORY = "INVENTORY";
        public static final String FUND = "FUND";

        private EntityType() {
        }
    }

    public static final class Status {
        public static final String FROZEN = "FROZEN";
        public static final String RELEASED = "RELEASED";
        /** Spent on the deal it was reserved for, not returned to available. */
        public static final String CONSUMED = "CONSUMED";

        private Status() {
        }
    }

    public static final class BizType {
        public static final String LISTING = "LISTING";
        public static final String ORDER = "ORDER";

        private BizType() {
        }
    }
}
