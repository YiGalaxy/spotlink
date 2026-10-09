package com.spotlink.identity.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spotlink.identity.entity.Enterprise;

/**
 * 企业表的持久化。
 *
 * <p>CRUD 从 {@link BaseMapper} 继承；所有查询条件封装为这里的命名方法，
 * 对业务层只暴露参数和结果。复杂查询使用参数化 SQL。
 */
public interface EnterpriseMapper extends BaseMapper<Enterprise> {

    default Enterprise findByCode(String code) {
        return selectOne(Wrappers.<Enterprise>lambdaQuery().eq(Enterprise::getEnterpriseCode, code));
    }

    default List<Enterprise> searchEnterprises(Integer status, String keyword) {
        var query = Wrappers.<Enterprise>lambdaQuery().orderByDesc(Enterprise::getId);
        if (status != null) query.eq(Enterprise::getStatus, status);
        if (keyword != null && !keyword.isBlank()) query.and(w -> w.like(Enterprise::getName, keyword.trim()).or().like(Enterprise::getEnterpriseCode, keyword.trim()).or().like(Enterprise::getUnifiedSocialCreditCode, keyword.trim()));
        return selectList(query);
    }
}
