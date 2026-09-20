package com.bulk.trade.shared.audit.mapper;

import com.bulk.trade.shared.audit.AuditLog;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 实践中只做插入；读取那一侧是运营后台的审计页。 */
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
