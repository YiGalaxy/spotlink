package com.spotlink.identity.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 授权缓存版本，也是低频运营变更的串行化锁。 */
public interface AuthorityRevisionMapper {
    @Select("SELECT revision FROM t_authority_revision WHERE id=1")
    long current();

    @Select("SELECT revision FROM t_authority_revision WHERE id=1 FOR UPDATE")
    long lock();

    @Update("UPDATE t_authority_revision SET revision=revision+1 WHERE id=1")
    int advance();
}
