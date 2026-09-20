package com.bulk.trade.warehouse.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * A designated delivery warehouse (指定交收仓库).
 *
 * <p>Goods sit here, not on the platform. The platform records where they are
 * and what happened to them; it never holds them. That separation is what keeps
 * the platform a registry rather than a counterparty.
 */
@Getter
@Setter
@TableName("t_warehouse")
public class Warehouse {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String code;
    private String name;
    private String shortName;
    private String province;
    private String city;
    private String address;
    private String contactName;
    private String contactPhone;

    /** 0=disabled, 1=enabled. */
    private Integer status;

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
}
