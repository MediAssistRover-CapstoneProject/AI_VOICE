package com.mediassistrover.backend.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.enums.StepType;
import com.mediassistrover.backend.exception.BusinessRuleException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/** Versioned STEP_RESULT validator: legacy schema 2 and MAR-STEP-2 schema 3. */
@Component
public class StepResultContractValidator {
    private final Schema3StepResultContractValidator schema3 = new Schema3StepResultContractValidator();
    public void validate(JobStep step, JsonNode result) {
        validate(step, result, Instant.now());
    }

    public void validate(JobStep step, JsonNode result, Instant reportedAt) {
        var job = step.getJobTask().getJob();
        StepType type;
        try { type=StepType.valueOf(step.getStepTypeSnapshot()); }
        catch (RuntimeException ex) { throw new BusinessRuleException("Unsupported StepType snapshot: "+step.getStepTypeSnapshot()); }
        validate(job.getSchemaVersion(),job.getGraphVersion(),type,step.getResolvedInputsJson(),result);
        if (job.getSchemaVersion() == 3) schema3.validateRuntimeScope(step, result, reportedAt);
    }

    public void validate(int schemaVersion,int graphVersion,StepType type,JsonNode input,JsonNode result) {
        StepContractVersions.requireJob(schemaVersion,graphVersion);
        object(result, type + " result");
        if (schemaVersion == 3) { object(input, "resolved input"); schema3.validate(type, input, result); return; }
        switch (type) {
            case MOVE -> move(input,result);
            case INTERACT -> interact(input, result);
            case DATA_SERVICE -> dataService(input,result);
            case AUTHENTICATE -> authenticate(result);
            case CONTROL_HARDWARE -> hardware(input,result);
            case HANDLE_PAYLOAD -> payload(result);
            case EXECUTE_SENSING -> sensing(result);
            case NOTIFY -> notifyResult(input,result);
            case WAIT -> waitResult(input, result);
            case CHECK_CONDITION -> check(result);
            case SET_CONTEXT -> context(result);
        }
    }

