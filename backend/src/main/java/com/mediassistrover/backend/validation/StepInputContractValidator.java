package com.mediassistrover.backend.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.Step;
import com.mediassistrover.backend.enums.StepType;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.service.impl.DataServiceStepHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Business inputs only.
 * Templates defer referenced values; runtime inputs never do.
 */
@Component
@RequiredArgsConstructor
public class StepInputContractValidator {

    private final WorkflowJsonValidator jsonValidator;

    private static final Set<String> UUID_FIELDS = Set.of(
            "map_id",
            "endpoint_id",
            "zone_id",
            "endpoint_group_id",
            "service_request_id",
            "detection_event_id",
            "incident_id",
            "mapping_session_id",
            "artifact_id",
            "floor_id",
            "verification_id",
            "actor_account_id"
    );

    public void validateTemplate(Step step) {
        object(step, step.getParamsJson(), "paramsJson");
        object(step, step.getInputMappingJson(), "inputMappingJson");

        // Check both sources, including a literal later shadowed by a mapping.
        validate(step, step.getParamsJson(), true, false, false);
        validate(step, step.getInputMappingJson(), true, false, false);

        ObjectNode merged = ((ObjectNode) step.getParamsJson()).deepCopy();
        merged.setAll((ObjectNode) step.getInputMappingJson());

        validate(step, merged, true, true, false);

        // The current authorization handler reads purpose from the definition,
        // never from client data.
        if (step.getStepType() == StepType.AUTHENTICATE) {
            text(
                    step,
                    step.getParamsJson(),
                    "purpose",
                    true,
                    false
            );

            if (!step.getParamsJson()
                    .path("purpose")
                    .equals(merged.path("purpose"))) {
                fail(
                        step,
                        "purpose",
                        "cannot override the configured authorization purpose"
                );
            }
        }

        if (step.getWorkflow() != null
                && step.getWorkflow().getSchemaVersion() == 3
                && step.getStepType() == StepType.CONTROL_HARDWARE
                && Set.of("UNLOCK", "UNLOCK_COMPARTMENT")
                .contains(merged.path("action").asText())) {

            if (!merged.has("authorization")) {
                fail(
                        step,
                        "authorization",
                        "UNLOCK requires a BE verification mapping"
                );
            }

            enumeration(
                    step,
                    merged.path("authorization"),
                    "purpose",
                    Set.of(
                            "DELIVERY_SENDER",
                            "DELIVERY_RECEIVER"
                    ),
                    true,
                    false
            );

            if (step.getInputMappingJson().has("action")) {
                fail(
                        step,
                        "action",
                        "authorized UNLOCK must be fixed in paramsJson"
                );
            }
        }
    }

    public void validateResolved(Step step, JsonNode inputs) {
        object(step, inputs, "params");
        rejectRefs(step, inputs);
        validate(step, inputs, false, true, false);
    }

    public void validateEffectiveTemplate(Step step, JsonNode inputs) {
        validate(step, inputs, true, true, false);
    }

    /**
     * Final gate for the MQTT dispatcher:
     * resolved schema-3 params, no unresolved references.
     *
     * IMPORTANT:
     * pi=true because ALL supported StepTypes are allowed
     * to execute through the Pi/MQTT runtime.
     */
    public void validateMqttParams(Step step, JsonNode params) {
        object(step, params, "params");
        rejectRefs(step, params);
        if (!Set.of(StepType.MOVE, StepType.CONTROL_HARDWARE, StepType.EXECUTE_SENSING, StepType.WAIT, StepType.INTERACT)
                .contains(step.getStepType())) fail(step, "stepType", "is not a Pi action");
        validate(step, params, false, true, true);
    }

    /**
     * Compatibility alias for callers compiled against
     * the earlier Pi-action API.
     */
    public void validatePiParams(Step step, JsonNode params) {
        validateMqttParams(step, params);
    }

