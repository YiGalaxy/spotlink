package com.spotlink.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spotlink.identity.entity.Enterprise;

/**
 * 企业表的持久化。
 *
 * <p>CRUD 从 {@link BaseMapper} 继承；当某个查询对包装器 API 来说过于复杂或对性能过于
 * 敏感时，手写 SQL 就写在这里。
 */
public interface EnterpriseMapper extends BaseMapper<Enterprise> {
}
