package com.spotlink.shared.audit.mapper;

import com.spotlink.shared.audit.AuditLog;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** 实践中只做插入；读取那一侧是运营后台的审计页。 */
public interface AuditLogMapper extends BaseMapper<AuditLog> {
    /** 条件和 LIMIT 均绑定参数，查询实现留在持久化层。 */
    @Select("""
            <script>
            SELECT * FROM t_audit_log
            <where>
              <if test="module != null">AND module = #{module}</if>
              <if test="action != null">AND action = #{action}</if>
              <if test="username != null">AND username LIKE CONCAT('%', #{username}, '%')</if>
              <if test="success != null">AND success = #{success}</if>
            </where>
            ORDER BY id DESC LIMIT #{limit}
            </script>
            """)
    List<AuditLog> searchRecent(@Param("module") String module, @Param("action") String action,
                               @Param("username") String username, @Param("success") Boolean success,
                               @Param("limit") int limit);
}
