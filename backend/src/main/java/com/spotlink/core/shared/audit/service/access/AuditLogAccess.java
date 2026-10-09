package com.spotlink.shared.audit.service.access;

import com.spotlink.shared.audit.AuditLog;
import java.util.List;
import java.util.List;

/** 模块对外的数据访问契约；查询实现由本模块 Mapper 负责。 */
public interface AuditLogAccess {
    int insert(AuditLog entity);
    List<AuditLog> searchRecent(String module, String action, String username, Boolean success, int limit);
}
