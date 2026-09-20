package com.bulk.trade.identity.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * Issued credentials plus the profile the frontend needs to render its shell.
 *
 * <p>The password hash is never part of this payload — the mapper selects it,
 * but nothing propagates it past the service layer.
 *
 * <p><b>Ids are serialised as strings.</b> A snowflake id is 19 digits, and
 * JavaScript numbers lose precision past 16 — {@code 2101635756223557634}
 * arrives in a browser as {@code 2101635756223557600}. Sending them as JSON
 * numbers makes every id the client echoes back a different id, which surfaces
 * as "record not found" on requests the user just made. Strings survive the
 * round trip unchanged.
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        long expiresInSeconds,
        UserProfile user
) {

    public record UserProfile(
            @JsonSerialize(using = ToStringSerializer.class) Long userId,
            String username,
            String realName,
            @JsonSerialize(using = ToStringSerializer.class) Long enterpriseId,
            String enterpriseName,
            String traderCode,
            Integer userType,
            boolean platformOperator
    ) {
    }
}
