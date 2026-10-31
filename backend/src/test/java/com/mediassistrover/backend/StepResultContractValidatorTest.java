package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.mediassistrover.backend.enums.StepType;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.validation.StepResultContractValidator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class StepResultContractValidatorTest {
    private final ObjectMapper json=new ObjectMapper();
    private final StepResultContractValidator validator=new StepResultContractValidator();

    record Contract(StepType type,ObjectNode input,ObjectNode result) {}

    static Stream<Contract> validContracts(){
        ObjectMapper j=new ObjectMapper();
        ObjectNode move=j.createObjectNode().put("reached",true).put("endpoint_id","EP-1").put("action_status",4).putNull("last_feedback_pose").put("distance_remaining_m",0.0);
        ObjectNode interact=j.createObjectNode().put("completed",true).putNull("confirmed");interact.set("input_data",j.createObjectNode());interact.putNull("actor_user_id");
        ObjectNode data=j.createObjectNode().put("request_id","REQ-1");data.set("data",j.createObjectNode());
        ObjectNode auth=j.createObjectNode().put("authenticated",true).put("verified",true).put("verification_id","VER-1").put("actor_user_id","STAFF-1").put("valid_until",Instant.parse("2026-10-08T00:00:00Z").toString()).put("purpose","DELIVERY_SENDER");
        ObjectNode hardware=j.createObjectNode().put("published",true).put("controller_ack",false).put("verified",false).put("commanded_state","LOCK_COMPARTMENT").put("position_raw",1500).put("ros_servo_id",4);
        ArrayNode items=j.createArrayNode().add(j.createObjectNode().put("item_code","DOC").put("quantity",1));ObjectNode payload=j.createObjectNode().put("confirmed",true).put("verification_mode","STAFF_CONFIRMED").put("actor_user_id","STAFF-1");payload.set("items",items);payload.putNull("weight_kg").putNull("temperature_c");
        ObjectNode sensing=j.createObjectNode().put("task_type","CROWD_DENSITY").put("captured_at","2026-10-08T00:00:00Z").putNull("evidence_ref");sensing.set("metrics",j.createObjectNode().put("count",2));
        ObjectNode notify=j.createObjectNode().put("notification_id","NTF-1").put("delivered",true).put("acked",false);
        ObjectNode wait=j.createObjectNode().put("completed",true).put("wake_reason","DURATION").put("elapsed_sec",5).putNull("event");
        ObjectNode check=j.createObjectNode().put("value",false);
        ObjectNode context=j.createObjectNode();context.set("changed_keys",j.createArrayNode().add("loops"));context.set("values",j.createObjectNode().put("loops",1));
        return Stream.of(
                new Contract(StepType.MOVE,j.createObjectNode(),move),new Contract(StepType.INTERACT,j.createObjectNode().put("action","DISPLAY"),interact),
                new Contract(StepType.DATA_SERVICE,j.createObjectNode(),data),new Contract(StepType.AUTHENTICATE,j.createObjectNode(),auth),
                new Contract(StepType.CONTROL_HARDWARE,j.createObjectNode(),hardware),new Contract(StepType.HANDLE_PAYLOAD,j.createObjectNode(),payload),
                new Contract(StepType.EXECUTE_SENSING,j.createObjectNode(),sensing),new Contract(StepType.NOTIFY,j.createObjectNode(),notify),
                new Contract(StepType.WAIT,j.createObjectNode().put("wait_type","DURATION"),wait),new Contract(StepType.CHECK_CONDITION,j.createObjectNode(),check),
                new Contract(StepType.SET_CONTEXT,j.createObjectNode(),context));
    }

    @ParameterizedTest @MethodSource("validContracts")
    void acceptsCanonicalSchema2ForEveryFixedStepType(Contract contract){
        assertThatCode(()->validator.validate(2,3,contract.type(),contract.input(),contract.result())).doesNotThrowAnyException();
    }

    @Test void rejectsUnknownRootField(){ObjectNode result=validContracts().findFirst().orElseThrow().result().deepCopy();result.put("ok",true);assertThatThrownBy(()->validator.validate(2,3,StepType.MOVE,json.createObjectNode(),result)).isInstanceOf(BusinessRuleException.class).hasMessageContaining("Unknown result field");}
    @Test void rejectsMoveThatDidNotReachDestination(){ObjectNode result=(ObjectNode)validContracts().findFirst().orElseThrow().result().deepCopy();result.put("reached",false);assertThatThrownBy(()->validator.validate(2,3,StepType.MOVE,json.createObjectNode(),result)).isInstanceOf(BusinessRuleException.class).hasMessageContaining("reached=true");}
    @Test void rejectsFalseBusinessConfirmation(){Contract c=validContracts().filter(x->x.type()==StepType.HANDLE_PAYLOAD).findFirst().orElseThrow();ObjectNode result=c.result().deepCopy().put("confirmed",false);assertThatThrownBy(()->validator.validate(2,3,c.type(),c.input(),result)).isInstanceOf(BusinessRuleException.class).hasMessageContaining("confirmed=true");}
    @Test void rejectsWrongNumericType(){Contract c=validContracts().filter(x->x.type()==StepType.CONTROL_HARDWARE).findFirst().orElseThrow();ObjectNode result=c.result().deepCopy().put("position_raw","1500");assertThatThrownBy(()->validator.validate(2,3,c.type(),c.input(),result)).isInstanceOf(BusinessRuleException.class).hasMessageContaining("integer");}
    @Test void rejectsUnknownSchemaOrGraphVersion(){Contract c=validContracts().findFirst().orElseThrow();assertThatThrownBy(()->validator.validate(99,3,c.type(),c.input(),c.result())).isInstanceOf(BusinessRuleException.class).hasMessageContaining("schemaVersion");assertThatThrownBy(()->validator.validate(2,99,c.type(),c.input(),c.result())).isInstanceOf(BusinessRuleException.class).hasMessageContaining("graphVersion");}
}
