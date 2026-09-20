package com.bulk.trade.shared.audit.mapper;

import com.bulk.trade.shared.audit.AuditLog;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** Insert-only in practice; the read side is the console's audit screen. */
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
