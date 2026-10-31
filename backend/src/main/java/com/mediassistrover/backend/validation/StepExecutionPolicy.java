package com.mediassistrover.backend.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.mqtt.MqttProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Version-aware assignment: existing schema 2 jobs retain their original executor. */
@Component @RequiredArgsConstructor
public class StepExecutionPolicy {
    public static final Set<StepType> SCHEMA3_MQTT_TYPES = Set.of(
            StepType.MOVE, StepType.CONTROL_HARDWARE, StepType.EXECUTE_SENSING, StepType.WAIT, StepType.INTERACT);
    public static final Set<StepType> SCHEMA3_BACKEND_TYPES = Set.of(
            StepType.DATA_SERVICE, StepType.AUTHENTICATE,
            StepType.HANDLE_PAYLOAD, StepType.NOTIFY, StepType.CHECK_CONDITION,
            StepType.SET_CONTEXT);

    private final MqttProperties mqtt;

    /** Configuration is only a dispatch gate; it never changes executor ownership. */
    public boolean routesMqtt(StepType type) {
        return SCHEMA3_MQTT_TYPES.contains(type) && mqtt.dispatches(type);
    }

    public boolean requiresMqtt(Step step) {
        JsonNode definition = step.getParamsJson();
        if(step.getStepType()==StepType.INTERACT && step.getInputMappingJson().has("action"))
            throw new com.mediassistrover.backend.exception.BusinessRuleException("INTERACT requires a fixed action in paramsJson");
        if (step.getStepType() == StepType.WAIT && step.getInputMappingJson().has("wait_type")) {
            throw new com.mediassistrover.backend.exception.BusinessRuleException(
                    "WAIT executor requires a fixed wait_type in paramsJson; dynamic wait_type is not publishable");
        }
        return executor(3, step.getStepType(), definition) == StepExecutorKind.PI;
    }

    public boolean isServer(JobStep step, JsonNode inputs) {
        if (!step.getJobTask().getJob().isBeOrchestrated()) {
            // Preserve the schema 2 executor behavior exactly.
            return step.getStep().getStepType()
                    .getExecutorKind(step.getJobTask().getJob().getSchemaVersion()) != StepExecutorKind.PI;
        }
        StepType type=StepType.valueOf(step.getStepTypeSnapshot());
        return executor(step.getJobTask().getJob().getSchemaVersion(), type, inputs) == StepExecutorKind.SERVER;
    }

    public StepExecutorKind executor(int schemaVersion, StepType type, JsonNode inputs) {
        if (schemaVersion == 2) return type.getExecutorKind(2);
        if (schemaVersion != 3) {
            throw new com.mediassistrover.backend.exception.BusinessRuleException(
                    "Unsupported executor policy schema version: " + schemaVersion);
        }
        if(type==StepType.INTERACT)return inputs!=null && "SPEAK".equals(inputs.path("action").asText()) ? StepExecutorKind.PI : StepExecutorKind.SERVER;
        if (SCHEMA3_BACKEND_TYPES.contains(type)) return StepExecutorKind.SERVER;
        if (type != StepType.WAIT) return StepExecutorKind.PI;
        String waitType = inputs == null ? "" : inputs.path("wait_type").asText();
        return switch (waitType) {
            case "EVENT" -> StepExecutorKind.SERVER;
            case "DURATION" -> StepExecutorKind.PI;
            default -> throw new com.mediassistrover.backend.exception.BusinessRuleException(
                    "WAIT requires wait_type EVENT or DURATION before executor selection");
        };
    }
}
