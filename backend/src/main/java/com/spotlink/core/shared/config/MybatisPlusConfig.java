package com.spotlink.shared.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.spotlink.shared.security.SecurityUtils;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * MyBatis-Plus 配置。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 分页。dbType 必须设置，否则 MyBatis-Plus 无法为 LIMIT 子句挑出
        // 正确的方言。
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(500L);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        // 把 @Version 变成乐观锁的 UPDATE ... WHERE version = ?。
        // 库存冻结那套逻辑依赖的正是这个机制。
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        // 拦截不带 WHERE 子句的 UPDATE/DELETE。那几乎总是一个 bug，
        // 而不是一个意图。
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }

    /**
     * 在插入与更新时填充审计列。
     *
     * <p>{@code createdAt} / {@code updatedAt} 同时也有数据库默认值和触发器兜底，这样由
     * 迁移脚本或手工 SQL 写入的行也保持一致。这个处理器负责正常的应用写入路径，
     * 而数据库保证它们永远不会是空。
     */
    @Component
    public static class AuditMetaObjectHandler implements MetaObjectHandler {

        @Override
        public void insertFill(MetaObject metaObject) {
            OffsetDateTime now = OffsetDateTime.now();
            strictInsertFill(metaObject, "createdAt", OffsetDateTime.class, now);
            strictInsertFill(metaObject, "updatedAt", OffsetDateTime.class, now);

            Long currentUserId = currentUserIdOrNull();
            if (currentUserId != null) {
                strictInsertFill(metaObject, "createdBy", Long.class, currentUserId);
                strictInsertFill(metaObject, "updatedBy", Long.class, currentUserId);
            }
        }

        @Override
        public void updateFill(MetaObject metaObject) {
            strictUpdateFill(metaObject, "updatedAt", OffsetDateTime.class, OffsetDateTime.now());
            Long currentUserId = currentUserIdOrNull();
            if (currentUserId != null) {
                strictUpdateFill(metaObject, "updatedBy", Long.class, currentUserId);
            }
        }

        private Long currentUserIdOrNull() {
            var user = SecurityUtils.currentUserOrNull();
            return user == null ? null : user.getUserId();
        }
    }
}
