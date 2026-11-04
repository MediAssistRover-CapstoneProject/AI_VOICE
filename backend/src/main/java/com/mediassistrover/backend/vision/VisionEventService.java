package com.mediassistrover.backend.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.DevicePrincipal;
import com.mediassistrover.backend.service.AlertService;
import com.mediassistrover.backend.vision.VisionDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Stores vision observations atomically with any live incident and recipients.
 * Simulation observations never create staff notifications. DetectionEvent is
 * the idempotency record; a robot row lock serializes retries for that device.
 */
@Service
@RequiredArgsConstructor
public class VisionEventService {
    private static final Set<String> METRIC_KEYS = Set.of(
            "torso_angle_deg", "hip_y_normalized", "hip_speed_normalized_per_sec",
            "angular_speed_deg_per_sec", "aspect_ratio", "confidence", "phase",
            "detection_mode", "algorithm", "confidence_kind", "reason", "support_ratio",
            "leg_angle_deg", "shin_angle_deg", "min_shin_angle_deg", "knee_angle_deg",
            "visible_legs", "support_drop", "support_loss_per_sec", "pose_angular_speed_deg_per_sec",
            "baseline_ready", "confirmation_elapsed_sec", "observation_quality",
            "angle_deg", "hip_speed", "angular_speed_deg", "horizontal_duration_sec");
    private static final Set<String> TEXT_METRICS = Set.of(
            "phase", "detection_mode", "algorithm", "confidence_kind", "reason", "observation_quality");
    private final DetectionEventRepository detections;
    private final EmergencyIncidentRepository incidents;
    private final AlertRepository alerts;
    private final RobotRepository robots;
    private final AlertService alertService;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Transactional
    public DetectionReceipt record(DetectionRequest request, DevicePrincipal principal) {
        Instant now = Instant.now(clock);
        validate(request, now);
        if (principal == null || !principal.robotId().equals(request.robotId())) {
            throw new AccessDeniedException("Vision robotId does not match the device token");
        }
        Robot robot = robots.findByIdForUpdate(principal.robotId())
                .orElseThrow(() -> new ResourceNotFoundException("Vision robot not found"));
        if (!Objects.equals(robot.getRobotCode(), principal.robotCode())
                || robot.getDeviceTokenVersion() != principal.tokenVersion()
                || principal.expiresAt() == null || !principal.expiresAt().isAfter(now)) {
            throw new AccessDeniedException("Current device session required for vision ingestion");
        }
        JsonNode source = mapper.valueToTree(request);
        DetectionEvent existing = detections.findById(request.eventId()).orElse(null);
        if (existing != null) {
            if (!existing.getRobot().getRobotId().equals(robot.getRobotId())
                    || !sameContent(existing.getEventPayloadJson(), source)) {
                throw new ConflictException("Vision eventId reused with different robot/content");
            }
            return receipt(existing, request.simulation(), true);
        }
        FacilityMap map = robot.getCurrentMap();
        if (map == null) {
            throw new BusinessRuleException("VISION_MAP_UNAVAILABLE: assign the robot current map before delivery");
        }
        DetectionEvent event = DetectionEvent.builder()
                .detectionEventId(request.eventId()).robot(robot).map(map)
                .eventType(detectionType(request.eventType())).confidenceScore(request.confidence())
                // Map membership is known; object coordinates have not been measured.
                .locationBasis(DetectionLocationBasis.UNKNOWN).modelVersion(request.algorithm())
                .eventPayloadJson(source).detectedAt(request.detectedAt()).receivedAt(now).build();
        detections.saveAndFlush(event);
        if (request.simulation()) {
            return new DetectionReceipt(event.getDetectionEventId(), true, false, true,
                    true, null, null, event.getReceivedAt());
        }
        EmergencyIncidentSeverity severity = "GESTURE_SOS".equals(request.eventType())
                ? EmergencyIncidentSeverity.CRITICAL : EmergencyIncidentSeverity.WARNING;
        String description = description(request, robot, map);
        EmergencyIncident incident = EmergencyIncident.builder().detectionEvent(event)
                .robot(robot).map(map).incidentType(request.eventType()).severity(severity)
                .status(EmergencyIncidentStatus.OPEN).description(description)
                .occurredAt(request.detectedAt()).build();
        incidents.saveAndFlush(incident);
        ObjectNode data = mapper.createObjectNode()
                .put("incident_id", incident.getIncidentId().toString())
                .put("detection_event_id", event.getDetectionEventId().toString())
                .put("robot_id", robot.getRobotId().toString()).put("robot_code", robot.getRobotCode())
                .put("map_id", map.getMapId().toString()).put("map_name", map.getMapName())
                .put("event_type", request.eventType()).put("algorithm", request.algorithm())
                .put("simulation", false).put("requires_staff_verification", true);
        if (request.personId() != null) data.put("person_id", request.personId());
        if (request.confidence() != null) data.put("confidence", request.confidence());
        data.set("metrics", request.metrics().deepCopy());
        Alert alert = alertService.createSystemAlert(operationKey(event.getDetectionEventId()),
                request.eventType(), severity == EmergencyIncidentSeverity.CRITICAL
                        ? AlertSeverity.CRITICAL : AlertSeverity.WARNING,
                description, data, incident, null, null, null,
                true, AlertAckPolicy.ANY, now.plusSeconds(600));
        return new DetectionReceipt(event.getDetectionEventId(), true, false, true,
                false, incident.getIncidentId(), alert.getAlertId(), event.getReceivedAt());
    }

