package com.bulk.trade.shared.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.bulk.trade.shared.security.SecurityUtils;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * MyBatis-Plus configuration.
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // Pagination. The dbType must be set or MyBatis-Plus cannot pick the
        // right dialect for the LIMIT clause.
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        pagination.setMaxLimit(500L);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        // Turns @Version into an optimistic-lock UPDATE ... WHERE version = ?.
        // This is the mechanism the inventory freeze logic relies on.
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        // Blocks UPDATE/DELETE statements that carry no WHERE clause, which is
        // almost always a bug rather than an intention.
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }

    /**
     * Fills audit columns on insert and update.
     *
     * <p>{@code createdAt} / {@code updatedAt} are also backed by database
     * defaults and a trigger, so rows written by migrations or manual SQL stay
     * consistent. The handler fills them for the normal application path, and
     * the database guarantees they can never be left null.
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
