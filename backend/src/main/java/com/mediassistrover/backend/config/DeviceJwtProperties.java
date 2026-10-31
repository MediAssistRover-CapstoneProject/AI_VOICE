package com.mediassistrover.backend.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix="app.device-jwt")
public record DeviceJwtProperties(@NotBlank @Size(min=32) String secret,
                                  @Min(60000) long expirationMs,
                                  @NotBlank String issuer) { }
