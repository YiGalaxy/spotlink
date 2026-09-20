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
 * One movement of money. Append-only.
 *
 * <p>Never updated, never deleted. A mistake is corrected by writing a
 * compensating row, because a ledger that can be edited cannot be audited — and
 * on a trading platform the ledger is the artefact a dispute is settled with.
 *
 * <p>{@code balanceAfter} is snapshotted so a statement can be printed without
 * replaying history, while remaining checkable by replaying it.
 */
@Getter
@Setter
@TableName("t_fund_flow")
public class FundFlow {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String flowNo;
    private Long accountId;
    private Long enterpriseId;

    /** {@link Direction}. */
    private String direction;

    /** {@link BizType}. */
    private String bizType;

    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private Long bizId;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    public static final class Direction {
        public static final String IN = "IN";
        public static final String OUT = "OUT";

        private Direction() {
        }
    }

    public static final class BizType {
        public static final String RECHARGE = "RECHARGE";
        public static final String WITHDRAW = "WITHDRAW";
        public static final String MARGIN_FREEZE = "MARGIN_FREEZE";
        public static final String MARGIN_RELEASE = "MARGIN_RELEASE";
        public static final String PAYMENT = "PAYMENT";
        public static final String REFUND = "REFUND";

        private BizType() {
        }

        public static String text(String bizType) {
            if (bizType == null) {
                return "—";
            }
            return switch (bizType) {
                case RECHARGE -> "充值";
                case WITHDRAW -> "提现";
                case MARGIN_FREEZE -> "保证金冻结";
                case MARGIN_RELEASE -> "保证金解冻";
                case PAYMENT -> "货款支付";
                case REFUND -> "退款";
                default -> bizType;
            };
        }
    }
}