    private DetectionReceipt receipt(DetectionEvent event, boolean simulation, boolean duplicate) {
        EmergencyIncident incident = simulation ? null : incidents
                .findByDetectionEventDetectionEventId(event.getDetectionEventId()).orElse(null);
        Alert alert = simulation ? null : alerts.findByOperationKey(operationKey(event.getDetectionEventId())).orElse(null);
        return new DetectionReceipt(event.getDetectionEventId(), true, duplicate, true, simulation,
                incident == null ? null : incident.getIncidentId(),
                alert == null ? null : alert.getAlertId(), event.getReceivedAt());
    }

    private static String operationKey(UUID id) { return "VISION:" + id; }

    // MySQL JSON/Jackson can read 1.0 as a different numeric node class than
    // the request mapper wrote. Numeric representation must not break retries.
    private static boolean sameContent(JsonNode a, JsonNode b) {
        if (a == null || b == null) return a == b;
        if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue()) == 0;
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) return false;
            Iterator<Map.Entry<String, JsonNode>> fields = a.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!b.has(field.getKey()) || !sameContent(field.getValue(), b.get(field.getKey()))) return false;
            }
            return true;
        }
        return a.equals(b);
    }

    private static DetectionEventType detectionType(String type) {
        return switch (type) {
            case "SUSPECTED_FALL" -> DetectionEventType.SUSPECTED_FALL;
            case "GESTURE_SOS" -> DetectionEventType.SOS_SIGNAL;
            case "SUSPECTED_SEIZURE" -> DetectionEventType.OTHER;
            default -> throw new BusinessRuleException("Unsupported vision eventType");
        };
    }

    private void validate(DetectionRequest r, Instant now) {
        if (r == null || r.eventId() == null || r.robotId() == null || r.simulation() == null
                || r.detectedAt() == null) throw new BusinessRuleException("Vision identity, timestamp and simulation are required");
        detectionType(r.eventType() == null ? "" : r.eventType());
        if (r.detectedAt().isAfter(now.plusSeconds(5))) throw new BusinessRuleException("Vision detectedAt is in the future");
        if (r.algorithm() == null || r.algorithm().isBlank() || r.algorithm().length() > 100)
            throw new BusinessRuleException("Vision algorithm requires 1-100 characters");
        if (r.confidence() != null && (r.confidence().signum() < 0 || r.confidence().compareTo(BigDecimal.ONE) > 0))
            throw new BusinessRuleException("Vision confidence must be between 0 and 1, or null");
        if (r.personId() != null && r.personId() <= 0) throw new BusinessRuleException("Vision personId must be positive");
        if (r.sampleTimeSec() != null && (!Double.isFinite(r.sampleTimeSec()) || r.sampleTimeSec() < 0))
            throw new BusinessRuleException("Vision sampleTimeSec must be finite and non-negative");
        JsonNode metrics = r.metrics();
        if (metrics == null || !metrics.isObject() || metrics.toString().getBytes(StandardCharsets.UTF_8).length > 16_384)
            throw new BusinessRuleException("Vision metrics must be an object of at most 16 KiB");
        metrics.fields().forEachRemaining(entry -> {
            String key = entry.getKey(); JsonNode value = entry.getValue();
            if (!METRIC_KEYS.contains(key)) throw new BusinessRuleException("Unsupported vision metric: " + key);
            if (value.isNull()) return;
            if (TEXT_METRICS.contains(key)) {
                if (!value.isTextual() || value.asText().length() > 256)
                    throw new BusinessRuleException("Vision text metric is invalid: " + key);
            } else if ("baseline_ready".equals(key)) {
                if (!value.isBoolean()) throw new BusinessRuleException("Vision baseline_ready must be boolean");
            } else {
                if (!value.isNumber() || !Double.isFinite(value.doubleValue()) || Math.abs(value.doubleValue()) > 1_000_000)
                    throw new BusinessRuleException("Vision numeric metric is invalid: " + key);
                if ("confidence".equals(key) && (value.doubleValue() < 0 || value.doubleValue() > 1))
                    throw new BusinessRuleException("Vision metric confidence must be between 0 and 1");
            }
        });
    }

    private static String description(DetectionRequest request, Robot robot, FacilityMap map) {
        String kind = switch (request.eventType()) {
            case "SUSPECTED_FALL" -> "người có dấu hiệu té ngã";
            case "GESTURE_SOS" -> "cử chỉ tay cầu cứu";
            default -> "chuyển động bất thường nghi co giật";
        };
        return "CẢNH BÁO AI: Robot " + robot.getRobotCode() + " tại bản đồ '" + map.getMapName()
                + "' phát hiện " + kind
                + (request.personId() == null ? "" : " (đối tượng theo dõi #" + request.personId() + ")")
                + ". Cần nhân viên y tế xác minh; chưa xác định tọa độ người trên bản đồ.";
    }
}