    private void move(JsonNode input,JsonNode n) {
        exact(n,"reached","endpoint_id","action_status","last_feedback_pose","distance_remaining_m");
        bool(n,"reached"); text(n,"endpoint_id"); integer(n,"action_status"); nullableNumber(n,"distance_remaining_m");
        if (!n.path("reached").asBoolean() || n.path("action_status").asInt() != 4)
            invalid("MOVE success requires reached=true and action_status=4");
        if(input!=null&&input.path("endpoint_id").isTextual()&&!input.path("endpoint_id").asText().equals(n.path("endpoint_id").asText()))invalid("MOVE endpoint_id does not match resolved input");
        JsonNode pose=n.get("last_feedback_pose");
        if (pose!=null&&!pose.isNull()) { object(pose,"last_feedback_pose"); exact(pose,"x_m","y_m","yaw_rad"); number(pose,"x_m");number(pose,"y_m");number(pose,"yaw_rad"); }
    }
    private void interact(JsonNode input,JsonNode n) {
        allowed(n,"completed","confirmed","input_data","actor_user_id","closed","locked");
        require(n,"completed","confirmed","input_data","actor_user_id");
        bool(n,"completed"); if(!n.path("completed").asBoolean())invalid("INTERACT success requires completed=true");
        nullableBoolean(n,"confirmed"); object(n.path("input_data"),"input_data"); nullableText(n,"actor_user_id");
        String action=input==null?"":input.path("action").asText();
        if("CONFIRM".equals(action)&&!n.path("confirmed").isBoolean())invalid("INTERACT CONFIRM requires confirmed boolean");
        String prompt=input==null?"":input.path("prompt_key").asText();
        if(Set.of("CONFIRM_COMPARTMENT_CLOSED","STAFF_CLOSE_DOOR_AND_CLEAR_MECHANISM").contains(prompt)){bool(n,"closed");requireActor(n);}
        if(Set.of("VERIFY_COMPARTMENT_LOCKED","STAFF_PHYSICALLY_VERIFY_LOCK").contains(prompt)){bool(n,"locked");requireActor(n);}
    }
    private void dataService(JsonNode input,JsonNode n) {
        exact(n,"data","request_id");object(n.path("data"),"data");text(n,"request_id");
        String service=input==null?"":input.path("service_key").asText();JsonNode data=n.path("data");
        switch(service){
            case "QUERY_HOSPITAL_INFORMATION" -> {
                bool(data,"found");text(data,"answer");array(data.path("items"),"data.items");integer(data,"count");
                if(data.path("found").asBoolean())object(data.path("source"),"data.source");
                else text(data,"fallback_code");
            }
            case "RESOLVE_TARGETS" -> array(data.path("targets"),"data.targets");
            case "SELECT_TARGET" -> bool(data,"has_target");
            case "CHECK_TARGET" -> bool(data,"is_valid");
            case "CREATE_FALL_INCIDENT" -> {text(data,"incident_id");bool(data,"created");}
            default -> { if(!service.isBlank())invalid("Unknown DATA_SERVICE service_key in resolved input"); }
        }
    }
    private void authenticate(JsonNode n) {
        exact(n,"authenticated","verified","verification_id","actor_user_id","valid_until","purpose");
        bool(n,"authenticated");bool(n,"verified");
        if(!n.path("authenticated").asBoolean()||!n.path("verified").asBoolean())invalid("AUTHENTICATE success requires authenticated=true and verified=true");
        text(n,"verification_id");text(n,"actor_user_id");dateTime(n,"valid_until");text(n,"purpose");
    }
    private void hardware(JsonNode input,JsonNode n) {
        exact(n,"published","controller_ack","verified","commanded_state","position_raw","ros_servo_id");
        bool(n,"published");bool(n,"controller_ack");bool(n,"verified");
        if(!n.path("published").asBoolean())invalid("CONTROL_HARDWARE success requires published=true");
        oneOf(n,"commanded_state",Set.of("LOCK","UNLOCK","LOCK_COMPARTMENT","UNLOCK_COMPARTMENT"));
        integer(n,"position_raw");integer(n,"ros_servo_id");
        if(n.path("position_raw").asInt()<0||n.path("position_raw").asInt()>2500)invalid("position_raw must be between 0 and 2500");
        String action=input==null?"":input.path("action").asText();String state=n.path("commanded_state").asText();
        if(!action.isBlank()&&!action.equals(state)&&!(action+"_COMPARTMENT").equals(state)&&!(state+"_COMPARTMENT").equals(action))invalid("commanded_state does not match requested action");
        if(state.startsWith("UNLOCK")&&(input==null||!input.path("verification_id").isTextual()||!input.path("purpose").isTextual()))invalid("UNLOCK requires verification_id and purpose");
    }
    private void payload(JsonNode n) {
        exact(n,"confirmed","verification_mode","actor_user_id","items","weight_kg","temperature_c");
        bool(n,"confirmed"); if(!n.path("confirmed").asBoolean())invalid("HANDLE_PAYLOAD success requires confirmed=true");
        oneOf(n,"verification_mode",Set.of("STAFF_CONFIRMED"));text(n,"actor_user_id");items(n.path("items"));
        nullableNumber(n,"weight_kg");nullableNumber(n,"temperature_c");
    }
    private void sensing(JsonNode n) {
        exact(n,"task_type","metrics","captured_at","evidence_ref");
        oneOf(n,"task_type",Set.of("ROBOT_HEALTH","SIDE_CLEARANCE","CROWD_DENSITY","RESTRICTED_AREA_INTRUSION","RESTRICTED_ZONE"));
        object(n.path("metrics"),"metrics");dateTime(n,"captured_at");nullableText(n,"evidence_ref");
        String type=n.path("task_type").asText();JsonNode m=n.path("metrics");
        if("CROWD_DENSITY".equals(type)){integer(m,"count");if(m.path("count").asInt()<0)invalid("metrics.count must be >= 0");}
        if(Set.of("RESTRICTED_AREA_INTRUSION","RESTRICTED_ZONE").contains(type))bool(m,"intrusion_detected");
        if("ROBOT_HEALTH".equals(type))bool(m,"sensors_ready");
        if("SIDE_CLEARANCE".equals(type)){nullableNumber(m,"left_m");nullableNumber(m,"right_m");bool(m,"left_fresh");bool(m,"right_fresh");}
    }
    private void notifyResult(JsonNode input,JsonNode n) { exact(n,"notification_id","delivered","acked");text(n,"notification_id");bool(n,"delivered");bool(n,"acked");if(!n.path("delivered").asBoolean())invalid("NOTIFY success requires delivered=true");if(input!=null&&input.path("require_ack").asBoolean(false)&&!n.path("acked").asBoolean())invalid("NOTIFY require_ack=true requires acked=true"); }
    private void waitResult(JsonNode input,JsonNode n) {
        exact(n,"completed","wake_reason","elapsed_sec","event");bool(n,"completed");text(n,"wake_reason");number(n,"elapsed_sec");
        if(!n.path("completed").asBoolean()||n.path("elapsed_sec").asDouble()<0)invalid("WAIT success requires completed=true and elapsed_sec >= 0");
        String type=input==null?"":input.path("wait_type").asText();JsonNode event=n.get("event");
        if("DURATION".equals(type)){if(event==null||!event.isNull())invalid("WAIT DURATION requires event=null");}
        else if("EVENT".equals(type)){object(event,"event");for(String f:List.of("event_id","type","correlation_key","job_id","actor_user_id","occurred_at"))text(event,f);dateTime(event,"occurred_at");}
    }
    private void check(JsonNode n) { exact(n,"value");bool(n,"value"); }
    private void context(JsonNode n) { exact(n,"changed_keys","values");array(n.path("changed_keys"),"changed_keys");object(n.path("values"),"values");Set<String> changed=new LinkedHashSet<>();n.path("changed_keys").forEach(v->{if(!v.isTextual()||v.asText().isBlank())invalid("changed_keys must contain non-blank strings");if(!changed.add(v.asText()))invalid("changed_keys must be unique");});Set<String> values=new LinkedHashSet<>();n.path("values").fieldNames().forEachRemaining(values::add);if(!changed.equals(values))invalid("changed_keys must equal values field names"); }

