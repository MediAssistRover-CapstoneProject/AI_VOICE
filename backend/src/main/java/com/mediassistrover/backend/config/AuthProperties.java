package com.mediassistrover.backend.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        @Min(1) int maxFailedLoginAttempts,
        @Min(1) long temporaryLockMinutes
) {
}
