package com.spotlink.admin.service;

import com.spotlink.admin.dto.AdminViews;
import com.spotlink.shared.audit.service.access.AuditLogAccess;
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
        return auditLogAccess.searchRecent(normalize(module), normalize(action), normalize(username),
                success, Math.min(Math.max(limit, 1), MAX_ROWS)).stream()
                .map(row -> new AdminViews.AuditRow(
                        row.getId(), row.getUsername(), row.getModule(), row.getAction(),
                        row.getTargetType(), row.getTargetId(),
                        row.getBeforeData(), row.getAfterData(), row.getIp(),
                        row.getSuccess(), row.getErrorMessage(), row.getCreatedAt()))
                .toList();
    }

    private final AuditLogAccess auditLogAccess;

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
