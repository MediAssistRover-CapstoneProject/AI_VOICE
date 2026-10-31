package com.mediassistrover.backend.security;

import com.mediassistrover.backend.enums.Role;

import java.time.Instant;
import java.util.UUID;

public record JwtClaims(
        UUID accountId,
        String username,
        Role role,
        long tokenVersion,
        String tokenType,
        String jti,
        Instant issuedAt,
        Instant expiresAt
) {
}
