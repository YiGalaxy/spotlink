package com.spotlink.shared.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Date;
import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {
    private static final String SECRET = "YnVsay10cmFkZS1kZXYtc2VjcmV0LWtleS1jaGFuZ2UtbWUtMjAyNg==";
    private final JwtTokenProvider provider = new JwtTokenProvider(SECRET, Duration.ofMinutes(5), Duration.ofDays(1));
    private final LoginUser user = LoginUser.builder().userId(8700000000000000001L)
            .username("seller").enterpriseId(8700000000000000002L).userType(1).status(1).build();

    @Test void accessRoundTripRetainsExactIdentityAndUniqueTokenId() {
        var claims = provider.parse(provider.createAccessToken(user));
        assertThat(provider.toLoginUser(claims).getEnterpriseId()).isEqualTo(user.getEnterpriseId());
        assertThat(claims.getId()).isNotEqualTo(provider.parse(provider.createAccessToken(user)).getId());
    }

    @Test void refreshCannotBeUsedAsAccess() {
        assertThat(provider.parse(provider.createRefreshToken(user))).isNull();
    }

    @Test void legacyMissingPurposeWrongAudienceAndMalformedIdentityAreRejected() {
        for (String audience : new String[]{"spotlink-api", "other"}) {
            var legacy = Jwts.builder().subject("1").issuer("spotlink-next").audience().add(audience).and()
                    .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60000))
                    .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).compact();
            assertThat(provider.parse(legacy)).isNull();
        }
        var malformed = Jwts.builder().subject("bad-id").issuer("spotlink-next")
                .audience().add("spotlink-api").and().claim("purpose", "access")
                .claim("username", "seller").claim("userType", 1).issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).compact();
        assertThat(provider.parse(malformed)).isNull();
    }

    @Test void expiredTamperedAndUnsignedAreRejected() {
        var expired = new JwtTokenProvider(SECRET, Duration.ofSeconds(-1), Duration.ofDays(1));
        assertThat(provider.parse(expired.createAccessToken(user))).isNull();
        assertThat(provider.parse("invalid")).isNull();
        assertThat(provider.parse(Jwts.builder().subject("1").compact())).isNull();
    }
}