    private void items(JsonNode n){array(n,"items");if(n.isEmpty())invalid("items must not be empty");for(JsonNode item:n){object(item,"item");exact(item,"item_code","quantity");text(item,"item_code");integer(item,"quantity");if(item.path("quantity").asInt()<1)invalid("item quantity must be >= 1");}}
    private void requireActor(JsonNode n){if(!n.path("actor_user_id").isTextual()||n.path("actor_user_id").asText().isBlank())invalid("Staff confirmation requires actor_user_id");}
    private void exact(JsonNode n,String...allowed){Set<String>a=Set.of(allowed);n.fieldNames().forEachRemaining(f->{if(!a.contains(f))invalid("Unknown result field: "+f);});for(String f:allowed)if(!n.has(f))invalid("Missing result field: "+f);}
    private void allowed(JsonNode n,String...fields){Set<String>a=Set.of(fields);n.fieldNames().forEachRemaining(f->{if(!a.contains(f))invalid("Unknown result field: "+f);});}
    private void require(JsonNode n,String...fields){for(String f:fields)if(!n.has(f))invalid("Missing result field: "+f);}
    private void object(JsonNode n,String f){if(n==null||!n.isObject())invalid(f+" must be an object");}
    private void array(JsonNode n,String f){if(n==null||!n.isArray())invalid(f+" must be an array");}
    private void text(JsonNode n,String f){if(!n.path(f).isTextual()||n.path(f).asText().isBlank())invalid(f+" must be a non-blank string");}
    private void nullableText(JsonNode n,String f){JsonNode v=n.get(f);if(v==null||(!v.isNull()&&!v.isTextual()))invalid(f+" must be string or null");}
    private void bool(JsonNode n,String f){if(!n.path(f).isBoolean())invalid(f+" must be boolean");}
    private void nullableBoolean(JsonNode n,String f){JsonNode v=n.get(f);if(v==null||(!v.isNull()&&!v.isBoolean()))invalid(f+" must be boolean or null");}
    private void number(JsonNode n,String f){if(!n.path(f).isNumber())invalid(f+" must be number");}
    private void nullableNumber(JsonNode n,String f){JsonNode v=n.get(f);if(v==null||(!v.isNull()&&!v.isNumber()))invalid(f+" must be number or null");}
    private void integer(JsonNode n,String f){if(!n.path(f).isIntegralNumber())invalid(f+" must be integer");}
    private void oneOf(JsonNode n,String f,Set<String> values){text(n,f);if(!values.contains(n.path(f).asText()))invalid(f+" has unsupported value");}
    private void dateTime(JsonNode n,String f){text(n,f);try{Instant.parse(n.path(f).asText());}catch(Exception ex){invalid(f+" must be UTC ISO-8601 date-time");}}
    private void invalid(String message){throw new BusinessRuleException("Invalid Step result: "+message);}
}
