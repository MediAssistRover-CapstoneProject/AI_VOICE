package com.mediassistrover.backend.service.impl;

import com.mediassistrover.backend.config.JwtProperties;
import com.mediassistrover.backend.entity.Account;
import com.mediassistrover.backend.entity.RefreshToken;
import com.mediassistrover.backend.enums.AccountStatus;
import com.mediassistrover.backend.enums.RevocationReason;
import com.mediassistrover.backend.exception.InvalidTokenException;
import com.mediassistrover.backend.repository.RefreshTokenRepository;
import com.mediassistrover.backend.service.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenServiceImpl implements RefreshTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final RefreshTokenRepository repository;
    private final JwtProperties properties;
    private final Clock clock;

    @Override
    @Transactional
    public IssuedRefreshToken issue(Account account, String deviceInfo, String ipAddress) {
        return create(account, UUID.randomUUID(), deviceInfo, ipAddress).issued();
    }

    @Override
    @Transactional(noRollbackFor = InvalidTokenException.class)
    public RotationResult rotate(String rawToken, String deviceInfo, String ipAddress) {
        Instant now = Instant.now(clock);
        RefreshToken current = repository.findByTokenHashForUpdate(hash(rawToken))
                .orElseThrow(() -> new InvalidTokenException("Refresh token không hợp lệ"));

        if (current.isRevoked()) {
            repository.revokeFamily(current.getTokenFamily(), now);
            throw new InvalidTokenException("Phát hiện refresh token đã được sử dụng lại; token family đã bị thu hồi");
        }
        if (current.isExpired(now)) {
            current.setRevokedAt(now);
            throw new InvalidTokenException("Refresh token đã hết hạn");
        }
        if (current.getAccount().getStatus() != AccountStatus.ACTIVE) {
            current.setRevokedAt(now);
            throw new InvalidTokenException("Tài khoản không hoạt động");
        }
        if (current.getAccount().isMustChangePassword()) {
            throw new InvalidTokenException("Change the temporary password before refreshing tokens");
        }

        CreatedToken replacement = create(current.getAccount(), current.getTokenFamily(), deviceInfo, ipAddress);
        current.setRevokedAt(now);
        current.setReplacedBy(replacement.entity());
        repository.save(current);
        return new RotationResult(current.getAccount(), replacement.issued());
    }

    @Override
    @Transactional
    public void revoke(String rawToken, Account account) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        repository.findByTokenHashForUpdate(hash(rawToken)).ifPresent(token -> {
            if (token.getAccount().getAccountId().equals(account.getAccountId()) && !token.isRevoked()) {
                token.setRevokedAt(Instant.now(clock));
            }
        });
    }

    @Override
    @Transactional
    public void revokeAll(Account account, RevocationReason reason) {
        repository.revokeAllForAccount(account.getAccountId(), Instant.now(clock));
    }

    private CreatedToken create(Account account, UUID family, String deviceInfo, String ipAddress) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plusMillis(properties.refreshExpirationMs());
        byte[] tokenBytes = new byte[48];
        SECURE_RANDOM.nextBytes(tokenBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        RefreshToken entity = repository.save(RefreshToken.builder()
                .account(account)
                .tokenHash(hash(rawToken))
                .tokenFamily(family)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .deviceInfo(trimTo(deviceInfo, 500))
                .ipAddress(trimTo(ipAddress, 45))
                .createdAt(now)
                .build());
        return new CreatedToken(entity, new IssuedRefreshToken(rawToken, expiresAt));
    }

    private String hash(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 không khả dụng", exception);
        }
    }

    private String trimTo(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(value.length(), max));
    }

    private record CreatedToken(RefreshToken entity, IssuedRefreshToken issued) {
    }
}
