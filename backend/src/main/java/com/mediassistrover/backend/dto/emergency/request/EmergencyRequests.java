package com.mediassistrover.backend.dto.emergency.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.EmergencyIncidentSeverity;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public final class EmergencyRequests {
    private EmergencyRequests() { }
    public record SosPayload(UUID mapId,UUID endpointId,@NotNull EmergencyIncidentSeverity severity,
                             @NotBlank @Size(max=4000) String description,
                             @Digits(integer=8,fraction=4) BigDecimal xM,@Digits(integer=8,fraction=4) BigDecimal yM) { }
    public record Resolution(@NotBlank @Size(max=4000) String resolutionNote,JsonNode evidence) {
        public Resolution(String resolutionNote) { this(resolutionNote,null); }
    }
}