    private void validate(
            Step s,
            JsonNode n,
            boolean template,
            boolean required,
            boolean pi
    ) {

        boolean modern =
                pi
                        || (
                        s.getWorkflow() != null
                                && s.getWorkflow().getSchemaVersion() == 3
                );

        for (String field : UUID_FIELDS) {
            uuid(s, n, field, false, template);
        }

        switch (s.getStepType()) {

            case MOVE -> {
                fields(
                        s,
                        n,
                        Set.of(
                                "map_id",
                                "endpoint_id",
                                "mode",
                                "max_speed_mps"
                        )
                );

                uuid(
                        s,
                        n,
                        "map_id",
                        required,
                        template
                );

                uuid(
                        s,
                        n,
                        "endpoint_id",
                        required,
                        template
                );

                enumeration(
                        s,
                        n,
                        "mode",
                        Set.of(
                                "NORMAL",
                                "ESCORT",
                                "PATROL"
                        ),
                        required && modern,
                        template
                );

                number(
                        s,
                        n,
                        "max_speed_mps",
                        0,
                        .3,
                        true,
                        required && modern,
                        template
                );
            }

            case CONTROL_HARDWARE -> {
                fields(
                        s,
                        n,
                        pi
                                ? Set.of(
                                "compartment_id",
                                "action",
                                "authorization"
                        )
                                : Set.of(
                                "compartment_id",
                                "action",
                                "service_request_id",
                                "authorization"
                        )
                );

                enumeration(
                        s,
                        n,
                        "action",
                        pi
                                ? Set.of(
                                "LOCK_COMPARTMENT",
                                "UNLOCK_COMPARTMENT"
                        )
                                : Set.of(
                                "LOCK_COMPARTMENT",
                                "UNLOCK_COMPARTMENT",
                                "LOCK",
                                "UNLOCK"
                        ),
                        required,
                        template
                );

                text(
                        s,
                        n,
                        "compartment_id",
                        required,
                        template
                );

                if (literal(n.get("compartment_id"), template)
                        && !"CART_MAIN".equals(
                        n.path("compartment_id").asText()
                )) {
                    fail(
                            s,
                            "compartment_id",
                            "must be CART_MAIN"
                    );
                }

                if (s.getMaxAttempts() != 1) {
                    fail(
                            s,
                            "maxAttempts",
                            "hardware commands cannot be retried automatically"
                    );
                }

                authorization(
                        s,
                        n.get("authorization"),
                        template
                );
            }

            case EXECUTE_SENSING ->
                    sensing(
                            s,
                            n,
                            template,
                            required,
                            modern
                    );

            /*
             * WAIT is also a Pi/MQTT StepType.
             *
             * Both:
             * - WAIT(DURATION)
             * - WAIT(EVENT)
             *
             * are supported on the Pi side.
             */
            case WAIT -> {
                fields(
                        s,
                        n,
                        Set.of(
                                "wait_type",
                                "duration_sec",
                                "event_type",
                                "correlation_key",
                                "incident_id"
                        )
                );

                enumeration(
                        s,
                        n,
                        "wait_type",
                        Set.of(
                                "DURATION",
                                "EVENT"
                        ),
                        required,
                        template
                );

                number(
                        s,
                        n,
                        "duration_sec",
                        1,
                        3600,
                        false,
                        required
                                && "DURATION".equals(
                                n.path("wait_type").asText()
                        ),
                        template
                );

                text(
                        s,
                        n,
                        "event_type",
                        required
                                && "EVENT".equals(
                                n.path("wait_type").asText()
                        ),
                        template
                );

                text(
        s,
        n,
        "correlation_key",
        required
                && pi
                && "EVENT".equals(
                n.path("wait_type").asText()
        ),
        template
);
                timeout(
                        s,
                        n,
                        "duration_sec",
                        template
                );

                /*
                 * Once wait_type is literal, only the fields
                 * belonging to that concrete wait mode are accepted.
                 */
                if (literal(
                        n.get("wait_type"),
                        template
                )) {

                    if ("DURATION".equals(
                            n.path("wait_type").asText()
                    )) {

                        fields(
                                s,
                                n,
                                Set.of(
                                        "wait_type",
                                        "duration_sec"
                                )
                        );

                    } else {

                        fields(
                                s,
                                n,
                                Set.of(
                                        "wait_type",
                                        "event_type",
                                        "correlation_key",
                                        "incident_id"
                                )
                        );
                    }
                }
            }

            case INTERACT -> {
                fields(s, n, Set.of("prompt_key", "action", "data", "actor", "required_role", "compartment_id",
                        "patient_reference", "endpoint_id", "question", "items", "message", "incident_id", "detection_event_id"));
                text(s, n, "prompt_key", required, template);
                enumeration(s, n, "action", Set.of("SPEAK", "DISPLAY", "CAPTURE_INPUT", "CONFIRM"), false, template);
                enumeration(s, n, "actor", Set.of("STAFF", "ADMIN", "PATIENT", "USER", "DEVICE"), false, template);
                enumeration(s, n, "required_role", Set.of("STAFF", "ADMIN"), false, template);
                objectField(s, n, "data", template);
                if (pi || modern && "SPEAK".equals(n.path("action").asText())) {
                    fields(s,n,Set.of("prompt_key","action","data"));
                    enumeration(s,n,"action",Set.of("SPEAK"),required,template);
                    JsonNode data=n.get("data");
                    if(literal(data,template)) {
                        fields(s,data,Set.of("text","language_code","speed_wpm","volume"));
                        text(s,data,"text",required,template);
                        if(literal(data.get("text"),template) && data.path("text").asText().length()>500)fail(s,"data.text","maximum is 500 characters");
                        enumeration(s,data,"language_code",Set.of("vi","en"),false,template);
                        number(s,data,"speed_wpm",80,220,false,false,template);
                        number(s,data,"volume",0,100,false,false,template);
                        for(String f:Set.of("speed_wpm","volume"))if(literal(data.get(f),template) && !data.get(f).isIntegralNumber())fail(s,f,"must be an integer");
                    }
                }
                if (modern && required) {
                    text(
                            s,
                            n,
                            "action",
                            true,
                            template
                    );

                    if (!n.has("data")) {
                        fail(
                                s,
                                "data",
                                "is required"
                        );
                    }
                }
            }

            case DATA_SERVICE -> {
                fields(
                        s,
                        n,
                        Set.of(
                                "service_key",
                                "operation",
                                "question",
                                "language_code",
                                "map_id",
                                "endpoint_id",
                                "endpoint_group_id",
                                "targets",
                                "index",
                                "detection_event_id",
                                "mapping_session_id",
                                "artifact_id",
                                "floor_id",
                                "map_name"
                        )
                );

                String key = n.path("service_key")
                        .asText(
                                "QUERY".equals(s.getStepCode())
                                        ? "QUERY_HOSPITAL_INFORMATION"
                                        : ""
                        );

                enumeration(
                        s,
                        n,
                        "service_key",
                        DataServiceStepHandler.SUPPORTED_SERVICE_KEYS,
                        required && !"QUERY".equals(s.getStepCode()),
                        template
                );

                enumeration(
                        s,
                        n,
                        "operation",
                        Set.of(
                                "QUERY",
                                "CREATE",
                                "UPDATE"
                        ),
                        false,
                        template
                );

                text(
                        s,
                        n,
                        "question",
                        required
                                && "QUERY_HOSPITAL_INFORMATION".equals(key),
                        template
                );

                text(
                        s,
                        n,
                        "language_code",
                        false,
                        template
                );

                uuid(
                        s,
                        n,
                        "endpoint_group_id",
                        required
                                && "RESOLVE_TARGETS".equals(key),
                        template
                );

                uuid(
                        s,
                        n,
                        "endpoint_id",
                        required
                                && "CHECK_TARGET".equals(key),
                        template
                );

                uuid(
                        s,
                        n,
                        "detection_event_id",
                        required
                                && "CREATE_FALL_INCIDENT".equals(key),
                        template
                );

                if ("COMMIT_MAP_ARTIFACT".equals(key)) {

                    if (!modern) {
                        fail(
                                s,
                                "service_key",
                                "COMMIT_MAP_ARTIFACT requires schema 3"
                        );
                    }

                    for (String field : Set.of(
                            "mapping_session_id",
                            "artifact_id",
                            "floor_id"
                    )) {
                        uuid(
                                s,
                                n,
                                field,
                                required,
                                template
                        );
                    }

                    text(
                            s,
                            n,
                            "map_name",
                            required,
                            template
                    );

                    if (literal(
                            n.get("map_name"),
                            template
                    )
                            && n.path("map_name")
                            .asText()
                            .length() > 160) {

                        fail(
                                s,
                                "map_name",
                                "maximum length is 160"
                        );
                    }

                    fields(
                            s,
                            n,
                            Set.of(
                                    "service_key",
                                    "mapping_session_id",
                                    "artifact_id",
                                    "floor_id",
                                    "map_name"
                            )
                    );
                }

                arrayField(
                        s,
                        n,
                        "targets",
                        required
                                && "SELECT_TARGET".equals(key),
                        template
                );

                if (literal(
                        n.get("index"),
                        template
                )
                        && (
                        !n.get("index").isIntegralNumber()
                                || !n.get("index").canConvertToInt()
                                || n.get("index").intValue() < 0
                )) {

                    fail(
                            s,
                            "index",
                            "must be a non-negative integer"
                    );
                }

                rejectDangerous(
                        s,
                        n
                );
            }

            case AUTHENTICATE -> {
                fields(
                        s,
                        n,
                        Set.of(
                                "purpose",
                                "method",
                                "service_request_id"
                        )
                );

                enumeration(
                        s,
                        n,
                        "purpose",
                        Set.of(
                                "DELIVERY_SENDER",
                                "DELIVERY_RECEIVER"
                        ),
                        required,
                        template
                );

                enumeration(
                        s,
                        n,
                        "method",
                        Set.of(
                                "QR_CODE",
                                "STAFF_CONFIRMATION"
                        ),
                        false,
                        template
                );

                uuid(
                        s,
                        n,
                        "service_request_id",
                        required,
                        template
                );
            }

            case HANDLE_PAYLOAD -> {
                fields(
                        s,
                        n,
                        Set.of(
                                "action",
                                "service_request_id",
                                "compartment_id",
                                "items"
                        )
                );

                enumeration(
                        s,
                        n,
                        "action",
                        modern
                                ? Set.of(
                                "LOAD",
                                "UNLOAD"
                        )
                                : Set.of(
                                "LOAD",
                                "UNLOAD",
                                "VERIFY"
                        ),
                        required && modern,
                        template
                );

                uuid(
                        s,
                        n,
                        "service_request_id",
                        required,
                        template
                );

                text(
                        s,
                        n,
                        "compartment_id",
                        required,
                        template
                );

                items(
                        s,
                        n.get("items"),
                        template
                );

                if (modern
                        && required
                        && !n.has("items")) {

                    fail(
                            s,
                            "items",
                            "is required"
                    );
                }
            }

            case NOTIFY -> {
                /*
                 * Schema 2 handlers consume existing domain
                 * metadata at the top level.
                 */
                fields(
                        s,
                        n,
                        modern
                                ? Set.of(
                                "notification_type",
                                "severity",
                                "data",
                                "require_ack",
                                "ack_policy",
                                "recipients",
                                "message"
                        )
                                : Set.of(
                                "notification_type",
                                "severity",
                                "data",
                                "require_ack",
                                "ack_policy",
                                "recipients",
                                "message",
                                "incident_id",
                                "metrics",
                                "robot_id",
                                "charge_request_id",
                                "staff_event",
                                "health",
                                "recovery_request_id",
                                "endpoint_id",
                                "detection_event_id",
                                "zone_id",
                                "map_id"
                        )
                );

                text(
                        s,
                        n,
                        "notification_type",
                        required,
                        template
                );

                text(
                        s,
                        n,
                        "message",
                        false,
                        template
                );

                enumeration(
                        s,
                        n,
                        "severity",
                        Set.of(
                                "INFO",
                                "WARNING",
                                "CRITICAL"
                        ),
                        required && modern,
                        template
                );

                enumeration(
                        s,
                        n,
                        "ack_policy",
                        Set.of(
                                "ANY",
                                "ALL"
                        ),
                        false,
                        template
                );

                arrayField(
                        s,
                        n,
                        "recipients",
                        required && !modern,
                        template
                );

                if (literal(
                        n.get("recipients"),
                        template
                )
                        && n.get("recipients").isArray()) {

                    for (JsonNode recipient : n.get("recipients")) {

                        if (literal(
                                recipient,
                                template
                        )) {

                            if (!recipient.isTextual()
                                    || recipient.asText().isBlank()) {

                                fail(
                                        s,
                                        "recipients",
                                        "entries must be non-empty strings"
                                );
                            }

                            if (modern
                                    && !Set.of(
                                    "ADMIN",
                                    "STAFF"
                            ).contains(
                                    recipient.asText()
                            )) {

                                try {
                                    if (!UUID.fromString(
                                                    recipient.asText()
                                            )
                                            .toString()
                                            .equalsIgnoreCase(
                                                    recipient.asText()
                                            )) {

                                        throw new IllegalArgumentException();
                                    }

                                } catch (IllegalArgumentException ex) {
                                    fail(
                                            s,
                                            "recipients",
                                            "entries must be account UUIDs or ADMIN/STAFF"
                                    );
                                }
                            }
                        }
                    }
                }

                if (literal(
                        n.get("require_ack"),
                        template
                )
                        && !n.get("require_ack").isBoolean()) {

                    fail(
                            s,
                            "require_ack",
                            "must be boolean"
                    );
                }

                objectField(
                        s,
                        n,
                        "data",
                        template
                );

                if (modern && required) {

                    text(
                            s,
                            n,
                            "severity",
                            true,
                            template
                    );

                    if (!n.has("require_ack")) {
                        fail(
                                s,
                                "require_ack",
                                "is required"
                        );
                    }

                    if (!n.has("data")) {
                        fail(
                                s,
                                "data",
                                "is required"
                        );
                    }
                }
            }

            case CHECK_CONDITION -> {
                fields(
                        s,
                        n,
                        Set.of("expression")
                );

                if (required
                        && !n.has("expression")) {

                    fail(
                            s,
                            "expression",
                            "is required"
                    );
                }

                if (literal(
                        n.get("expression"),
                        template
                )) {

                    try {
                        jsonValidator.condition(
                                n.get("expression")
                        );

                    } catch (BusinessRuleException ex) {
                        fail(
                                s,
                                "expression",
                                ex.getMessage()
                        );
                    }

                    JsonNode expression =
                            n.get("expression");

                    if (literal(
                            expression.get("value"),
                            template
                    )) {

                        if (Set.of(
                                "GT",
                                "GTE",
                                "LT",
                                "LTE"
                        ).contains(
                                expression
                                        .path("operator")
                                        .asText()
                        )
                                && !expression
                                .path("value")
                                .isNumber()) {

                            fail(
                                    s,
                                    "expression.value",
                                    "must be numeric"
                            );
                        }

                        if ("IN".equals(
                                expression
                                        .path("operator")
                                        .asText()
                        )
                                && !expression
                                .path("value")
                                .isArray()) {

                            fail(
                                    s,
                                    "expression.value",
                                    "must be an array"
                            );
                        }
                    }
                }
            }

            case SET_CONTEXT ->
                    operations(
                            s,
                            n,
                            template,
                            required
                    );
        }

        if (s.getTimeoutSec() < 1) {
            fail(
                    s,
                    "timeoutSec",
                    "must be positive"
            );
        }
    }

