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
 * A node in the platform's commodity catalogue tree.
 *
 * <p>{@code path} is a materialised ancestor chain such as {@code /1001/1002/}.
 * Subtree queries become a prefix match ({@code path LIKE '/1001/%'}) rather
 * than a recursive walk, which matters once the tree is deep enough that a
 * recursive CTE per request stops being free.
 */
@Getter
@Setter
@TableName("t_commodity_category")
public class CommodityCategory {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 0 for a root node. */
    private Long parentId;

    private String code;
    private String name;
    private Integer level;

    /** Materialised ancestors, e.g. {@code /1001/1002/}. */
    private String path;

    private Integer sortOrder;

    /** JSON array describing the spec fields a commodity here must supply. */
    private String specSchema;

    private String unit;

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

    public static final long ROOT_PARENT_ID = 0L;
}
