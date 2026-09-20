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
 * 一次资金变动。只追加。
 *
 * <p>从不更新，从不删除。错误通过写入一条冲正记录来更正，因为一本能被编辑的
 * 账本无法被审计——而在一个交易平台上，账本正是用来了结争议的那件东西。
 *
 * <p>{@code balanceAfter} 被快照下来，这样打印一份对账单无需重放历史，同时
 * 它又仍然可以通过重放历史来核对。
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

    /** {@link Direction}。 */
    private String direction;

    /** {@link BizType}。 */
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
