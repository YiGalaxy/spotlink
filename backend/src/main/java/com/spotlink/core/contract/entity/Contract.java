package com.spotlink.contract.entity;

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
 * 订单背后的已签署协议。
 *
 * <p><b>条款是快照，不是引用。</b>数量、价格和金额是从订单复制过来的，而不是
 * 关联到订单。合同记录的是双方在某一刻约定的内容；如果订单日后被更正，合同
 * 绝不能随之悄悄改变——已经开出的发票引用的是这份文件，而不是订单的当前状态。
 *
 * <p><b>已签署意味着双方都已签署。</b>一条 CHECK 约束保证任何一行都不可能
 * 在只有一方签名的情况下声称已签署，因为单方签名不是一份协议，数据库本就不该
 * 能持有这种状态。
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

    /** 以 JSON 表示的条款：交收、质量、溢短装、争议解决。 */
    private String terms;

    private BigDecimal quantity;
    private String unit;
    private BigDecimal price;
    private BigDecimal amount;

    /** 允许的过磅差异百分比；超出此范围的结算走人工。 */
    private BigDecimal weightTolerance;

    /** {@link Status}。 */
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