    private void sensing(
            Step s,
            JsonNode n,
            boolean template,
            boolean required,
            boolean pi
    ) {

        fields(
                s,
                n,
                Set.of(
                        "sensing_type",
                        "zone_id",
                        "window_sec",
                        "action",
                        "mapping_session_id",
                        "artifact_id",
                        "preview_rate_hz"
                )
        );

        enumeration(
                s,
                n,
                "sensing_type",
                pi
                        ? Set.of(
                        "ROBOT_HEALTH",
                        "SIDE_CLEARANCE",
                        "CROWD_DENSITY",
                        "RESTRICTED_ZONE",
                        "MAPPING"
                )
                        : Set.of(
                        "ROBOT_HEALTH",
                        "SIDE_CLEARANCE",
                        "CROWD_DENSITY",
                        "RESTRICTED_ZONE",
                        "RESTRICTED_AREA_INTRUSION"
                ),
                required,
                template
        );

        String type =
                n.path("sensing_type").asText();

        boolean ai =
                Set.of(
                        "CROWD_DENSITY",
                        "RESTRICTED_ZONE",
                        "RESTRICTED_AREA_INTRUSION"
                ).contains(type);

        uuid(
                s,
                n,
                "zone_id",
                required && ai,
                template
        );

        number(
                s,
                n,
                "window_sec",
                0,
                30,
                false,
                false,
                template
        );

        timeout(
                s,
                n,
                "window_sec",
                template
        );

        if (required
                && ai
                && !n.has("window_sec")
                && s.getTimeoutSec() < 1) {

            fail(
                    s,
                    "timeoutSec",
                    "must be at least window_sec + 1"
            );
        }

        if (literal(
                n.get("sensing_type"),
                template
        )) {

            if ("MAPPING".equals(type)) {

                enumeration(
                        s,
                        n,
                        "action",
                        Set.of(
                                "START",
                                "SAVE",
                                "STOP"
                        ),
                        required,
                        template
                );

                uuid(
                        s,
                        n,
                        "mapping_session_id",
                        required,
                        template
                );

                uuid(
                        s,
                        n,
                        "artifact_id",
                        required
                                && "SAVE".equals(
                                n.path("action").asText()
                        ),
                        template
                );

                number(
                        s,
                        n,
                        "preview_rate_hz",
                        .2,
                        1,
                        false,
                        false,
                        template
                );

                int min = switch (
                        n.path("action").asText()
                ) {
                    case "START" -> 55;
                    case "SAVE" -> 60;
                    case "STOP" -> 15;
                    default -> 0;
                };

                if (s.getTimeoutSec() < min) {
                    fail(
                            s,
                            "timeoutSec",
                            "MAPPING action requires at least "
                                    + min
                                    + " seconds"
                    );
                }

                if (s.getMaxAttempts() != 1) {
                    fail(
                            s,
                            "maxAttempts",
                            "mapping cannot retry physical actions automatically"
                    );
                }

                if (literal(
                        n.get("action"),
                        template
                )) {

                    fields(
                            s,
                            n,
                            "START".equals(
                                    n.path("action").asText()
                            )
                                    ? Set.of(
                                    "sensing_type",
                                    "action",
                                    "mapping_session_id",
                                    "preview_rate_hz"
                            )
                                    : "SAVE".equals(
                                    n.path("action").asText()
                            )
                                    ? Set.of(
                                    "sensing_type",
                                    "action",
                                    "mapping_session_id",
                                    "artifact_id"
                            )
                                    : Set.of(
                                    "sensing_type",
                                    "action",
                                    "mapping_session_id"
                            )
                    );
                }

            } else {

                fields(
                        s,
                        n,
                        ai
                                ? Set.of(
                                "sensing_type",
                                "zone_id",
                                "window_sec"
                        )
                                : Set.of(
                                "sensing_type"
                        )
                );
            }
        }
    }

