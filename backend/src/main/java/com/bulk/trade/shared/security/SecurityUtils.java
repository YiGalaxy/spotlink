package com.bulk.trade.shared.security;

import com.bulk.trade.shared.exception.BusinessException;
import com.bulk.trade.shared.web.ResultCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 读取已认证主体的入口。
 *
 * <p>业务代码必须走 {@link #currentEnterpriseId()}，而不是把租户 id 当作方法参数
 * 收进来。这让租户边界只存在于一个地方，也让跨租户访问变成**结构上不可能**，
 * 而不是一件每个开发者都得记得去检查的事。
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
     * 每一条业务查询的租户键。
     *
     * @throws BusinessException 当账号没有绑定企业时
     *                           （平台运营账号没有租户范围）。
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
