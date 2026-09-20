package com.bulk.trade.identity.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.util.List;

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
            boolean platformOperator,

            /**
             * Authority strings the account holds, e.g. admin:enterprise:review.
             *
             * <p>Sent so the console can render only what the caller may use.
             * That is a convenience and not a boundary — every one of these is
             * checked again on the server, and a client that invented a code
             * would find the button it drew returns 403.
             */
            List<String> permissions,

            /** Role codes, for display. Authority comes from permissions. */
            List<String> roles
    ) {
    }
}
