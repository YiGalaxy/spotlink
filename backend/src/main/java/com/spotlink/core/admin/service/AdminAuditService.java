package com.spotlink.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.spotlink.admin.dto.AdminViews;
import com.spotlink.shared.audit.AuditLog;
import com.spotlink.shared.audit.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 回读运营人员做过什么。
 *
 * <p>这张表从 V2 起就存在，带着三个专为这些查询设计的索引，而且在运营后台出现
 * 之前，它完全没有写入方。这里就是它的读取方。
 *
 * <p>倒序且限量。审计轨迹的读法是看刚刚发生了什么，而不限量的读取是导出，不是
 * 一个页面。
 */
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private static final int MAX_ROWS = 200;

    public List<AdminViews.AuditRow> search(String module, String action,
                                            String username, Boolean success, int limit) {
        var query = Wrappers.<AuditLog>lambdaQuery()
                .orderByDesc(AuditLog::getId)
                .last("limit " + Math.min(Math.max(limit, 1), MAX_ROWS));
        if (module != null && !module.isBlank()) {
            query.eq(AuditLog::getModule, module.trim());
        }
        if (action != null && !action.isBlank()) {
            query.eq(AuditLog::getAction, action.trim());
        }
        if (username != null && !username.isBlank()) {
            query.like(AuditLog::getUsername, username.trim());
        }
        if (success != null) {
            query.eq(AuditLog::getSuccess, success);
        }

        return auditLogMapper.selectList(query).stream()
                .map(row -> new AdminViews.AuditRow(
                        row.getId(), row.getUsername(), row.getModule(), row.getAction(),
                        row.getTargetType(), row.getTargetId(),
                        row.getBeforeData(), row.getAfterData(), row.getIp(),
                        row.getSuccess(), row.getErrorMessage(), row.getCreatedAt()))
                .toList();
    }

    private final AuditLogMapper auditLogMapper;
}
