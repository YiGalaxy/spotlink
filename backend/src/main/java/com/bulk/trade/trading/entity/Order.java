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
 * A contract-to-be between two named parties.
 *
 * <p><b>Either side can read this row</b>, which is why the tenant filter is
 * {@code buyer_id = ? OR seller_id = ?} rather than a single owner column. A
 * single {@code enterprise_id} would have made one party a second-class reader
 * of their own deal.
 *
 * <p><b>{@code amount} is stored, not recomputed.</b> It is what was agreed. If
 * a price or quantity were ever corrected, a derived total would silently
 * restate the history of a deal that has already been invoiced.
 */
@Getter
@Setter
@TableName("t_order")
public class Order {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String orderNo;

    /** The listing this order accepted; null for a negotiated deal. */
    private Long listingId;

    private Long buyerId;
    private Long sellerId;

    private Long categoryId;
    private String commodityName;
    private String spec;

    private BigDecimal quantity;
    private String unit;
    private BigDecimal price;

    /** {@code quantity * price}, frozen at the moment of agreement. */
    private BigDecimal amount;

    private Long warehouseId;
    private String deliveryMethod;
    private String paymentTerms;

    /** Goods freeze on the seller's side. */
    private Long goodsFreezeId;

    /** Margin freeze on the buyer's side. */
    private Long marginFreezeId;

    /** See {@link OrderStatus}. */
    private String status;

    private Long contractId;
    private OffsetDateTime confirmedAt;
    private OffsetDateTime cancelledAt;
    private String cancelReason;

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

    /** True when the given enterprise is a party to this order. */
    public boolean involves(Long enterpriseId) {
        return enterpriseId != null
                && (enterpriseId.equals(buyerId) || enterpriseId.equals(sellerId));
    }

    /** Which side the given enterprise is on, for rendering. */
    public String roleOf(Long enterpriseId) {
        if (enterpriseId == null) {
            return "—";
        }
        if (enterpriseId.equals(buyerId)) {
            return "BUYER";
        }
        if (enterpriseId.equals(sellerId)) {
            return "SELLER";
        }
        return "—";
    }
}
