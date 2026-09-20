package com.bulk.trade.advisor.tool;

import com.bulk.trade.shared.security.LoginUser;
import com.bulk.trade.shared.security.SecurityUtils;

/**
 * Identity of the caller a tool runs on behalf of.
 *
 * <p>Tools read the tenant from here — never from the model's arguments. The
 * model cannot name an enterprise, so it cannot ask for another tenant's data
 * even if a prompt tries to make it. That is the entire defence against
 * cross-tenant access through the advisor.
 */
public record AdvisorContext(
        Long userId,
        String username,
        Long enterpriseId,
        boolean platformOperator
) {

    public static AdvisorContext of(LoginUser user) {
        return new AdvisorContext(
                user.getUserId(),
                user.getUsername(),
                user.getEnterpriseId(),
                user.getEnterpriseId() == null);
    }

    public static AdvisorContext current() {
        return of(SecurityUtils.currentUser());
    }

    /** Throws when a tenant-scoped tool is used by an account with no tenant. */
    public Long requireEnterpriseId() {
        if (enterpriseId == null) {
            throw new IllegalStateException("This tool requires an enterprise-scoped account");
        }
        return enterpriseId;
    }
}
