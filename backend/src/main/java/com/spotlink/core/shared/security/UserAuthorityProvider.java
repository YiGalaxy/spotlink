package com.spotlink.shared.security;

import java.util.Set;

/**
 * 加载一个账号当前的权限。
 *
 * <p>接口放在 {@code shared}，实现在 {@code identity}，因为 {@code shared} 是所有
 * 其他包都依赖的那个包，它自己绝不能反过来依赖其中任何一个。JWT 过滤器住在这里、
 * 需要这个东西，而它要读的那几张表住在那边。
 */
public interface UserAuthorityProvider {

    /**
     * 该账号当前的状态与权限。
     *
     * @return 账号已不存在时为 null——一个指向已删除用户的令牌算不上会话，
     *         调用方应当把它当作**根本没有会话**来处理
     */
    UserAuthority load(Long userId);

    /** 不经缓存读取账号及企业的当前身份；账号或企业不可用时返回 null。 */
    LoginUser currentIdentity(Long userId);

    /** 运营变更事务的第一步：串行化授权变更与最后管理员保护。 */
    void beginMutation();

    /** 在业务事务内推进授权版本；提交后旧缓存键不再被读取。 */
    void evict(Long userId);

    /** 同一事务内推进全局授权版本。 */
    void evictAll();

    /** 账号持有的角色码。用于展示；权限才管授权。 */
    Set<String> rolesOf(Long userId);
}
