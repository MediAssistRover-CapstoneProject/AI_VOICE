package com.mediassistrover.backend.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.enums.StepType;
import com.mediassistrover.backend.exception.BusinessRuleException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

/** Wire shape plus relationships which JSON Schema cannot express. Schema 2 remains independent. */
final class Schema3StepResultContractValidator {
    private final FixedContractSchemaValidator schemas = new FixedContractSchemaValidator();

    void validate(StepType type, JsonNode input, JsonNode result) {
        boolean pi = Set.of(StepType.MOVE, StepType.CONTROL_HARDWARE, StepType.EXECUTE_SENSING).contains(type)
                || type == StepType.WAIT && "DURATION".equals(input.path("wait_type").asText());
        schemas.validate(pi ? "pi-step-outputs.schema.json" : "be-step-contracts.json", type.name() + (pi ? "" : "Output"), result);
        switch (type) {
            case MOVE -> {
                if (!result.path("action_status").isIntegralNumber()) invalid("action_status must be an integer");
                same(input, result, "endpoint_id");
                nonNegativeNullable(result, "distance_remaining_m");
                for (JsonNode attempt : result.path("attempts")) same(input, attempt, "endpoint_id");
            }
            case CONTROL_HARDWARE -> {
                same(input, result, "action"); same(input, result, "compartment_id");
                if (!result.path("position_raw").isIntegralNumber() || !result.path("ros_servo_id").isIntegralNumber()) invalid("servo position/id must be integers");
            }
            case EXECUTE_SENSING -> sensing(input, result);
            case INTERACT -> {
                same(input, result, "action"); same(input, result, "prompt_key");
                if ("SPEAK".equals(input.path("action").asText()) && !"AUDIO_CLIENT".equals(result.path("source").asText())) invalid("SPEAK must complete from AUDIO_CLIENT");
            }
            case DATA_SERVICE -> data(input, result.path("data"));
            case AUTHENTICATE -> {
                same(input, result, "purpose"); same(input, result, "service_request_id");
                if (result.path("verified").booleanValue() && !time(result, "valid_until").isAfter(time(result, "verified_at"))) invalid("valid_until must follow verified_at");
            }
            case HANDLE_PAYLOAD -> {
                for (String field : Set.of("action", "service_request_id", "compartment_id")) same(input, result, field);
                uniqueItems(result.path("items"));
                if (!input.path("items").equals(result.path("items"))) invalid("payload items do not match resolved snapshot");
            }
            case NOTIFY -> {
                same(input, result, "notification_type");
                if (!input.path("require_ack").equals(result.path("requires_ack"))) invalid("requires_ack does not match input");
            }
            case WAIT -> {
                sameValue(input.path("wait_type"), result.path("wake_reason"), "wake_reason");
                if ("DURATION".equals(result.path("wake_reason").asText()) && result.path("elapsed_sec").doubleValue() < input.path("duration_sec").doubleValue()) invalid("elapsed_sec is less than duration_sec");
                if ("EVENT".equals(result.path("wake_reason").asText())) {
                    same(input, result.path("event"), "event_type"); same(input, result.path("event"), "correlation_key");
                }
            }
            case SET_CONTEXT -> {
                Set<String> expected = new HashSet<>();
                for (JsonNode operation : input.path("operations")) expected.add(operation.path("path").asText().substring("/vars/".length()));
                Set<String> actual = new HashSet<>(); result.path("updated").fieldNames().forEachRemaining(actual::add);
                if (!expected.equals(actual)) invalid("updated keys do not match operations");
                for (JsonNode operation : input.path("operations")) if (!"INCREMENT".equals(operation.path("operation").asText()))
                    sameValue(operation.path("value"), result.path("updated").path(operation.path("path").asText().substring(6)), "updated value");
                else if (!result.path("updated").path(operation.path("path").asText().substring(6)).isNumber()) invalid("INCREMENT updated value must be numeric");
            }
            case CHECK_CONDITION -> { } // false is a valid completed condition, the graph decides the branch
        }
    }

    void validateRuntimeScope(JobStep step, JsonNode result, Instant reportedAt) {
        if (step.getStep().getStepType() != StepType.EXECUTE_SENSING) return;
        String type = result.path("sensing_type").asText();
        if (!Set.of("CROWD_DENSITY", "RESTRICTED_ZONE").contains(type)) return;
        var job = step.getJobTask().getJob();
        if (job.getMap() == null || !job.getMap().getMapId().toString().equalsIgnoreCase(result.path("map_id").asText())) invalid("AI observation map_id is outside the Job map");
        Instant captured = time(result, "captured_at");
        if (step.getResolvedInputsJson().path("window_sec").doubleValue() > 0
                && step.getStartedAt() != null && captured.isBefore(step.getStartedAt())) invalid("AI observation predates STEP_STARTED");
        if (captured.isBefore(reportedAt.minusSeconds(2))) invalid("AI observation is stale (maximum age 2 seconds at result production)");
        if (captured.isAfter(reportedAt.plusSeconds(5))) invalid("AI observation captured_at is in the future");
        if (result.has("frame_id") && job.getMap().getFrameId() != null
                && !job.getMap().getFrameId().equals(result.path("frame_id").asText())) invalid("AI frame_id does not match Job map");
        JsonNode configured = job.getInputJson().get("model_version");
        if (configured != null) sameValue(configured, result.path("model_version"), "model_version");
    }

