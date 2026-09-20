package com.bulk.trade.commodity.entity;

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
 * 平台商品品类树中的一个节点。
 *
 * <p>{@code path} 是物化出来的祖先链，形如 {@code /1001/1002/}。于是子树查询变成
 * 一次前缀匹配（{@code path LIKE '/1001/%'}），而不是递归遍历；当树足够深、以至于
 * 每个请求都跑一次递归 CTE 不再免费时，这一点就重要了。
 */
@Getter
@Setter
@TableName("t_commodity_category")
public class CommodityCategory {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 根节点为 0。 */
    private Long parentId;

    private String code;
    private String name;
    private Integer level;

    /** 物化的祖先链，例如 {@code /1001/1002/}。 */
    private String path;

    private Integer sortOrder;

    /** JSON 数组，描述该品类下的商品必须提供哪些规格字段。 */
    private String specSchema;

    private String unit;

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

    public static final long ROOT_PARENT_ID = 0L;
}
