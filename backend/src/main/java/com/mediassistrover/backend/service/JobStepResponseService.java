package com.mediassistrover.backend.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.validation.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class JobStepResponseService {
    private static final Set<String> CLOSE=Set.of("CLOSE_SENDER","CLOSE_RECEIVER","CONFIRM_COMPARTMENT_CLOSED","STAFF_CLOSE_DOOR_AND_CLEAR_MECHANISM");
    private static final Set<String> VERIFY=Set.of("VERIFY_SENDER","VERIFY_RECEIVER","VERIFY_COMPARTMENT_LOCKED","STAFF_PHYSICALLY_VERIFY_LOCK");
    private final JobRepository jobs;
    private final JobStepRepository steps;
    private final JobStepResponseRepository responses;
    private final AccountRepository accounts;
    private final RuntimeStepCompletionService completion;
    private final DeliveryHandoverService handover;
    private final StaffStepResponseValidator validator;
    private final ObjectMapper json;
    private final Clock clock;
    private final StaffOperationalScopePolicy staffScope;

    public record Receipt(UUID responseId,UUID jobStepId,boolean duplicate,JsonNode result,Instant completedAt) { }

    @Transactional
    public Receipt respond(UUID stepId,String key,JsonNode body,AccountPrincipal actor) {
        if (key==null || !key.matches("[A-Za-z0-9._:-]{1,150}")) throw new BusinessRuleException("Idempotency-Key must be 1–150 letters, digits, dot, underscore, colon or hyphen");
        UUID jobId=steps.findJobId(stepId).orElseThrow(()->new ResourceNotFoundException("JobStep not found"));
        Job job=jobs.lockById(jobId).orElseThrow(); staffScope.requireJobAccess(job,actor);
        validator.validate(body);
        Account account=accounts.findById(actor.accountId()).orElseThrow();
        if (account.getStatus()!=AccountStatus.ACTIVE || account.getRole()!=actor.role() || account.isMustChangePassword()
                || account.getTokenVersion()!=actor.tokenVersion()) throw new AccessDeniedException("Active Staff/Admin session required");
        if (!job.isBeOrchestrated() || job.getSchemaVersion()!=3) throw new BusinessRuleException("UI responses require an orchestrated schema 3 Job");
        StaffStepResponseRecord previous=responses.findByJobStepJobStepIdAndIdempotencyKey(stepId,key).orElse(null);
        if (previous!=null) {
            if (!previous.getActorAccount().getAccountId().equals(actor.accountId()) || !previous.getRequestJson().equals(body))
                throw new ConflictException("Idempotency-Key already used with another actor or response");
            return receipt(previous,true);
        }
        JobStep step=steps.lockById(stepId).orElseThrow();
        RuntimeAuthorizationPolicy.requireRunning(job);
        Instant now=Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (job.getCancelRequestedAt()!=null || job.getSelectedJobStep()==null || !job.getSelectedJobStep().getJobStepId().equals(stepId)
                || step.getStatus()!=JobStepStatus.RUNNING || !step.getStartedAt().plusSeconds(step.getTimeoutSecSnapshot()).isAfter(now)
                || !job.getStartedAt().plusSeconds(job.getWorkflow().getMaxDurationSec()).isAfter(now))
            throw new BusinessRuleException("Response requires the selected, unexpired RUNNING step");
        for (JsonNode authority:List.of(step.getStep().getParamsJson(),step.getResolvedInputsJson()))
            if (("ADMIN".equals(authority.path("actor").asText()) || "ADMIN".equals(authority.path("required_role").asText())) && actor.role()!=Role.ADMIN)
                throw new AccessDeniedException("This instruction requires ADMIN");
        JsonNode input=step.getResolvedInputsJson(), data=body.path("data");
        String responseType=body.path("responseType").asText();
        ObjectNode result=json.createObjectNode();
        switch (StepType.valueOf(step.getStepTypeSnapshot())) {
            case HANDLE_PAYLOAD -> {
                require("PAYLOAD_CONFIRMED".equals(responseType),"HANDLE_PAYLOAD requires PAYLOAD_CONFIRMED");
                require(data.path("items").equals(input.path("items")),"Confirmed items must match the BE cargo snapshot");
                result.put("confirmed",data.path("confirmed").booleanValue()).put("actor_account_id",actor.accountId().toString())
                        .put("confirmed_at",now.toString()).put("source","STAFF_MANUAL");
                for (String field:List.of("action","service_request_id","compartment_id","items")) result.set(field,input.get(field).deepCopy());
                if (body.has("note")) result.set("note",body.get("note"));
            }
            case INTERACT -> {
                String prompt=input.path("prompt_key").asText(), action=input.path("action").asText();
                String expected=CLOSE.contains(prompt)?"DOOR_CLOSED":VERIFY.contains(prompt)?"LOCK_CHECKED":
                        "DISPLAY".equals(action)?"UI_DISPLAYED":"INTERACTION_CONFIRMED";
                require(expected.equals(responseType),"Instruction requires "+expected);
                require(!"SPEAK".equals(action),"SPEAK requires an integrated audio client");
                if ("UI_DISPLAYED".equals(responseType)) require(prompt.equals(data.path("prompt_key").asText()),"prompt_key mismatch");
                result.put("completed",true).put("prompt_key",prompt).put("action",action)
                        .put("source","UI_DISPLAYED".equals(responseType)?"UI":"STAFF_MANUAL")
                        .put("actor_account_id",actor.accountId().toString()).put("completed_at",now.toString());
                for (String field:List.of("confirmed","closed","locked","values")) if (data.has(field)) result.set(field,data.get(field).deepCopy());
                if (Set.of("CONFIRM","CAPTURE_INPUT").contains(action) && !result.has("confirmed"))
                    result.put("confirmed",data.has("closed")?data.path("closed").booleanValue():data.path("locked").booleanValue());
            }
            default -> throw new BusinessRuleException("This step cannot be completed by a UI response; AUTHENTICATE uses verification APIs");
        }
        handover.record(step,result,account,responseType);
        require(completion.complete(step,result,account,actor.role()==Role.ADMIN?JobEventProducerType.ADMIN:JobEventProducerType.STAFF,"UI_RESPONSE_ACCEPTED",null),"Step already completed");
        return receipt(responses.saveAndFlush(StaffStepResponseRecord.builder().jobStep(step).actorAccount(account).idempotencyKey(key)
                .requestJson(body.deepCopy()).resultJson(result.deepCopy()).createdAt(now).build()),false);
    }
    private void require(boolean value,String message) { if (!value) throw new BusinessRuleException(message); }
    private Receipt receipt(StaffStepResponseRecord value,boolean duplicate) {
        return new Receipt(value.getResponseId(),value.getJobStep().getJobStepId(),duplicate,value.getResultJson().deepCopy(),value.getCreatedAt());
    }
}
