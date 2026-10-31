package com.mediassistrover.backend.security;

import com.mediassistrover.backend.config.JwtProperties;
import com.mediassistrover.backend.entity.Account;
import com.mediassistrover.backend.enums.Role;
import com.mediassistrover.backend.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtTokenService {

    public static final String USER_TOKEN_TYPE = "USER_ACCESS";
    private final JwtProperties properties;
    private final Clock clock;
    private final SecretKey signingKey;

    public JwtTokenService(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public IssuedAccessToken issueAccessToken(Account account) {
        Instant issuedAt = Instant.now(clock);
        Instant expiresAt = issuedAt.plusMillis(properties.expirationMs());
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .issuer(properties.issuer())
                .subject(account.getUsername())
                .id(jti)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .claim("accountId", account.getAccountId().toString())
                .claim("role", account.getRole().name())
                .claim("tokenType", USER_TOKEN_TYPE)
                .claim("tokenVersion", account.getTokenVersion())
                .signWith(signingKey)
                .compact();
        return new IssuedAccessToken(token, jti, issuedAt, expiresAt);
    }

    public JwtClaims parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new JwtClaims(
                    UUID.fromString(claims.get("accountId", String.class)),
                    claims.getSubject(),
                    Role.valueOf(claims.get("role", String.class)),
                    claims.get("tokenVersion", Number.class).longValue(),
                    claims.get("tokenType", String.class),
                    claims.getId(),
                    claims.getIssuedAt().toInstant(),
                    claims.getExpiration().toInstant()
            );
        } catch (JwtException | IllegalArgumentException exception) {
            throw new InvalidTokenException("Access token không hợp lệ hoặc đã hết hạn");
        }
    }

    public record IssuedAccessToken(String value, String jti, Instant issuedAt, Instant expiresAt) {
    }
}
