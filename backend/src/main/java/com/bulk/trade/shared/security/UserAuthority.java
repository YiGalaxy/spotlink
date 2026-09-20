package com.bulk.trade.shared.security;

import java.util.Set;

/**
 * 一个账号能做什么，实时解析出来，而不是从它的令牌里读。
 *
 * <p><b>为什么这不是一个 JWT 声明。</b>令牌只签发一次，并在它的整个生命周期内被信任
 * ——这里是两小时。而权限的收回必须在这之前生效，声明却是无法撤回的。同样的道理也适用于
 * 账号状态，这正是它也跟着走这里的原因：**一个持有有效令牌的被禁用账号，会一直工作到
 * 令牌过期为止，而那恰恰是禁用它的那个人最不想要的。**
 *
 * @param status      账号状态码；只有 {@link #ACTIVE} 能使用平台
 * @param permissions 权限码，例如 {@code admin:enterprise:review}
 */
public record UserAuthority(int status, Set<String> permissions) {

    /** {@code User.Status.ACTIVE}，在这里重述一遍，好让这个包不依赖别的包。 */
    public static final int ACTIVE = 1;

    public boolean isActive() {
        return status == ACTIVE;
    }

    public boolean has(String code) {
        return permissions != null && permissions.contains(code);
    }

    public static UserAuthority of(int status, Set<String> permissions) {
        return new UserAuthority(status, permissions == null ? Set.of() : Set.copyOf(permissions));
    }
}
