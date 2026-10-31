package com.mediassistrover.backend.service;

import com.mediassistrover.backend.entity.Account;
import com.mediassistrover.backend.enums.RevocationReason;

import java.time.Instant;

public interface TokenRevocationService {
    boolean isRevoked(String jti);
    void revokeAccessToken(String jti, Account account, Instant expiresAt, RevocationReason reason);
}
