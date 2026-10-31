package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.StepType;
import com.mediassistrover.backend.validation.StepResultContractValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.*;

class Schema3StepResultContractValidatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final StepResultContractValidator validator = new StepResultContractValidator();
    record Case(String name, StepType type, JsonNode input, JsonNode result) {}

    static Stream<Case> validContracts() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode cases = json.readTree(Path.of("docs/schemas/fixtures/contract-cases.json").toFile());
        return StreamSupport.stream(cases.spliterator(), false)
                .filter(c -> c.path("valid").asBoolean())
                .filter(c -> "pi-step-outputs.schema.json".equals(c.path("schema").asText())
                        || "be-step-contracts.json".equals(c.path("schema").asText()) && c.path("instance").has("output"))
                .map(c -> {
                    JsonNode instance = c.path("instance"); StepType type = StepType.valueOf(instance.path("stepType").asText());
                    JsonNode output = instance.has("output") ? instance.get("output") : instance.get("result");
                    ObjectNode input = instance.has("input") ? (ObjectNode) instance.get("input") : json.createObjectNode();
                    if (!instance.has("input")) {
                        for (String f : Set.of("endpoint_id", "action", "compartment_id", "sensing_type", "zone_id", "map_id", "mapping_session_id"))
                            if (output.has(f)) input.set(f, output.get(f));
                        if (type == StepType.WAIT) input.put("wait_type", "DURATION").put("duration_sec", 2);
                        if ("MAPPING".equals(input.path("sensing_type").asText())) {
                            JsonNode metrics = output.path("metrics");
                            input.put("action", metrics.has("stopped") ? "STOP" : metrics.has("artifact_id") ? "SAVE" : "START");
                            if (metrics.has("artifact_id")) input.set("artifact_id", metrics.get("artifact_id"));
                        }
                    }
                    return new Case(c.path("name").asText(), type, input, output);
                });
    }

    @ParameterizedTest @MethodSource("validContracts")
    void acceptsDocumentedOutputsForAllTypesAndActions(Case c) {
        assertThatCode(() -> validator.validate(3, 3, c.type(), c.input(), c.result())).as(c.name()).doesNotThrowAnyException();
    }

    @Test void fixturesCoverEveryStepType() throws Exception {
        Set<StepType> types = new HashSet<>(); validContracts().forEach(c -> types.add(c.type()));
        assertThat(types).containsExactlyInAnyOrder(StepType.values());
    }

    @Test void rejectsMissingRequiredFieldsAndUnknownRootsAcrossAllVariants() throws Exception {
        for (Case c : validContracts().toList()) {
            ObjectNode result = c.result().deepCopy(); result.put("unknown", true);
            // AI observation extension fields are explicitly permitted by the handoff contract.
            if (!(c.type() == StepType.EXECUTE_SENSING && Set.of("CROWD_DENSITY", "RESTRICTED_ZONE").contains(result.path("sensing_type").asText())))
                assertThatThrownBy(() -> validator.validate(3, 3, c.type(), c.input(), result)).as(c.name()).hasMessageContaining("schema 3");
            String field = switch (c.type()) {
                case MOVE -> "attempts"; case CONTROL_HARDWARE -> "compartment_id"; case EXECUTE_SENSING -> "sensing_type";
                case INTERACT -> "source"; case DATA_SERVICE -> "request_id"; case AUTHENTICATE -> "verified_at";
                case HANDLE_PAYLOAD -> "actor_account_id"; case NOTIFY -> "persisted"; case WAIT -> "event";
                case CHECK_CONDITION -> "value"; case SET_CONTEXT -> "updated";
            };
            ObjectNode missing = c.result().deepCopy(); missing.remove(field);
            assertThatThrownBy(() -> validator.validate(3, 3, c.type(), c.input(), missing)).as(c.name()).hasMessageContaining("schema 3");
        }
    }

    @Test void rejectsMismatchedTargetHardwareAndEventEvenWhenShapeIsValid() throws Exception {
        Case move = validContracts().filter(c -> c.type() == StepType.MOVE).findFirst().orElseThrow();
        ObjectNode wrong = move.result().deepCopy(); wrong.put("endpoint_id", UUID.randomUUID().toString());
        assertThatThrownBy(() -> validator.validate(3, 3, move.type(), move.input(), wrong)).hasMessageContaining("does not match");
        Case lock = validContracts().filter(c -> c.type() == StepType.CONTROL_HARDWARE).findFirst().orElseThrow();
        ObjectNode fakeVerified = lock.result().deepCopy(); fakeVerified.put("verified", true);
        assertThatThrownBy(() -> validator.validate(3, 3, lock.type(), lock.input(), fakeVerified)).hasMessageContaining("constant");
        Case wait = validContracts().filter(c -> c.type() == StepType.WAIT && "EVENT".equals(c.input().path("wait_type").asText())).findFirst().orElseThrow();
        ObjectNode wrongEvent = wait.result().deepCopy(); ((ObjectNode) wrongEvent.get("event")).put("correlation_key", "other");
        assertThatThrownBy(() -> validator.validate(3, 3, wait.type(), wait.input(), wrongEvent)).hasMessageContaining("correlation_key");
    }

    @Test void aiObservationMustMatchJobMapZoneModelAndCurrentExecutionTime() throws Exception {
        Case c = validContracts().filter(x -> "CROWD_DENSITY".equals(x.result().path("sensing_type").asText())).findFirst().orElseThrow();
        ObjectNode result = c.result().deepCopy(); result.put("captured_at", Instant.now().toString());
        Job job = Job.builder().schemaVersion(3).graphVersion(3)
                .map(FacilityMap.builder().mapId(UUID.fromString(result.path("map_id").asText())).build())
                .inputJson(json.createObjectNode().put("model_version", "demo")).build();
        JobStep step = JobStep.builder().jobTask(JobTask.builder().job(job).build()).stepTypeSnapshot("EXECUTE_SENSING")
                .step(Step.builder().stepType(StepType.EXECUTE_SENSING).build()).resolvedInputsJson(c.input()).startedAt(Instant.now().minusSeconds(2)).build();
        ((ObjectNode) step.getResolvedInputsJson()).put("window_sec", 3);
        validator.validate(step, result);
        result.put("captured_at", step.getStartedAt().minusSeconds(1).toString());
        assertThatThrownBy(() -> validator.validate(step, result)).hasMessageContaining("predates");
        result.put("captured_at", Instant.now().toString()).put("zone_id", UUID.randomUUID().toString());
        assertThatThrownBy(() -> validator.validate(step, result)).hasMessageContaining("zone_id");
        result.set("zone_id", c.result().get("zone_id")); result.put("model_version", "other");
        assertThatThrownBy(() -> validator.validate(step, result)).hasMessageContaining("model_version");
        result.put("model_version", "demo"); job.setMap(FacilityMap.builder().mapId(UUID.randomUUID()).build());
        assertThatThrownBy(() -> validator.validate(step, result)).hasMessageContaining("Job map");
    }

    @Test void schemasRemainSeparateAndFalseConditionOrDeniedAuthenticationIsValid() throws Exception {
        Case move = validContracts().filter(c -> c.type() == StepType.MOVE).findFirst().orElseThrow();
        assertThatThrownBy(() -> validator.validate(2, 3, move.type(), move.input(), move.result())).hasMessageContaining("Unknown result field");
        validator.validate(3, 3, StepType.CHECK_CONDITION, json.createObjectNode(), json.createObjectNode().put("value", false));
        for (Case c : validContracts().filter(x -> x.type() == StepType.AUTHENTICATE).toList()) validator.validate(3, 3, c.type(), c.input(), c.result());
    }

    @Test void zeroWindowAllowsFreshCacheAndDelayedTransportUsesProductionTimeForAge() throws Exception {
        Case c=validContracts().filter(x->"CROWD_DENSITY".equals(x.result().path("sensing_type").asText())).findFirst().orElseThrow();
        Instant reportedAt=Instant.now().minusSeconds(60);
        ObjectNode result=c.result().deepCopy();result.put("captured_at",reportedAt.minusSeconds(1).toString());
        Job job=Job.builder().schemaVersion(3).graphVersion(3).inputJson(json.createObjectNode())
                .map(FacilityMap.builder().mapId(UUID.fromString(result.path("map_id").asText())).build()).build();
        JobStep step=JobStep.builder().stepTypeSnapshot("EXECUTE_SENSING").step(Step.builder().stepType(StepType.EXECUTE_SENSING).build())
                .jobTask(JobTask.builder().job(job).build()).resolvedInputsJson(c.input()).startedAt(reportedAt).build();
        validator.validate(step,result,reportedAt);
        result.put("captured_at",reportedAt.minusSeconds(3).toString());
        assertThatThrownBy(()->validator.validate(step,result,reportedAt)).hasMessageContaining("stale");
    }
}
