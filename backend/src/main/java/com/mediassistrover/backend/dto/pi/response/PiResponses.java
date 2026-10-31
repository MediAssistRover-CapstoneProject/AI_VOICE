package com.mediassistrover.backend.dto.pi.response;

import com.mediassistrover.backend.enums.MapStatus;
import com.mediassistrover.backend.enums.RobotStatus;
import com.mediassistrover.backend.enums.EndpointType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.JobStepStatus;

public final class PiResponses {
    private PiResponses() { }
    public record DeviceTokenResponse(String accessToken,String tokenType,Instant expiresAt,UUID robotId) { }
    public record AckResponse(UUID messageId,String operationKey,UUID correlationId,boolean accepted,
            boolean duplicate,Instant serverTime,RobotStatus robotStatus,boolean catalogSyncRequired,
            boolean mapSyncRequired,String reason) { }
    public record CatalogManifestResponse(String protocolVersion,UUID robotId,UUID mapId,Integer mapVersion,
            String mapHash,String mapUri,String deployedCatalogHash,String expectedCatalogHash,
            boolean catalogSyncRequired,boolean mapSyncRequired,List<EndpointManifest> endpoints) { }
    public record EndpointManifest(UUID endpointId,String endpointCode,EndpointType endpointType,
            String xM,String yM,String yawRad,boolean active) { }
    public record PiMapMetadata(UUID mapId,String mapCode,int versionNo,String mapFileUri,String assetHash,
            String frameId,MapStatus status) { }
    public record JobCommandResponse(String protocolVersion,UUID commandId,String commandType,UUID correlationId,
            UUID robotId,UUID jobId,String workflowHash,int schemaVersion,int graphVersion,Instant expiresAt,
            JsonNode workflowDefinition,JsonNode jobInput,JsonNode context,List<JobStepCommand> jobSteps) { }
    public record JobStepCommand(UUID jobStepId,UUID stepId,String stepCode,int visitNo,
            String operationKey,JobStepStatus status) { }
    public record StepServiceExecutionResponse(UUID messageId,boolean duplicate,UUID jobStepId,
            JobStepStatus status,JsonNode result,Instant serverTime) { }
}
