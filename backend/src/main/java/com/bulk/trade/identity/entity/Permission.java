package com.bulk.trade.identity.entity;

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
 * One thing a platform account may do, or one console screen it may see.
 *
 * <p>{@code code} is the authority string — {@code admin:enterprise:review} —
 * and it is the only field authorization reads. Everything else is for people:
 * the name to show in a checkbox list, the path to show beside it, the order to
 * list them in.
 *
 * <p>There is no {@code enterprise_id}. The catalogue is platform-global: what
 * exists to be granted is the same list for every tenant, and a per-tenant
 * catalogue would mean two operators could not be described with one
 * vocabulary.
 */
@Getter
@Setter
@TableName("t_permission")
public class Permission {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 0 for a top-level entry; used only to nest the console menu. */
    private Long parentId;

    private String code;
    private String name;

    /** 1 = menu, 2 = button, 3 = api. See {@link PermType}. */
    private Integer permType;

    /** The console route this guards, for menu entries. Documentation, not a driver. */
    private String path;

    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    /** What a permission is. The numbers are the database's, stated once. */
    public static final class PermType {
        public static final int MENU = 1;
        public static final int BUTTON = 2;

        private PermType() {
        }
    }
}
