package com.mediassistrover.backend.dto.pi.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.BatteryUnit;
import com.mediassistrover.backend.enums.RobotStatus;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.UUID;

public record HeartbeatPayload(@NotNull RobotStatus status,
                               @Size(max=64) String runtimeVersion,
                               @Size(max=64) String firmwareVersion,
                               @Min(1) int protocolMajor,
                               @NotNull JsonNode capabilities,
                               @Pattern(regexp="^[A-Fa-f0-9]{64}$") String deployedCatalogHash,
                               @Pattern(regexp="^[A-Fa-f0-9]{64}$") String mapAssetHash,
                               UUID currentMapId,
                               @Min(0) @Max(65535) Integer batteryRaw,
                               BatteryUnit batteryUnit,
                               Instant batteryCapturedAt,
                               @Size(max=100) String errorCode) { }
