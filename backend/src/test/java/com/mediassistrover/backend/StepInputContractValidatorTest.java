package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.validation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class StepInputContractValidatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final StepInputContractValidator validator = new StepInputContractValidator(new WorkflowJsonValidator());
    private static final String ID = "11111111-1111-1111-1111-111111111111";

    @Test void mappingRequiresActionTimeoutAndCannotRetry() throws Exception {
        for(var action:java.util.Map.of("START",55,"SAVE",60,"STOP",15).entrySet()) {
            Step s=step(StepType.EXECUTE_SENSING,"{\"sensing_type\":\"MAPPING\",\"action\":\""+action.getKey()+"\",\"mapping_session_id\":\""+ID+"\""+(action.getKey().equals("SAVE")?",\"artifact_id\":\""+ID+"\"":"")+"}");
            s.setWorkflow(Workflow.builder().schemaVersion(3).build());s.setTimeoutSec(action.getValue());assertThatCode(()->validator.validateTemplate(s)).doesNotThrowAnyException();
            s.setTimeoutSec(action.getValue()-1);assertThatThrownBy(()->validator.validateTemplate(s)).hasMessageContaining("timeoutSec");s.setTimeoutSec(action.getValue());s.setMaxAttempts(2);assertThatThrownBy(()->validator.validateTemplate(s)).hasMessageContaining("maxAttempts");
        }
    }
    @Test void commitArtifactRequiresAllIdsAndSchema3() throws Exception {
        Step s=step(StepType.DATA_SERVICE,"{\"service_key\":\"COMMIT_MAP_ARTIFACT\",\"mapping_session_id\":\""+ID+"\",\"artifact_id\":\""+ID+"\",\"floor_id\":\""+ID+"\",\"map_name\":\"Map\"}");
        s.setWorkflow(Workflow.builder().schemaVersion(3).build());assertThatCode(()->validator.validateTemplate(s)).doesNotThrowAnyException();
        ((ObjectNode)s.getParamsJson()).put("floor_id","bad");assertThatThrownBy(()->validator.validateTemplate(s)).hasMessageContaining("floor_id");((ObjectNode)s.getParamsJson()).put("floor_id",ID);s.setWorkflow(Workflow.builder().schemaVersion(2).build());assertThatThrownBy(()->validator.validateTemplate(s)).hasMessageContaining("schema 3");
    }

    @Test void schema3UnlockRequiresAnAuthorizationMappingWithAFixedPurpose() throws Exception {
        Step s=step(StepType.CONTROL_HARDWARE,"{\"action\":\"UNLOCK_COMPARTMENT\",\"compartment_id\":\"CART_MAIN\"}");
        s.setWorkflow(Workflow.builder().schemaVersion(3).build());
        assertThatThrownBy(()->validator.validateTemplate(s)).hasMessageContaining("authorization");
        ((ObjectNode)s.getParamsJson()).set("authorization",json.readTree("{\"mode\":\"STAFF_VERIFIED\",\"verification_id\":{\"$ref\":\"/outputs/AUTH/verification_id\"},\"actor_account_id\":{\"$ref\":\"/outputs/AUTH/actor_account_id\"},\"purpose\":\"DELIVERY_SENDER\",\"valid_until\":{\"$ref\":\"/outputs/AUTH/valid_until\"}}"));
        assertThatCode(()->validator.validateTemplate(s)).doesNotThrowAnyException();
        ((ObjectNode)s.getParamsJson().path("authorization")).set("purpose",json.readTree("{\"$ref\":\"/inputs/purpose\"}"));
        assertThatThrownBy(()->validator.validateTemplate(s)).hasMessageContaining("purpose");
    }

    static Stream<Arguments> validInputs() {
        return Stream.of(
                Arguments.of(StepType.MOVE, "{\"map_id\":\"" + ID + "\",\"endpoint_id\":\"" + ID + "\",\"max_speed_mps\":0.05}"),
                Arguments.of(StepType.CONTROL_HARDWARE, "{\"action\":\"LOCK_COMPARTMENT\",\"compartment_id\":\"CART_MAIN\"}"),
                Arguments.of(StepType.EXECUTE_SENSING, "{\"sensing_type\":\"CROWD_DENSITY\",\"zone_id\":\"" + ID + "\",\"window_sec\":3}"),
                Arguments.of(StepType.WAIT, "{\"wait_type\":\"DURATION\",\"duration_sec\":2}"),
                Arguments.of(StepType.INTERACT, "{\"prompt_key\":\"CONFIRM_COMPARTMENT_CLOSED\",\"action\":\"CONFIRM\",\"data\":{}}"),
                Arguments.of(StepType.DATA_SERVICE, "{\"service_key\":\"QUERY_HOSPITAL_INFORMATION\",\"question\":\"Khoa khám ở đâu?\"}"),
                Arguments.of(StepType.AUTHENTICATE, "{\"purpose\":\"DELIVERY_SENDER\",\"service_request_id\":\"" + ID + "\"}"),
                Arguments.of(StepType.HANDLE_PAYLOAD, "{\"action\":\"LOAD\",\"compartment_id\":\"CART_MAIN\",\"service_request_id\":\"" + ID + "\",\"items\":[{\"item_code\":\"KIT\",\"quantity\":2}]}"),
                Arguments.of(StepType.NOTIFY, "{\"notification_type\":\"HELP\",\"recipients\":[\"STAFF\"],\"require_ack\":true}"),
                Arguments.of(StepType.CHECK_CONDITION, "{\"expression\":{\"path\":\"/vars/loops\",\"operator\":\"LT\",\"value\":5}}"),
                Arguments.of(StepType.SET_CONTEXT, "{\"operations\":[{\"path\":\"/vars/loops\",\"operation\":\"INCREMENT\",\"value\":1}]}"));
    }

    @ParameterizedTest @MethodSource("validInputs")
    void acceptsAllElevenBusinessContracts(StepType type, String value) throws Exception {
        Step s = step(type, value);
        validator.validateTemplate(s);
        validator.validateResolved(s, s.getParamsJson());
    }

    static Stream<Arguments> invalidInputs() {
        return Stream.of(
                Arguments.of(StepType.WAIT, "{\"wait_type\":\"DURATION\",\"duration_sec\":0}", "duration_sec"),
                Arguments.of(StepType.WAIT, "{\"wait_type\":\"DURATION\",\"duration_sec\":3601}", "duration_sec"),
                Arguments.of(StepType.WAIT, "{\"wait_type\":\"DURATION\",\"duration_sec\":\"2\"}", "duration_sec"),
                Arguments.of(StepType.WAIT, "{\"wait_type\":\"DURATION\",\"duration_sec\":30}", "timeoutSec"),
                Arguments.of(StepType.WAIT, "{\"wait_type\":\"EVENT\",\"event_type\":\"X\",\"duration_sec\":1}", "duration_sec"),
                Arguments.of(StepType.EXECUTE_SENSING, "{\"sensing_type\":\"CROWD_DENSITY\",\"window_sec\":31}", "window_sec"),
                Arguments.of(StepType.MOVE, "{\"map_id\":\"1-1-1-1-1\"}", "map_id"),
                Arguments.of(StepType.MOVE, "{\"max_speed_mps\":0}", "max_speed_mps"),
                Arguments.of(StepType.MOVE, "{\"max_speed_mps\":0.301}", "max_speed_mps"),
                Arguments.of(StepType.MOVE, "{\"endpoint_group_id\":\"" + ID + "\"}", "endpoint_group_id"),
                Arguments.of(StepType.CONTROL_HARDWARE, "{\"action\":\"LOCK\",\"gpio\":18}", "gpio"),
                Arguments.of(StepType.NOTIFY, "{\"notification_type\":\"HELP\",\"recipients\":[],\"require_ack\":\"true\"}", "require_ack"),
                Arguments.of(StepType.AUTHENTICATE, "{\"purpose\":\"ADMIN\"}", "purpose"),
                Arguments.of(StepType.DATA_SERVICE, "{\"service_key\":\"RESOLVE_TARGETS\",\"sql\":\"SELECT 1\"}", "sql"),
                Arguments.of(StepType.SET_CONTEXT, "{\"operations\":[{\"path\":\"/inputs/admin\",\"value\":true}]}", "operations.path"),
                Arguments.of(StepType.SET_CONTEXT, "{\"operations\":[{\"path\":\"/vars/n\",\"operation\":\"INCREMENT\",\"value\":\"1\"}]}", "operations.value"),
                Arguments.of(StepType.CHECK_CONDITION, "{\"expression\":{\"path\":\"/vars/n\",\"operator\":\"GTE\",\"value\":\"1\"}}", "expression.value"));
    }

    @ParameterizedTest @MethodSource("invalidInputs")
    void rejectsInvalidLiteralsWithStepAndField(StepType type, String value, String field) throws Exception {
        Step s = step(type, value);
        assertThatThrownBy(() -> validator.validateTemplate(s)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("TEST_STEP").hasMessageContaining(field);
    }

    @Test void checksMappingsAndDefersOnlyReferencedValues() throws Exception {
        Step s = step(StepType.WAIT, "{\"wait_type\":\"DURATION\"}");
        s.setInputMappingJson(json.readTree("{\"duration_sec\":{\"$ref\":\"/inputs/duration\"}}"));
        validator.validateTemplate(s);
        assertThatThrownBy(() -> validator.validateResolved(s, s.getInputMappingJson())).hasMessageContaining("$ref");
        s.setInputMappingJson(json.readTree("{\"duration_sec\":\"2\"}"));
        assertThatThrownBy(() -> validator.validateTemplate(s)).hasMessageContaining("duration_sec");
    }

    @Test void validatesEffectiveDefaultsAndExactOutputReferenceCase() throws Exception {
        Step s = step(StepType.WAIT, "{\"wait_type\":\"DURATION\"}");
        s.setInputMappingJson(json.readTree("{\"duration_sec\":{\"$ref\":\"/inputs/duration\"}}"));
        Workflow w = Workflow.builder().inputSchemaJson(json.createObjectNode()).defaultInputsJson(json.createObjectNode().put("duration", 0)).build();
        var publish = new WorkflowStepContractValidator(validator, mock(StepInputScopeValidator.class));
        assertThatThrownBy(() -> publish.validate(w, List.of(s))).hasMessageContaining("duration_sec");
        w.setDefaultInputsJson(json.createObjectNode().put("duration", 2));
        publish.validate(w, List.of(s));
        s.setInputMappingJson(json.readTree("{\"duration_sec\":{\"$ref\":\"/outputs/test_step/value\"}}"));
        assertThatThrownBy(() -> publish.validate(w, List.of(s))).hasMessageContaining("unknown step output reference");
    }

    @Test void enforcesFractionalTimeoutMarginAndHardwareRetryLimit() throws Exception {
        Step wait = step(StepType.WAIT, "{\"wait_type\":\"DURATION\",\"duration_sec\":2.5}");
        wait.setTimeoutSec(3);
        assertThatThrownBy(() -> validator.validateTemplate(wait)).hasMessageContaining("timeoutSec");
        wait.setTimeoutSec(4); validator.validateTemplate(wait);
        Step sensing = step(StepType.EXECUTE_SENSING, "{\"sensing_type\":\"CROWD_DENSITY\",\"zone_id\":\"" + ID + "\",\"window_sec\":3}");
        sensing.setTimeoutSec(3);
        assertThatThrownBy(() -> validator.validateTemplate(sensing)).hasMessageContaining("timeoutSec");
        Step lock = step(StepType.CONTROL_HARDWARE, "{\"action\":\"LOCK_COMPARTMENT\",\"compartment_id\":\"CART_MAIN\"}");
        lock.setMaxAttempts(2);
        assertThatThrownBy(() -> validator.validateTemplate(lock)).hasMessageContaining("maxAttempts");
    }

    @Test void finalPiGateRequiresConcreteTargetAndRejectsServerMetadata() throws Exception {
        Step move = step(StepType.MOVE, "{\"map_id\":\"" + ID + "\",\"endpoint_id\":\"" + ID + "\",\"mode\":\"NORMAL\",\"max_speed_mps\":0.05}");
        validator.validatePiParams(move, move.getParamsJson());
        ((ObjectNode) move.getParamsJson()).remove("endpoint_id");
        assertThatThrownBy(() -> validator.validatePiParams(move, move.getParamsJson())).hasMessageContaining("endpoint_id");
        Step lock = step(StepType.CONTROL_HARDWARE, "{\"action\":\"LOCK_COMPARTMENT\",\"compartment_id\":\"CART_MAIN\",\"service_request_id\":\"" + ID + "\"}");
        assertThatThrownBy(() -> validator.validatePiParams(lock, lock.getParamsJson())).hasMessageContaining("service_request_id");
    }

    private Step step(StepType type, String params) throws Exception {
        return Step.builder().stepCode("TEST_STEP").stepType(type).timeoutSec(30).maxAttempts(1)
                .paramsJson(json.readTree(params)).inputMappingJson(json.createObjectNode()).build();
    }
    @Test void schema3NotifyChecksRecipientPolicyAndKeepsLegacyMetadataInsideData() throws Exception {
        Step notify=step(StepType.NOTIFY,"{\"notification_type\":\"REVIEW\",\"severity\":\"WARNING\",\"data\":{},\"require_ack\":true,\"ack_policy\":\"ALL\",\"recipients\":[\"STAFF\"]}");
        notify.setWorkflow(Workflow.builder().schemaVersion(3).build());validator.validateTemplate(notify);
        ObjectNode params=(ObjectNode)notify.getParamsJson();params.put("incident_id",ID);
        assertThatThrownBy(()->validator.validateTemplate(notify)).hasMessageContaining("incident_id");params.remove("incident_id");
        params.putArray("message").add("wrong type");assertThatThrownBy(()->validator.validateTemplate(notify)).hasMessageContaining("message");params.remove("message");
        params.putArray("recipients").add("not-an-account-or-role");assertThatThrownBy(()->validator.validateTemplate(notify)).hasMessageContaining("recipients");
    }
}