    private void operations(
            Step s,
            JsonNode n,
            boolean template,
            boolean required
    ) {

        fields(
                s,
                n,
                Set.of("operations")
        );

        arrayField(
                s,
                n,
                "operations",
                required,
                template
        );

        if (!literal(
                n.get("operations"),
                template
        )) {
            return;
        }

        JsonNode ops =
                n.get("operations");

        if (ops.isEmpty()
                || ops.size() > 128) {

            fail(
                    s,
                    "operations",
                    "must contain 1–128 entries"
            );
        }

        Set<String> paths =
                new HashSet<>();

        for (JsonNode op : ops) {

            if (template
                    && ref(op)) {
                continue;
            }

            object(
                    s,
                    op,
                    "operations[]"
            );

            fields(
                    s,
                    op,
                    Set.of(
                            "path",
                            "operation",
                            "value"
                    )
            );

            text(
                    s,
                    op,
                    "path",
                    true,
                    false
            );

            String path =
                    op.path("path").asText();

            if (!path.matches(
                    "^/vars/[A-Za-z][A-Za-z0-9_]*$"
            )
                    || !paths.add(path)) {

                fail(
                        s,
                        "operations.path",
                        "must uniquely target /vars/<identifier>"
                );
            }

            enumeration(
                    s,
                    op,
                    "operation",
                    Set.of(
                            "SET",
                            "INCREMENT"
                    ),
                    false,
                    template
            );

            if (!op.has("value")) {
                fail(
                        s,
                        "operations.value",
                        "is required"
                );
            }

            if ("INCREMENT".equals(
                    op.path("operation").asText()
            )
                    && literal(
                    op.get("value"),
                    template
            )
                    && !op.get("value").isNumber()) {

                fail(
                        s,
                        "operations.value",
                        "INCREMENT requires a number"
                );
            }
        }
    }

