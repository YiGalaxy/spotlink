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
 * 企业的资金账户。
 *
 * <p><b>余额列是缓存；流水账才是真相。</b>每一次资金变动都写入 {@link FundFlow}
 * 并在同一事务里更新这些总额。若两者出现分歧，流水账是对的，这一行是错的
 * ——那是一件需要对账的事，不是一笔损失。
 *
 * <p>{@code available + frozen = balance} 由数据库强制执行，因此一处失手会在
 * 写入时被拒绝，而不是等到有人试图花掉早已被许诺出去的钱时才发现。
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

    /** 0=停用，1=正常。 */
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
