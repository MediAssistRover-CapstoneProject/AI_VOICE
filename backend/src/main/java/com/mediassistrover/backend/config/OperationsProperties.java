package com.mediassistrover.backend.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix="app.operations")
public record OperationsProperties(@Min(10) long robotOfflineThresholdSeconds) { }
