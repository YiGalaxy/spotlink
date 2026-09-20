package com.bulk.trade.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Date;

/**
 * Issues and verifies JSON Web Tokens.
 *
 * <p>The token carries identity claims only (who, which tenant, what kind of
 * account). It deliberately does NOT carry the permission list: permissions
 * change far more often than tokens expire, so they are looked up per request
 * from cache instead. Putting them in the token would leave a revoked
 * permission valid until the token expires.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_ENTERPRISE_ID = "enterpriseId";
    private static final String CLAIM_USER_TYPE = "userType";

    private final SecretKey secretKey;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;

    public JwtTokenProvider(
            @Value("${bulk.security.jwt.secret}") String secret,
            @Value("${bulk.security.jwt.access-token-ttl}") Duration accessTokenTtl,
            @Value("${bulk.security.jwt.refresh-token-ttl}") Duration refreshTokenTtl) {
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.accessTokenTtl = accessTokenTtl;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public String createAccessToken(LoginUser user) {
        return createToken(user, accessTokenTtl);
    }

    public String createRefreshToken(LoginUser user) {
        return createToken(user, refreshTokenTtl);
    }

    private String createToken(LoginUser user, Duration ttl) {
        Date now = new Date();
        var builder = Jwts.builder()
                .subject(String.valueOf(user.getUserId()))
                .claim(CLAIM_USERNAME, user.getUsername())
                .claim(CLAIM_USER_TYPE, user.getUserType())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttl.toMillis()))
                .signWith(secretKey);
        if (user.getEnterpriseId() != null) {
            builder.claim(CLAIM_ENTERPRISE_ID, user.getEnterpriseId());
        }
        return builder.compact();
    }

    /**
     * Parses and verifies a token.
     *
     * @return the claims, or {@code null} when the token is expired, tampered
     *         with, or otherwise invalid — callers treat null as "not logged in".
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Rejected JWT: {}", e.getMessage());
            return null;
        }
    }

    public LoginUser toLoginUser(Claims claims) {
        Long enterpriseId = claims.get(CLAIM_ENTERPRISE_ID, Number.class) == null
                ? null
                : claims.get(CLAIM_ENTERPRISE_ID, Number.class).longValue();
        Integer userType = claims.get(CLAIM_USER_TYPE, Number.class) == null
                ? null
                : claims.get(CLAIM_USER_TYPE, Number.class).intValue();

        return LoginUser.builder()
                .userId(Long.valueOf(claims.getSubject()))
                .username(claims.get(CLAIM_USERNAME, String.class))
                .enterpriseId(enterpriseId)
                .userType(userType)
                .status(1)
                .build();
    }
}