    private void authorization(
            Step s,
            JsonNode auth,
            boolean template
    ) {

        if (!literal(
                auth,
                template
        )) {
            return;
        }

        object(
                s,
                auth,
                "authorization"
        );

        fields(
                s,
                auth,
                Set.of(
                        "mode",
                        "verification_id",
                        "actor_account_id",
                        "purpose",
                        "valid_until"
                )
        );

        enumeration(
                s,
                auth,
                "mode",
                Set.of(
                        "STAFF_VERIFIED"
                ),
                true,
                template
        );

        uuid(
                s,
                auth,
                "verification_id",
                true,
                template
        );

        uuid(
                s,
                auth,
                "actor_account_id",
                true,
                template
        );

        enumeration(
                s,
                auth,
                "purpose",
                Set.of(
                        "DELIVERY_SENDER",
                        "DELIVERY_RECEIVER"
                ),
                true,
                template
        );

        text(
                s,
                auth,
                "valid_until",
                true,
                template
        );

        if (literal(
                auth.get("valid_until"),
                template
        )) {

            try {
                java.time.OffsetDateTime.parse(
                        auth.path("valid_until").asText()
                );

            } catch (java.time.DateTimeException ex) {

                fail(
                        s,
                        "authorization.valid_until",
                        "must be an ISO timestamp with offset"
                );
            }
        }
    }

