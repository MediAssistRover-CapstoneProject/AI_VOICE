package com.mediassistrover.backend.dto.robot.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class RobotResponses {
    private RobotResponses() { }
    public record RobotResponse(UUID robotId,UUID currentMapId,String robotCode,String robotName,String model,
            String serialNumber,String runtimeVersion,String firmwareVersion,int protocolVersion,JsonNode capabilitiesJson,
            String deployedCatalogHash,String mapAssetHash,RobotStatus status,Integer batteryRaw,BatteryUnit batteryUnit,
            BigDecimal batteryPercent,Instant batteryCapturedAt,Instant lastHeartbeatAt,Instant commissionedAt,
            long rowVersion,Instant createdAt,Instant updatedAt) { }
    public record DeviceSecretResponse(UUID robotId,String robotCode,String deviceSecret,Instant issuedAt) { }
    public record RobotStatusHistoryResponse(UUID robotStatusHistoryId,UUID robotId,UUID jobId,
            RobotStatus previousStatus,RobotStatus newStatus,String reason,String errorCode,Instant recordedAt) { }
    public record RobotLocationResponse(UUID robotLocationHistoryId,UUID robotId,UUID mapId,UUID jobId,
            BigDecimal xM,BigDecimal yM,BigDecimal yawRad,BigDecimal localizationConfidence,String frameId,String source,boolean fresh,
            Instant capturedAt,Instant receivedAt) { }
}
