package com.spotlink.shared.security;

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
 * 签发与校验 JSON Web Token。
 *
 * <p>令牌只携带身份声明（谁、哪个租户、什么类型的账号）。它**刻意不携带权限清单**：
 * 权限变化的频率远高于令牌过期的频率，所以权限改为按请求从缓存里查。把权限放进令牌，
 * 会让一个**已被收回的权限一直有效到令牌过期为止**。
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_ENTERPRISE_ID = "enterpriseId";
    private static final String CLAIM_USER_TYPE = "userType";
    private static final String ISSUER = "spotlink-next";
    private static final String AUDIENCE = "spotlink-api";

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
        return createToken(user, accessTokenTtl, "access");
    }

    public String createRefreshToken(LoginUser user) {
        return createToken(user, refreshTokenTtl, "refresh");
    }

    private String createToken(LoginUser user, Duration ttl, String purpose) {
        Date now = new Date();
        var builder = Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .claim("purpose", purpose)
                .id(java.util.UUID.randomUUID().toString())
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
     * 解析并校验一个令牌。
     *
     * @return 声明；当令牌已过期、被篡改或其他原因无效时为 null——
     *         调用方把 null 当作「未登录」处理。
     */
    public Claims parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(ISSUER)
                    .requireAudience(AUDIENCE)
                    .require("purpose", "access")
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (claims.getExpiration() == null || claims.getIssuedAt() == null
                    || toLoginUser(claims) == null) {
                return null;
            }
            return claims;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Rejected JWT: {}", e.getMessage());
            return null;
        }
    }

    public LoginUser toLoginUser(Claims claims) {
        try {
        Long enterpriseId = claims.get(CLAIM_ENTERPRISE_ID, Number.class) == null
                ? null
                : claims.get(CLAIM_ENTERPRISE_ID, Number.class).longValue();
        Integer userType = claims.get(CLAIM_USER_TYPE, Number.class) == null
                ? null
                : claims.get(CLAIM_USER_TYPE, Number.class).intValue();

        long userId = Long.parseLong(claims.getSubject());
        String username = claims.get(CLAIM_USERNAME, String.class);
        if (userId <= 0 || username == null || username.isBlank() || userType == null
                || (enterpriseId != null && enterpriseId <= 0)) {
            return null;
        }
        return LoginUser.builder()
                .userId(userId)
                .username(username)
                .enterpriseId(enterpriseId)
                .userType(userType)
                // 占位值，由过滤器替换成账号的真实状态。它过去被硬编码为 1 且从未
                // 被替换，于是 isEnabled() 永远是 true：一个在登录之后被禁用的账号，
                // 会一直工作到令牌过期——而这恰好与「禁用」这件事的目的相反。
                .status(1)
                .build();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
