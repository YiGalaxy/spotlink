package com.bulk.trade.contract.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The signed agreement behind an order.
 *
 * <p><b>Terms are snapshotted, not referenced.</b> Quantity, price and amount
 * are copied from the order rather than joined to it. A contract records what
 * two parties agreed at a moment; if the order is later corrected, the contract
 * must not silently change with it — an invoice already issued refers to this
 * document, not to the order's current state.
 *
 * <p><b>Signed means both sides signed.</b> A check constraint enforces that no
 * row can claim to be signed with only one signature present, because a
 * one-sided signature is not an agreement and the database should not be able
 * to hold that state at all.
 */
@Getter
@Setter
@TableName("t_contract")
public class Contract {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String contractNo;
    private Long orderId;
    private Long buyerId;
    private Long sellerId;

    private String title;

    /** Terms as JSON: delivery, quality, tolerance, dispute resolution. */
    private String terms;

    private BigDecimal quantity;
    private String unit;
    private BigDecimal price;
    private BigDecimal amount;

    /** Allowed weighing variance in percent; settlement beyond this is manual. */
    private BigDecimal weightTolerance;

    /** {@link Status}. */
    private String status;

    private OffsetDateTime buyerSignedAt;
    private Long buyerSignedBy;
    private OffsetDateTime sellerSignedAt;
    private Long sellerSignedBy;

    private OffsetDateTime terminatedAt;
    private String terminateReason;

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

    public boolean involves(Long enterpriseId) {
        return enterpriseId != null
                && (enterpriseId.equals(buyerId) || enterpriseId.equals(sellerId));
    }

    public boolean isBuyer(Long enterpriseId) {
        return enterpriseId != null && enterpriseId.equals(buyerId);
    }

    public boolean hasSigned(Long enterpriseId) {
        return isBuyer(enterpriseId) ? buyerSignedAt != null : sellerSignedAt != null;
    }

    public static final class Status {
        public static final String DRAFT = "DRAFT";
        public static final String PENDING_SIGN = "PENDING_SIGN";
        public static final String SIGNED = "SIGNED";
        public static final String TERMINATED = "TERMINATED";

        private Status() {
        }

        public static String text(String status) {
            if (status == null) {
                return "未知";
            }
            return switch (status) {
                case DRAFT -> "草稿";
                case PENDING_SIGN -> "待签署";
                case SIGNED -> "已生效";
                case TERMINATED -> "已解除";
                default -> "未知";
            };
        }
    }
}
