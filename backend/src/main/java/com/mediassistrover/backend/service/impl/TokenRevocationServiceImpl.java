package com.mediassistrover.backend.service.impl;

import com.mediassistrover.backend.entity.Account;
import com.mediassistrover.backend.entity.RevokedToken;
import com.mediassistrover.backend.enums.RevocationReason;
import com.mediassistrover.backend.repository.RevokedTokenRepository;
import com.mediassistrover.backend.service.TokenRevocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class TokenRevocationServiceImpl implements TokenRevocationService {

    private final RevokedTokenRepository repository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public boolean isRevoked(String jti) {
        return repository.existsByJti(jti);
    }

    @Override
    @Transactional
    public void revokeAccessToken(String jti, Account account, Instant expiresAt, RevocationReason reason) {
        if (jti == null || expiresAt == null || repository.existsByJti(jti)) {
            return;
        }
        Instant now = Instant.now(clock);
        if (!expiresAt.isAfter(now)) {
            return;
        }
        try {
            repository.save(RevokedToken.builder()
                    .jti(jti)
                    .account(account)
                    .expiresAt(expiresAt)
                    .revokedAt(now)
                    .revocationReason(reason)
                    .createdAt(now)
                    .build());
        } catch (DataIntegrityViolationException ignored) {
            // Idempotent logout: a concurrent request may have inserted the same jti.
        }
    }
}
