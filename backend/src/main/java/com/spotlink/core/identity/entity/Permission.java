package com.spotlink.identity.entity;

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
 * 一个平台账号可以做的一件事，或它能看见的一个后台页面。
 *
 * <p>{@code code} 是权限码——{@code admin:enterprise:review}——**而它是授权逻辑
 * 唯一读取的字段**。其余字段都是给人看的：勾选列表里显示的名字、旁边显示的路由、
 * 以及排列它们的顺序。
 *
 * <p>这里没有 {@code enterprise_id}。这份目录是平台全局的：可以被授予的东西，
 * 对每个租户都是同一份清单；而按租户各存一份目录，就意味着两个运营账号无法用
 * 同一套词汇来描述。
 */
@Getter
@Setter
@TableName("t_permission")
public class Permission {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 0 表示顶层条目；只用来把后台菜单嵌套起来。 */
    private Long parentId;

    private String code;
    private String name;

    /** 1 = 菜单，2 = 按钮，3 = 接口。见 {@link PermType}。 */
    private Integer permType;

    /** 这一项守护的后台路由，仅菜单条目用。是文档，不是驱动。 */
    private String path;

    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    /** 一条权限是什么。这些数字属于数据库，在这里只陈述一次。 */
    public static final class PermType {
        public static final int MENU = 1;
        public static final int BUTTON = 2;

        private PermType() {
        }
    }
}
