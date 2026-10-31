package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.entity.Job;
import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.entity.JobTask;
import com.mediassistrover.backend.entity.Step;
import com.mediassistrover.backend.enums.StepExecutorKind;
import com.mediassistrover.backend.enums.StepType;
import com.mediassistrover.backend.mqtt.MqttProperties;
import com.mediassistrover.backend.validation.StepExecutionPolicy;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StepExecutionPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final MqttProperties mqtt = new MqttProperties();
    private final StepExecutionPolicy policy = new StepExecutionPolicy(mqtt);

    @Test
    void sevenBackendTypesStayServerOwnedEvenIfAllowlistIsMutated() {
        mqtt.setEnabled(true);
        mqtt.setDispatchEnabled(true);
        mqtt.setEnabledStepTypes(new HashSet<>(Set.of(
                "INTERACT", "DATA_SERVICE", "AUTHENTICATE", "HANDLE_PAYLOAD",
                "NOTIFY", "CHECK_CONDITION", "SET_CONTEXT")));

        for (StepType type : StepExecutionPolicy.SCHEMA3_BACKEND_TYPES) {
            assertThat(policy.isServer(runtime(type, 3, json.createObjectNode()), json.createObjectNode()))
                    .as(type.name()).isTrue();
            assertThat(policy.routesMqtt(type)).as(type.name()).isFalse();
        }
    }

    @Test
    void schema3WaitOwnershipIsActionSpecificAndIndependentOfAllowlist() {
        mqtt.setEnabled(true);
        mqtt.setDispatchEnabled(true);
        mqtt.setEnabledStepTypes(new HashSet<>());

        assertThat(policy.executor(3, StepType.WAIT,
                json.createObjectNode().put("wait_type", "EVENT"))).isEqualTo(StepExecutorKind.SERVER);
        assertThat(policy.executor(3, StepType.WAIT,
                json.createObjectNode().put("wait_type", "DURATION"))).isEqualTo(StepExecutorKind.PI);
    }

    @Test
    void schema3MoveHardwareAndSensingAreRobotOwned() {
        for (StepType type : Set.of(StepType.MOVE, StepType.CONTROL_HARDWARE, StepType.EXECUTE_SENSING)) {
            assertThat(policy.executor(3, type, json.createObjectNode()))
                    .as(type.name()).isEqualTo(StepExecutorKind.PI);
        }
    }

    @Test
    void schema2KeepsLegacyExecutorKinds() {
        assertThat(policy.isServer(runtime(StepType.MOVE, 2, json.createObjectNode()), json.createObjectNode())).isFalse();
        assertThat(policy.isServer(runtime(StepType.CHECK_CONDITION, 2, json.createObjectNode()), json.createObjectNode())).isFalse();
        assertThat(policy.isServer(runtime(StepType.SET_CONTEXT, 2, json.createObjectNode()), json.createObjectNode())).isFalse();
        assertThat(policy.isServer(runtime(StepType.WAIT, 2,
                json.createObjectNode().put("wait_type", "DURATION")), json.createObjectNode())).isTrue();
    }

    private JobStep runtime(StepType type, int schemaVersion, com.fasterxml.jackson.databind.JsonNode inputs) {
        Step definition = Step.builder().stepType(type).build();
        Job job = Job.builder().schemaVersion(schemaVersion).beOrchestrated(schemaVersion == 3).build();
        JobTask task = JobTask.builder().job(job).build();
        return JobStep.builder().jobTask(task).step(definition).stepTypeSnapshot(type.name())
                .resolvedInputsJson(inputs).build();
    }
}
