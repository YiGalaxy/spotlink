package com.bulk.trade.shared.security;

import java.util.Set;

/**
 * Loads an account's live authority.
 *
 * <p>An interface in {@code shared} with its implementation in {@code identity},
 * because {@code shared} is the package everything else depends on and must not
 * depend on any of them. The JWT filter lives here and needs this; the tables it
 * reads live there.
 */
public interface UserAuthorityProvider {

    /**
     * The account's current status and permissions.
     *
     * @return null when the account no longer exists — a token naming a deleted
     *         user is not a session, and the caller should treat it as no
     *         session at all
     */
    UserAuthority load(Long userId);

    /** Drops the cached copy after a change, so the next request sees it. */
    void evict(Long userId);

    /** Drops every cached copy, for changes that affect many accounts at once. */
    void evictAll();

    /** Role codes the account holds. For display; authority comes from permissions. */
    Set<String> rolesOf(Long userId);
}
