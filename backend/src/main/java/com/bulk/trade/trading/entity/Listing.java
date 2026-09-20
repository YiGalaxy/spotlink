package com.bulk.trade.trading.entity;

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
import java.time.OffsetDateTime;

/**
 * An offer to sell (卖方挂牌) or a request to buy (买方挂牌).
 *
 * <p><b>Publishing a listing is making an offer; accepting it is an
 * acceptance.</b> That is the legal shape of trade here, and it is why there is
 * no matching engine anywhere in this codebase. A central engine that discovers
 * prices makes a venue an exchange in the futures sense; two named parties
 * agreeing one contract at a time is ordinary spot trade.
 *
 * <p>A SELL listing freezes the seller's goods for as long as it is open — the
 * goods stay theirs, but they are reserved. A BUY listing is the mirror: it
 * reserves the buyer's margin instead, which is why {@code freezeId} is null
 * for one side and set for the other.
 */
@Getter
@Setter
@TableName("t_listing")
public class Listing {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String listingNo;
    private Long enterpriseId;

    /** {@link Side#SELL} or {@link Side#BUY}. */
    private String side;

    private Long categoryId;
    private String commodityName;
    private String brand;
    private String origin;
    private String spec;

    private BigDecimal quantity;

    /** Still open to acceptance; a listing may be taken in several parts. */
    private BigDecimal remainingQuantity;

    private String unit;

    /** Null for a negotiable listing. */
    private BigDecimal price;

    /** {@link PriceType#FIXED} or {@link PriceType#NEGOTIABLE}. */
    private String priceType;

    private Long warehouseId;
    private String deliveryMethod;
    private String paymentTerms;

    /** Goods freeze backing a SELL listing; released when the listing closes. */
    private Long freezeId;

    private OffsetDateTime validUntil;

    /** {@link Status}. */
    private String status;

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

    public boolean isOpenForTrade() {
        return Status.OPEN.equals(status) || Status.PARTIALLY_FILLED.equals(status);
    }

    public boolean isExpired(OffsetDateTime now) {
        return validUntil != null && validUntil.isBefore(now);
    }

    public static final class Side {
        public static final String SELL = "SELL";
        public static final String BUY = "BUY";

        private Side() {
        }
    }

    public static final class PriceType {
        public static final String FIXED = "FIXED";
        public static final String NEGOTIABLE = "NEGOTIABLE";

        private PriceType() {
        }
    }

    public static final class Status {
        public static final String OPEN = "OPEN";
        public static final String PARTIALLY_FILLED = "PARTIALLY_FILLED";
        public static final String FILLED = "FILLED";
        /** Withdrawn by its owner. */
        public static final String CLOSED = "CLOSED";
        /** Reached its valid-until without being fully taken. */
        public static final String EXPIRED = "EXPIRED";

        private Status() {
        }
    }

    public static final class DeliveryMethod {
        public static final String SELF_PICKUP = "SELF_PICKUP";
        public static final String DELIVERED = "DELIVERED";

        private DeliveryMethod() {
        }
    }
}
