package com.mediassistrover.backend.security;

import com.mediassistrover.backend.config.DeviceJwtProperties;
import com.mediassistrover.backend.entity.Robot;
import com.mediassistrover.backend.exception.InvalidTokenException;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class DeviceJwtTokenService {
    public static final String DEVICE_TOKEN_TYPE="DEVICE_ACCESS";
    private final DeviceJwtProperties properties;
    private final Clock clock;
    private final SecretKey key;
    public DeviceJwtTokenService(DeviceJwtProperties properties,Clock clock){this.properties=properties;this.clock=clock;this.key=Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));}
    public IssuedDeviceToken issue(Robot robot){Instant issued=Instant.now(clock);Instant expires=issued.plusMillis(properties.expirationMs());String jti=UUID.randomUUID().toString();String value=Jwts.builder().issuer(properties.issuer()).subject(robot.getRobotCode()).id(jti).issuedAt(Date.from(issued)).expiration(Date.from(expires)).claim("robotId",robot.getRobotId().toString()).claim("robotCode",robot.getRobotCode()).claim("role","DEVICE").claim("tokenType",DEVICE_TOKEN_TYPE).claim("tokenVersion",robot.getDeviceTokenVersion()).signWith(key).compact();return new IssuedDeviceToken(value,expires);}
    public DeviceJwtClaims parse(String token){try{Claims c=Jwts.parser().verifyWith(key).requireIssuer(properties.issuer()).build().parseSignedClaims(token).getPayload();return new DeviceJwtClaims(UUID.fromString(c.get("robotId",String.class)),c.get("robotCode",String.class),c.get("tokenVersion",Number.class).longValue(),c.get("tokenType",String.class),c.getId(),c.getIssuedAt().toInstant(),c.getExpiration().toInstant());}catch(JwtException|IllegalArgumentException e){throw new InvalidTokenException("Device token không hợp lệ hoặc đã hết hạn");}}
    public record IssuedDeviceToken(String value,Instant expiresAt) { }
}
