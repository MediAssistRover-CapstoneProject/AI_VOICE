package com.mediassistrover.backend.dto.robot.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;

import java.util.UUID;

public final class RobotRequests {
    private RobotRequests() { }
    public record RobotCreateRequest(@NotBlank @Size(max=64) String robotCode,
                                     @NotBlank @Size(max=160) String robotName,
                                     @NotBlank @Size(max=100) String model,
                                     @Size(max=100) String serialNumber,
                                     UUID currentMapId,
                                     @Min(1) int protocolVersion,
                                     @NotNull JsonNode capabilitiesJson) { }
    public record RobotUpdateRequest(@NotBlank @Size(max=160) String robotName,
                                     @NotBlank @Size(max=100) String model,
                                     @Size(max=100) String serialNumber,
                                     UUID currentMapId,
                                     @Min(1) int protocolVersion,
                                     @NotNull JsonNode capabilitiesJson) { }
    public record RobotActionRequest(@NotBlank @Size(max=500) String reason) { }
}
