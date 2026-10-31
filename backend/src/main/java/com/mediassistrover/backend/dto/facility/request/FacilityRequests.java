package com.mediassistrover.backend.dto.facility.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.EndpointType;
import com.mediassistrover.backend.enums.SelectionPolicy;
import com.mediassistrover.backend.enums.ZoneType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class FacilityRequests {
    private FacilityRequests() { }

    public record HospitalInformationRequest(
            @NotBlank @Size(max = 64) String infoCategory,
            @NotBlank @Size(max = 255) String title,
            @NotBlank String content,
            @NotBlank @Size(max = 16) String languageCode,
            @NotNull JsonNode keywordsJson,
            boolean publicVisible,
            Instant effectiveFrom,
            Instant effectiveUntil) { }

    public record HospitalInformationArticleRequest(
            @NotBlank @Size(max = 80) @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]*") String articleCode,
            @NotBlank @Size(max = 64) String category,
            @NotBlank @Size(max = 500) String canonicalQuestion,
            @NotBlank @Size(max = 255) String title,
            @NotBlank @Size(max = 20000) String answer,
            @NotBlank @Size(max = 16) @Pattern(regexp = "[A-Za-z]{2,3}([_-][A-Za-z0-9]{2,8})*") String languageCode,
            @NotNull JsonNode keywordsJson,
            boolean publicVisible,
            Instant effectiveFrom,
            Instant effectiveUntil) { }

    public record FloorRequest(
            @NotNull UUID hospitalInformationId,
            @NotBlank @Size(max = 64) String floorCode,
            @NotBlank @Size(max = 160) String floorName,
            Integer levelNo,
            @PositiveOrZero int displayOrder,
            String description,
            boolean active) { }

    public record MapRequest(
            @NotNull UUID floorId,
            @NotBlank @Size(max = 64) String mapCode,
            @Min(1) int versionNo,
            @NotBlank @Size(max = 160) String mapName,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal widthM,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal heightM,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal resolutionMPerPixel,
            @NotNull BigDecimal originXM,
            @NotNull BigDecimal originYM,
            @NotNull @DecimalMin("-3.141593") @DecimalMax("3.141593") BigDecimal originYawRad,
            @NotBlank @Size(max = 64) String frameId,
            @NotBlank String mapFileUri,
            @NotBlank @Pattern(regexp = "^[A-Fa-f0-9]{64}$") String assetHash) { }

    public record ZoneRequest(
            @NotNull UUID mapId,
            @NotBlank @Size(max = 64) String zoneCode,
            @NotBlank @Size(max = 160) String zoneName,
            @NotNull ZoneType zoneType,
            @NotNull JsonNode polygonJson,
            @DecimalMin(value = "0", inclusive = false) BigDecimal speedLimitMps,
            @PositiveOrZero Integer capacityLimit,
            @DecimalMin(value = "0", inclusive = false) BigDecimal calibratedAreaM2,
            @Size(max = 255) String cameraSourceRef,
            JsonNode cameraRoiJson,
            boolean active,
            boolean publicVisible,
            String description) { }

    public record RoomRequest(
            @NotNull UUID floorId,
            UUID defaultEndpointGroupId,
            @NotBlank @Size(max = 64) String roomCode,
            @NotBlank @Size(max = 160) String roomName,
            @NotBlank @Size(max = 64) String roomType,
            @Size(max = 64) String departmentCode,
            String description,
            boolean active,
            boolean publicVisible) { }

    public record EndpointRequest(
            @NotNull UUID mapId,
            UUID zoneId,
            UUID roomId,
            @NotBlank @Size(max = 64) String endpointCode,
            @NotBlank @Size(max = 160) String endpointName,
            @NotNull EndpointType endpointType,
            @NotNull BigDecimal xM,
            @NotNull BigDecimal yM,
            @NotNull @DecimalMin("-3.141593") @DecimalMax("3.141593") BigDecimal yawRad,
            boolean active,
            boolean accessible,
            boolean publicVisible,
            String description) { }

    public record EndpointGroupRequest(
            @NotNull UUID mapId,
            @NotBlank @Size(max = 64) String groupCode,
            @NotBlank @Size(max = 160) String groupName,
            @NotNull SelectionPolicy selectionPolicy,
            boolean allowFallback,
            String description,
            boolean active) { }

    public record EndpointGroupMemberRequest(
            @NotNull UUID endpointGroupId,
            @NotNull UUID endpointId,
            @Min(1) int sequenceNo,
            boolean active) { }
}
