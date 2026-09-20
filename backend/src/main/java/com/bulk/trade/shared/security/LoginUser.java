package com.bulk.trade.shared.security;

import lombok.Builder;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;

/**
 * Authenticated principal held in the Spring Security context.
 *
 * <p>{@code enterpriseId} is the tenant key. Every business query derives it
 * from here, never from a request parameter — that is the whole point of
 * carrying it on the principal.
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

    /** Platform-side accounts are not scoped to a tenant. */
    public boolean isPlatformOperator() {
        return enterpriseId == null;
    }

    /**
     * The same identity, with what it may do filled in.
     *
     * <p>Identity comes from the token and authority comes from the database,
     * and they are joined here rather than at either end. The token is the only
     * thing that can say who the caller is; the database is the only thing that
     * can say what they may currently do, because a token cannot be un-issued
     * when a permission is taken away.
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
