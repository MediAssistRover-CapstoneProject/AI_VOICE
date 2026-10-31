package com.mediassistrover.backend.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.MapStatus;
import com.mediassistrover.backend.enums.StepType;
import com.mediassistrover.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/** Resolve identity against the catalog and the owning Job, never against client assertions. */
@Component
@RequiredArgsConstructor
public class StepInputScopeValidator {
    private final FacilityMapRepository mapRepository;
    private final EndpointRepository endpointRepository;
    private final ZoneRepository zoneRepository;
    private final EndpointGroupRepository groupRepository;
    private final ServiceRequestRepository requestRepository;

    public void validate(Step step, JsonNode inputs, Job job) {
        UUID mapId = id(step, inputs, "map_id");
        FacilityMap jobMap = job == null ? null : job.getMap();
        if (mapId != null) {
            FacilityMap map = mapRepository.findById(mapId).orElse(null);
            if (map == null) fail(step, "map_id", "does not exist");
            if (jobMap != null && !mapId.equals(jobMap.getMapId())) fail(step, "map_id", "is outside the Job map");
            if (step.getStepType() == StepType.MOVE && map.getStatus() != MapStatus.ACTIVE) fail(step, "map_id", "must be ACTIVE for MOVE");
        } else if (jobMap != null) mapId = jobMap.getMapId();
        if (job != null && step.getStepType() == StepType.MOVE && job.getRobot() != null
                && (job.getRobot().getCurrentMap() == null || !Objects.equals(mapId, job.getRobot().getCurrentMap().getMapId())))
            fail(step, "map_id", "does not match the robot current map");
        if (job != null && inputs.has("zone_id") && mapId == null) fail(step, "zone_id", "requires a Job map");
        UUID endpointId = id(step, inputs, "endpoint_id");
        if (endpointId != null) endpoint(step, endpointId, mapId, step.getStepType() == StepType.MOVE);
        UUID zoneId = id(step, inputs, "zone_id");
        if (zoneId != null) {
            Zone zone = zoneRepository.findById(zoneId).orElse(null);
            if (zone == null) fail(step, "zone_id", "does not exist");
            if (!zone.isActive()) fail(step, "zone_id", "must be active");
            sameMap(step, "zone_id", mapId, zone.getMap());
        }
        UUID groupId = id(step, inputs, "endpoint_group_id");
        if (groupId != null) {
            EndpointGroup group = groupRepository.findById(groupId).orElse(null);
            if (group == null) fail(step, "endpoint_group_id", "does not exist");
            if (!group.isActive()) fail(step, "endpoint_group_id", "must be active");
            sameMap(step, "endpoint_group_id", mapId, group.getMap());
        }
        UUID requestId = id(step, inputs, "service_request_id");
        if (requestId != null) {
            if (job != null) {
                if (job.getServiceRequest() == null || !requestId.equals(job.getServiceRequest().getServiceRequestId()))
                    fail(step, "service_request_id", "is outside this Job");
                if ((step.getStepType() == StepType.AUTHENTICATE || step.getStepType() == StepType.HANDLE_PAYLOAD)
                        && job.getServiceRequest().getRequestType() != com.mediassistrover.backend.enums.ServiceRequestType.DELIVERY)
                    fail(step, "service_request_id", "must identify this Job's DELIVERY request");
            } else if (!requestRepository.existsById(requestId)) fail(step, "service_request_id", "does not exist");
        }
        JsonNode targets = inputs.path("targets");
        if (targets.isArray()) for (JsonNode target : targets) {
            if (job == null && target.isObject() && target.has("$ref")) continue;
            UUID targetId = id(step, target, "endpoint_id");
            if (targetId == null) fail(step, "targets.endpoint_id", "must identify an endpoint");
            endpoint(step, targetId, mapId, false);
        }
        if (job != null && step.getStepType() == StepType.HANDLE_PAYLOAD && inputs.has("items")) {
            JsonNode canonical = job.getInputJson().get("items");
            if (canonical == null || !canonical.equals(inputs.get("items"))) fail(step, "items", "must match the Job delivery snapshot");
        }
        if (job != null && inputs.has("authorization"))
            fail(step, "authorization", "must be issued and bound by BE; untrusted authorization is forbidden");
    }

    private void endpoint(Step step, UUID id, UUID mapId, boolean mustBeUsable) {
        Endpoint endpoint = endpointRepository.findById(id).orElse(null);
        if (endpoint == null) fail(step, "endpoint_id", "does not exist");
        sameMap(step, "endpoint_id", mapId, endpoint.getMap());
        if (mustBeUsable && (!endpoint.isActive() || !endpoint.isAccessible())) fail(step, "endpoint_id", "must be active and accessible for MOVE");
    }
    private void sameMap(Step step, String field, UUID expected, FacilityMap actual) {
        if (expected != null && (actual == null || !Objects.equals(expected, actual.getMapId()))) fail(step, field, "belongs to another map");
    }
    private UUID id(Step step, JsonNode n, String field) {
        JsonNode value = n.get(field);
        if (value == null || value.isObject() && value.has("$ref")) return null;
        try { UUID parsed = UUID.fromString(value.asText()); if (!parsed.toString().equalsIgnoreCase(value.asText())) throw new IllegalArgumentException(); return parsed; }
        catch (IllegalArgumentException ex) { StepInputContractValidator.fail(step, field, "must be a canonical UUID"); return null; }
    }
    private void fail(Step step, String field, String reason) { StepInputContractValidator.fail(step, field, reason); }
}
