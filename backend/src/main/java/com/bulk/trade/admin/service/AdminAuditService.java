package com.bulk.trade.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.admin.dto.AdminViews;
import com.bulk.trade.shared.audit.AuditLog;
import com.bulk.trade.shared.audit.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Reading back what operators did.
 *
 * <p>The table has existed since V2 with three indexes designed for exactly
 * these queries and, until the console, no writer at all. This is the reader.
 *
 * <p>Newest first and bounded. An audit trail is read by looking at what just
 * happened, and an unbounded one would be an export rather than a screen.
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
