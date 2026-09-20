package com.bulk.trade.shared.security;

import com.bulk.trade.shared.exception.BusinessException;
import com.bulk.trade.shared.web.ResultCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Access to the authenticated principal.
 *
 * <p>Business code must go through {@link #currentEnterpriseId()} rather than
 * accepting a tenant id as a method argument. That keeps the tenant boundary in
 * one place and makes cross-tenant access structurally impossible instead of
 * something every developer has to remember to check.
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static LoginUser currentUserOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        if (authentication.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser;
        }
        return null;
    }

    public static LoginUser currentUser() {
        LoginUser user = currentUserOrNull();
        if (user == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        return user;
    }

    public static Long currentUserId() {
        return currentUser().getUserId();
    }

    public static String currentUsername() {
        return currentUser().getUsername();
    }

    /**
     * The tenant key for every business query.
     *
     * @throws BusinessException when the account is not bound to an enterprise
     *                           (platform operators have no tenant scope).
     */
    public static Long currentEnterpriseId() {
        Long enterpriseId = currentUser().getEnterpriseId();
        if (enterpriseId == null) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "当前账号未绑定企业");
        }
        return enterpriseId;
    }

    public static Long currentEnterpriseIdOrNull() {
        LoginUser user = currentUserOrNull();
        return user == null ? null : user.getEnterpriseId();
    }

    public static boolean isAuthenticated() {
        return currentUserOrNull() != null;
    }
}