    private void items(
            Step s,
            JsonNode items,
            boolean template
    ) {

        if (!literal(
                items,
                template
        )) {
            return;
        }

        if (!items.isArray()
                || items.isEmpty()) {

            fail(
                    s,
                    "items",
                    "must be a non-empty array"
            );
        }

        Set<String> codes =
                new HashSet<>();

        for (JsonNode item : items) {

            if (template
                    && ref(item)) {
                continue;
            }

            object(
                    s,
                    item,
                    "items[]"
            );

            fields(
                    s,
                    item,
                    Set.of(
                            "item_code",
                            "quantity"
                    )
            );

            text(
                    s,
                    item,
                    "item_code",
                    true,
                    template
            );

            if (literal(
                    item.get("item_code"),
                    template
            )
                    && !codes.add(
                    item.path("item_code").asText()
            )) {

                fail(
                        s,
                        "items.item_code",
                        "must be unique"
                );
            }

            if (!item.has("quantity")) {
                fail(
                        s,
                        "items.quantity",
                        "is required"
                );
            }

            if (literal(
                    item.get("quantity"),
                    template
            )
                    && (
                    !item.get("quantity").isIntegralNumber()
                            || !item.get("quantity").canConvertToInt()
                            || item.get("quantity").intValue() < 1
            )) {

                fail(
                        s,
                        "items.quantity",
                        "must be a positive integer"
                );
            }
        }
    }

