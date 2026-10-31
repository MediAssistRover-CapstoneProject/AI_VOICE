package com.mediassistrover.backend.service;

import com.mediassistrover.backend.entity.Account;
import com.mediassistrover.backend.enums.RevocationReason;

import java.time.Instant;

public interface RefreshTokenService {
    IssuedRefreshToken issue(Account account, String deviceInfo, String ipAddress);
    RotationResult rotate(String rawToken, String deviceInfo, String ipAddress);
    void revoke(String rawToken, Account account);
    void revokeAll(Account account, RevocationReason reason);

    record IssuedRefreshToken(String value, Instant expiresAt) {
    }

    record RotationResult(Account account, IssuedRefreshToken token) {
    }
}
