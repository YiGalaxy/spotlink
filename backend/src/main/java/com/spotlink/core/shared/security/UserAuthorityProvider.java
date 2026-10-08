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

    /** 变更后丢掉缓存的那一份，让下一个请求看得见。 */
    void evict(Long userId);

    /** 丢掉所有缓存的那一份，用于一次影响很多账号的变更。 */
    void evictAll();

    /** 账号持有的角色码。用于展示；权限才管授权。 */
    Set<String> rolesOf(Long userId);
}
