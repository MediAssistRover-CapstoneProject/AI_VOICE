package com.mediassistrover.backend.security;

import java.time.Instant;
import java.util.UUID;

public record DeviceJwtClaims(UUID robotId, String robotCode, long tokenVersion,
                              String tokenType, String jti, Instant issuedAt, Instant expiresAt) { }
