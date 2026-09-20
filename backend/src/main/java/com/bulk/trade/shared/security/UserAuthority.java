package com.bulk.trade.shared.security;

import java.util.Set;

/**
 * What an account may do, resolved live rather than read from its token.
 *
 * <p><b>Why this is not a JWT claim.</b> A token is issued once and trusted for
 * its whole lifetime — two hours here. Permission revocation has to take effect
 * in less than that, and a claim cannot be un-issued. The same argument applies
 * to account status, which is why it travels here too: a disabled account
 * holding a valid token is one that keeps working until the token expires,
 * which is precisely when someone disabling it wanted the opposite.
 *
 * @param status      the account's status code; {@link #ACTIVE} is the only one
 *                    that can use the platform
 * @param permissions authority strings, e.g. {@code admin:enterprise:review}
 */
public record UserAuthority(int status, Set<String> permissions) {

    /** {@code User.Status.ACTIVE}, restated so this package needs no dependency. */
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
