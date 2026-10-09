package com.spotlink.shared.audit.service.access;

import com.spotlink.shared.audit.AuditLog;
import java.util.List;
import java.util.List;
import com.spotlink.shared.audit.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 跨模块访问入口，沿用调用方事务，不向调用方暴露 ORM 条件。 */
@Service
@RequiredArgsConstructor
public class AuditLogAccessService implements AuditLogAccess {
    private final AuditLogMapper mapper;

    @Override public int insert(AuditLog entity) {
        return mapper.insert(entity);
    }

    @Override public List<AuditLog> searchRecent(String module, String action, String username, Boolean success, int limit) {
        return mapper.searchRecent(module, action, username, success, limit);
    }
}