    private void sensing(JsonNode input, JsonNode n) {
        same(input, n, "sensing_type");
        JsonNode metrics = n.path("metrics");
        String type = n.path("sensing_type").asText();
        if (Set.of("CROWD_DENSITY", "RESTRICTED_ZONE").contains(type)) {
            same(input, n, "zone_id");
            if (input.has("map_id")) same(input, n, "map_id");
            if (n.path("model_version").asText().isBlank() || n.path("model_version").asText().length()>100) invalid("model_version requires 1-100 characters");
            if("CROWD_DENSITY".equals(type) && !metrics.path("count").canConvertToInt())invalid("AI count exceeds integer range");
        } else if ("MAPPING".equals(type)) {
            same(input, n, "mapping_session_id");
            switch (input.path("action").asText()) {
                case "START" -> { if (!"MAPPING".equals(metrics.path("state").asText()) || !metrics.path("preview_ready").asBoolean()) invalid("MAPPING START requires ready preview"); }
                case "SAVE" -> {
                    same(input, metrics, "artifact_id");
                    if (!metrics.path("saved_locally").asBoolean()) invalid("MAPPING SAVE requires saved_locally=true");
                    String state = metrics.path("state").asText();
                    if (!state.equals(metrics.path("uploaded").asBoolean() ? "SAVED" : "SAVED_LOCAL")) invalid("MAPPING upload state is inconsistent");
                    if (Math.abs(metrics.path("width_m").doubleValue() - metrics.path("width_px").doubleValue() * metrics.path("resolution_m_per_pixel").doubleValue()) > .000001
                            || Math.abs(metrics.path("height_m").doubleValue() - metrics.path("height_px").doubleValue() * metrics.path("resolution_m_per_pixel").doubleValue()) > .000001) invalid("MAPPING dimensions do not match pixels/resolution");
                }
                case "STOP" -> { if (!metrics.path("stopped").asBoolean()) invalid("MAPPING STOP requires stopped=true"); }
                default -> invalid("unknown MAPPING action");
            }
        } else {
            for (String f : Set.of("left_m", "right_m", "left_age_sec", "right_age_sec", "battery_age_sec")) nonNegativeNullable(metrics, f);
            if (!metrics.path("battery_raw").isNull() && (metrics.path("battery_raw").decimalValue().signum() < 0
                    || metrics.path("battery_raw").decimalValue().compareTo(java.math.BigDecimal.valueOf(65535)) > 0)) invalid("battery_raw must be uint16");
            for (String side : Set.of("left", "right")) if (metrics.path(side + "_fresh").asBoolean()
                    && (metrics.path(side + "_m").isNull() || metrics.path(side + "_m").doubleValue() < .02 || metrics.path(side + "_m").doubleValue() > 4)) invalid("fresh range must be within 0.02–4 m");
            if ("SIDE_CLEARANCE".equals(type) && (metrics.path("left_m").isNull() || metrics.path("right_m").isNull())) invalid("SIDE_CLEARANCE requires fresh range values");
        }
    }
    private void data(JsonNode input, JsonNode data) {
        String key = input.path("service_key").asText();
        String definition = switch (key) {
            case "QUERY_HOSPITAL_INFORMATION" -> "QUERY_HOSPITAL_INFORMATIONData";
            case "RESOLVE_TARGETS" -> "RESOLVE_TARGETSData"; case "SELECT_TARGET" -> "SELECT_TARGETData";
            case "CHECK_TARGET" -> "CHECK_TARGETData"; case "CREATE_FALL_INCIDENT" -> "CREATE_FALL_INCIDENTData";
            case "COMMIT_MAP_ARTIFACT" -> "COMMIT_MAP_ARTIFACTData";
            default -> throw new BusinessRuleException("Unknown DATA_SERVICE key: " + key);
        };
        schemas.validate("be-step-contracts.json", definition, data);
        if ("QUERY_HOSPITAL_INFORMATION".equals(key) && data.path("count").intValue() != data.path("items").size()) invalid("count does not match items");
        if ("SELECT_TARGET".equals(key) && data.path("has_target").asBoolean()) same(data, data.path("target"), "endpoint_id");
        if ("CHECK_TARGET".equals(key) && data.path("is_valid").asBoolean()) same(input, data.path("endpoint"), "endpoint_id");
    }
    private void uniqueItems(JsonNode items) {
        Set<String> codes = new HashSet<>();
        for (JsonNode item : items) if (!codes.add(item.path("item_code").asText())) invalid("duplicate item_code");
    }
    private void same(JsonNode input, JsonNode result, String field) {
        if (!input.has(field)) invalid("missing resolved input " + field);
        if (Set.of("endpoint_id", "map_id", "zone_id", "service_request_id", "mapping_session_id", "artifact_id").contains(field)) {
            try {
                if (!java.util.UUID.fromString(input.path(field).asText()).equals(java.util.UUID.fromString(result.path(field).asText()))) invalid(field + " does not match resolved input");
                return;
            } catch (IllegalArgumentException ex) { invalid(field + " requires UUID input and output"); }
        }
        sameValue(input.path(field), result.path(field), field);
    }
    private void sameValue(JsonNode a, JsonNode b, String field) {
        if (a.isNumber() && b.isNumber() ? a.decimalValue().compareTo(b.decimalValue()) != 0 : !a.equals(b)) invalid(field + " does not match resolved input");
    }
    private void nonNegativeNullable(JsonNode n, String field) {
        if (!n.path(field).isNull() && n.path(field).doubleValue() < 0) invalid(field + " must be non-negative");
    }
    private Instant time(JsonNode n, String field) { return OffsetDateTime.parse(n.path(field).asText()).toInstant(); }
    private void invalid(String message) { throw new BusinessRuleException("Invalid Step result schema 3: " + message); }
}
