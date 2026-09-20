package com.bulk.trade.identity.dto;

/**
 * Issued credentials plus the profile the frontend needs to render its shell.
 *
 * <p>The password hash is never part of this payload — the mapper selects it,
 * but nothing propagates it past the service layer.
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        long expiresInSeconds,
        UserProfile user
) {

    public record UserProfile(
            Long userId,
            String username,
            String realName,
            Long enterpriseId,
            String enterpriseName,
            String traderCode,
            Integer userType,
            boolean platformOperator
    ) {
    }
}
