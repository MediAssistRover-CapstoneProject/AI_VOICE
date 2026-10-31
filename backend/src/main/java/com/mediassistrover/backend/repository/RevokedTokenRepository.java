package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.RevokedToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface RevokedTokenRepository extends JpaRepository<RevokedToken, UUID> {
    boolean existsByJti(String jti);
    long deleteByExpiresAtLessThanEqual(Instant threshold);
}
