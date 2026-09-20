package com.bulk.trade.shared.security;

import lombok.Builder;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;

/**
 * 保存在 Spring Security 上下文中的已认证主体。
 *
 * <p>{@code enterpriseId} 就是租户键。每一条业务查询都从这里取它，<b>绝不从请求参数取</b>
 * ——把它挂在主体上，全部意义就在于此。
 */
@Getter
@Builder
public class LoginUser implements UserDetails {

    private final Long userId;
    private final String username;
    private final String password;
    private final Long enterpriseId;
    private final String enterpriseName;
    private final Integer userType;
    private final Integer status;
    private final Set<String> permissions;

    /** 平台侧账号不隶属于任何租户。 */
    public boolean isPlatformOperator() {
        return enterpriseId == null;
    }

    /**
     * 同一个身份，但把「它能做什么」填了进去。
     *
     * <p>身份来自令牌，权限来自数据库，两者在这里合流，而不是在任意一端合流。<b>令牌是
     * 唯一能说明调用方是谁的东西</b>；数据库是唯一能说明他们<em>此刻</em>能做什么的东西，
     * 因为权限被收回时，令牌是无法撤回的。
     */
    public LoginUser withAuthority(UserAuthority authority) {
        return LoginUser.builder()
                .userId(userId)
                .username(username)
                .password(password)
                .enterpriseId(enterpriseId)
                .enterpriseName(enterpriseName)
                .userType(userType)
                .status(authority.status())
                .permissions(authority.permissions())
                .build();
    }

    public boolean hasPermission(String code) {
        return permissions != null && permissions.contains(code);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (permissions == null || permissions.isEmpty()) {
            return Set.of();
        }
        return permissions.stream()
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return status == null || status != 2;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return status != null && status == 1;
    }
}
