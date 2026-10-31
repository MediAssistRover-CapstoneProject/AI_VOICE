package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from RefreshToken token join fetch token.account where token.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("hash") String hash);

    @Modifying
    @Query("update RefreshToken token set token.revokedAt = :now " +
            "where token.account.accountId = :accountId and token.revokedAt is null")
    int revokeAllForAccount(@Param("accountId") UUID accountId, @Param("now") Instant now);

    @Modifying
    @Query("update RefreshToken token set token.revokedAt = :now " +
            "where token.tokenFamily = :family and token.revokedAt is null")
    int revokeFamily(@Param("family") UUID family, @Param("now") Instant now);

    long deleteByExpiresAtLessThanEqual(Instant threshold);
}
