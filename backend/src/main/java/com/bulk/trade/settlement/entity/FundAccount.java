package com.bulk.trade.settlement.entity;

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
 * An enterprise's fund account.
 *
 * <p><b>The balance columns are a cache; the ledger is the truth.</b> Every
 * movement writes to {@link FundFlow} and updates these totals in the same
 * transaction. If they ever disagree, the ledger is right and this row is
 * wrong — which is a reconciliation, not a loss.
 *
 * <p>{@code available + frozen = balance} is enforced by the database, so a
 * slip is rejected on write rather than discovered when someone tries to spend
 * money that was already promised.
 */
@Getter
@Setter
@TableName("t_fund_account")
public class FundAccount {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String accountNo;
    private Long enterpriseId;

    private BigDecimal balance;
    private BigDecimal availableBalance;
    private BigDecimal frozenBalance;

    private String currency;

    /** 0=disabled, 1=active. */
    private Integer status;

    @Version
    private Integer version;

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
}