    private void timeout(
            Step s,
            JsonNode n,
            String field,
            boolean template
    ) {

        if (literal(
                n.get(field),
                template
        )
                && n.get(field).isNumber()
                && java.math.BigDecimal
                .valueOf(s.getTimeoutSec())
                .compareTo(
                        n.get(field)
                                .decimalValue()
                                .add(
                                        java.math.BigDecimal.ONE
                                )
                ) < 0) {

            fail(
                    s,
                    "timeoutSec",
                    "must be at least "
                            + field
                            + " + 1"
            );
        }
    }

    private void fields(
            Step s,
            JsonNode n,
            Set<String> allowed
    ) {

        n.fieldNames()
                .forEachRemaining(field -> {

                    if (!allowed.contains(field)) {

                        fail(
                                s,
                                field,
                                "is not supported for "
                                        + s.getStepType()
                        );
                    }
                });
    }

    private void object(
            Step s,
            JsonNode n,
            String field
    ) {

        if (n == null
                || !n.isObject()) {

            fail(
                    s,
                    field,
                    "must be a JSON object"
            );
        }

        try {
            jsonValidator.references(n);

        } catch (BusinessRuleException ex) {

            fail(
                    s,
                    field,
                    ex.getMessage()
            );
        }
    }

    private void objectField(
            Step s,
            JsonNode n,
            String field,
            boolean template
    ) {

        if (literal(
                n.get(field),
                template
        )
                && !n.get(field).isObject()) {

            fail(
                    s,
                    field,
                    "must be an object"
            );
        }
    }

