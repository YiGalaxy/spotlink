package com.bulk.trade.inventory.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * An electronic inventory note (电子库存单) — the subject of a trade.
 *
 * <p><b>It is not a warehouse receipt.</b> Under the Civil Code a warehouse
 * receipt is a document of title: pledgeable and endorsable. Trading a document
 * of title as a standardised instrument is what gets a spot platform
 * reclassified as a de facto futures exchange. This entity is defined as
 * nothing more than a digital record of goods held in a named warehouse, which
 * is the whole reason it carries that name.
 *
 * <p><b>Quantity is three numbers, not one.</b> {@code totalQuantity} is what
 * the owner has; {@code availableQuantity} is what can still be offered;
 * {@code frozenQuantity} is reserved by an active listing or order. A database
 * check constraint enforces {@code available + frozen = total}, so an
 * accounting mistake is rejected at write time rather than discovered during a
 * settlement.
 *
 * <p><b>Concurrent changes go through the optimistic lock.</b> Every quantity
 * update is {@code UPDATE ... WHERE id = ? AND version = ?}; two requests
 * racing for the same available quantity cannot both succeed.
 */
@Getter
@Setter
@TableName("t_inventory_note")
public class InventoryNote {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String noteNo;

    /** The owning enterprise — the tenant key. */
    private Long enterpriseId;

    private Long categoryId;
    private Long warehouseId;

    private String commodityName;
    private String brand;
    private String origin;

    /** Specification values keyed by the category's spec schema, as JSON. */
    private String spec;

    private BigDecimal totalQuantity;
    private BigDecimal availableQuantity;
    private BigDecimal frozenQuantity;
    private String unit;

    private String qualityReportKey;
    private LocalDate productionDate;

    /** See {@link Status}. */
    private Integer status;

    /**
     * Optimistic lock. MyBatis-Plus appends {@code AND version = ?} to updates
     * and bumps it, turning a lost update into a zero-row result the caller
     * can detect.
     */
    @Version
    private Integer version;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    @TableLogic
    private Integer deleted;

    /** True when nothing is reserved against this note. */
    public boolean isFullyAvailable() {
        return frozenQuantity == null || frozenQuantity.signum() == 0;
    }

    public static final class Status {
        public static final int DRAFT = 0;
        public static final int PENDING_REVIEW = 1;
        public static final int IN_STOCK = 2;
        public static final int FULLY_FROZEN = 3;
        public static final int PARTIALLY_FROZEN = 4;
        public static final int DELIVERED = 5;
        public static final int CANCELLED = 6;

        private Status() {
        }

        /** Notes in these states can be listed or sold. */
        public static boolean isTradable(Integer status) {
            return status != null
                    && (status == IN_STOCK || status == FULLY_FROZEN || status == PARTIALLY_FROZEN);
        }
    }
}
