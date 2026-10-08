package com.spotlink.warehouse.entity;

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
 * 指定交收仓库。
 *
 * <p>货物放在这里，而不是放在平台上。平台记录货物在哪里、经历过什么，但它从不持有
 * 货物。正是这种分离，让平台保持为一个登记机构，而不是交易的对手方。
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

    /** 0=禁用，1=启用。 */
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
