package com.mediassistrover.backend.dto.auth.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshTokenRequest(
        @NotBlank @Size(max = 500) String refreshToken,
        @Size(max = 500) String deviceInfo
) {
}
