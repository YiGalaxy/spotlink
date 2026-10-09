package com.spotlink.identity.service.access;

import java.util.List;
import java.util.Collection;
import com.spotlink.identity.entity.Enterprise;
import com.spotlink.identity.mapper.EnterpriseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 跨模块访问入口，沿用调用方事务，不向调用方暴露 ORM 条件。 */
@Service
@RequiredArgsConstructor
public class EnterpriseAccessService implements EnterpriseAccess {
    private final EnterpriseMapper mapper;

    @Override public Enterprise findByCode(String code) {
        return mapper.findByCode(code);
    }

    @Override public int insert(Enterprise entity) {
        return mapper.insert(entity);
    }

    @Override public List<Enterprise> searchEnterprises(Integer status, String keyword) {
        return mapper.searchEnterprises(status, keyword);
    }

    @Override public List<Enterprise> selectBatchIds(Collection<? extends java.io.Serializable> ids) {
        return mapper.selectBatchIds(ids);
    }

    @Override public Enterprise selectById(java.io.Serializable id) {
        return mapper.selectById(id);
    }

    @Override public int updateById(Enterprise entity) {
        return mapper.updateById(entity);
    }
}
