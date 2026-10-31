package com.mediassistrover.backend.dto.auth.response;

import java.time.Instant;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        Instant accessTokenExpiresAt,
        Instant refreshTokenExpiresAt,
        boolean mustChangePassword
) {
}
