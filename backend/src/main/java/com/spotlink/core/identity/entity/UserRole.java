package com.spotlink.identity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 一个账号持有哪些角色。
 *
 * <p><b>没有软删除列，这不是疏漏。</b>这份 schema 里其他每一张表都带 {@code deleted}，
 * 好让历史在一次删除之后活下来。在这里，**删除本身就是历史**：撤销一个角色，目的就是
 * 拿走这项授权，而一条软删除的行会让账号仍然持有它——因为每一条查询都得记着去过滤，
 * 忘记一次就等于没撤销。**一项活得比它的撤销更久的授权，是一个安全漏洞，不是一条记录。**
 *
 * <p>所以解除分配是一次硬删除，而「这件事发生过」这个事实保存在审计日志里。
 */
@Getter
@Setter
@TableName("t_user_role")
public class UserRole {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private Long roleId;
    private OffsetDateTime createdAt;
}
