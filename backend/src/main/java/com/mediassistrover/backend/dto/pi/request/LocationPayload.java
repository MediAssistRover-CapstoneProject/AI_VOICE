package com.mediassistrover.backend.dto.pi.request;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LocationPayload(@NotNull UUID mapId,
                              @NotNull BigDecimal xM,
                              @NotNull BigDecimal yM,
                              @NotNull @DecimalMin("-3.141593") @DecimalMax("3.141593") BigDecimal yawRad,
                              @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal localizationConfidence,
                              boolean fresh,
                              @NotNull Instant capturedAt) { }