    private void arrayField(
            Step s,
            JsonNode n,
            String field,
            boolean required,
            boolean template
    ) {

        if (required
                && !n.has(field)) {

            fail(
                    s,
                    field,
                    "is required"
            );
        }

        if (literal(
                n.get(field),
                template
        )
                && !n.get(field).isArray()) {

            fail(
                    s,
                    field,
                    "must be an array"
            );
        }
    }

    private void text(
            Step s,
            JsonNode n,
            String field,
            boolean required,
            boolean template
    ) {

        if (required
                && !n.has(field)) {

            fail(
                    s,
                    field,
                    "is required"
            );
        }

        if (literal(
                n.get(field),
                template
        )
                && (
                !n.get(field).isTextual()
                        || n.get(field)
                        .textValue()
                        .isBlank()
        )) {

            fail(
                    s,
                    field,
                    "must be a non-empty string"
            );
        }
    }

    private void enumeration(
            Step s,
            JsonNode n,
            String field,
            Set<String> allowed,
            boolean required,
            boolean template
    ) {

        text(
                s,
                n,
                field,
                required,
                template
        );

        if (literal(
                n.get(field),
                template
        )
                && !allowed.contains(
                n.get(field).asText()
        )) {

            fail(
                    s,
                    field,
                    "has an unsupported value"
            );
        }
    }

    private void uuid(
            Step s,
            JsonNode n,
            String field,
            boolean required,
            boolean template
    ) {

        text(
                s,
                n,
                field,
                required,
                template
        );

        if (literal(
                n.get(field),
                template
        )) {

            String value =
                    n.get(field).asText();

            try {

                if (!UUID.fromString(value)
                        .toString()
                        .equalsIgnoreCase(value)) {

                    throw new IllegalArgumentException();
                }

            } catch (IllegalArgumentException ex) {

                fail(
                        s,
                        field,
                        "must be a canonical UUID"
                );
            }
        }
    }

    private void number(
            Step s,
            JsonNode n,
            String field,
            double min,
            double max,
            boolean exclusive,
            boolean required,
            boolean template
    ) {

        if (required
                && !n.has(field)) {

            fail(
                    s,
                    field,
                    "is required"
            );
        }

        if (literal(
                n.get(field),
                template
        )) {

            JsonNode value =
                    n.get(field);

            if (!value.isNumber()
                    || !Double.isFinite(
                    value.doubleValue()
            )
                    || value.decimalValue()
                    .compareTo(
                            java.math.BigDecimal.valueOf(max)
                    ) > 0
                    || (
                    exclusive
                            ? value.decimalValue()
                            .compareTo(
                                    java.math.BigDecimal.valueOf(min)
                            ) <= 0
                            : value.decimalValue()
                            .compareTo(
                                    java.math.BigDecimal.valueOf(min)
                            ) < 0
            )) {

                fail(
                        s,
                        field,
                        "is outside the supported range"
                );
            }
        }
    }

    private boolean ref(JsonNode n) {
        return n != null
                && n.isObject()
                && n.size() == 1
                && n.has("$ref");
    }

    private boolean literal(
            JsonNode n,
            boolean template
    ) {
        return n != null
                && !(template && ref(n));
    }

    private void rejectRefs(
            Step s,
            JsonNode n
    ) {

        if (n.isObject()
                && n.has("$ref")) {

            fail(
                    s,
                    "$ref",
                    "must be fully resolved"
            );
        }

        if (n.isContainerNode()) {

            n.forEach(
                    child ->
                            rejectRefs(
                                    s,
                                    child
                            )
            );
        }
    }

    private void rejectDangerous(
            Step s,
            JsonNode n
    ) {

        if (n.isObject()) {

            for (String field : Set.of(
                    "url",
                    "sql",
                    "script",
                    "code"
            )) {

                if (n.has(field)) {

                    fail(
                            s,
                            field,
                            "DATA_SERVICE cannot execute arbitrary code or queries"
                    );
                }
            }
        }

        if (n.isContainerNode()) {

            n.forEach(
                    child ->
                            rejectDangerous(
                                    s,
                                    child
                            )
            );
        }
    }

    static void fail(
            Step step,
            String field,
            String reason
    ) {

        throw new BusinessRuleException(
                "Invalid contract for step "
                        + step.getStepCode()
                        + ": "
                        + field
                        + " "
                        + reason
        );
    }
}