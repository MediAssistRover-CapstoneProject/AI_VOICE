package com.mediassistrover.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class OperationAuthorizationService {
    private final JobRepository jobs;
    private final JobStepRepository steps;
    private final AuthVerificationRepository verifications;
    private final RobotOperationAuthorizationRepository bindings;
    private final DeliveryRequestRepository deliveries;
    private final ObjectMapper json;
    private final Clock clock;

    /** Called while selecting a step, before freezing its resolved input. Never accepts a client grant. */
    @Transactional
    public JsonNode bind(JobStep step,JsonNode claimed) {
        Job job=jobs.lockById(step.getJobTask().getJob().getJobId()).orElseThrow();
        requireSelected(job,step);
        if(claimed==null || !claimed.isObject()) fail("UNLOCK requires authorization from a completed AUTHENTICATE step");
        AuthVerification verification;
        try {verification=verifications.findById(UUID.fromString(claimed.path("verification_id").asText())).orElseThrow();}
        catch(IllegalArgumentException|NoSuchElementException ex){throw new BusinessRuleException("Unknown UNLOCK verification");}
        requireVerification(job,step,verification);
        JsonNode canonical=snapshot(verification);
        if(!canonical.equals(claimed)) fail("Authorization differs from the BE verification record");
        RobotOperationAuthorization existing=bindings.findByJobStepJobStepId(step.getJobStepId()).orElse(null);
        if(existing!=null) {
            if(!sameBinding(existing,job,step,canonical) || !existing.getVerification().getVerificationId().equals(verification.getVerificationId()))
                fail("Operation authorization is immutable");
            return existing.getAuthorizationJson().deepCopy();
        }
        if(bindings.findByVerificationVerificationId(verification.getVerificationId()).isPresent()
                || verification.getUsedByJobStep()!=null) fail("Verification has already been bound to an operation");
        verification.setUsedByJobStep(step);
        bindings.saveAndFlush(RobotOperationAuthorization.builder().verification(verification).jobStep(step).robot(job.getRobot())
                .operationKey(step.getOperationKey()).authorizationJson(canonical.deepCopy()).issuedAt(Instant.now(clock))
                .validUntil(verification.getValidUntil()).build());
        return canonical;
    }

    /** Defense at dispatch; expired grants can never be renewed by editing a workflow or context. */
    public void requireBound(JobStep step,JsonNode authorization) {
        Job job=step.getJobTask().getJob();
        requireSelected(job,step);
        RobotOperationAuthorization binding=bindings.findByJobStepJobStepId(step.getJobStepId())
                .orElseThrow(()->new BusinessRuleException("UNLOCK has no BE operation authorization"));
        requireVerification(job,step,binding.getVerification());
        if(!sameBinding(binding,job,step,authorization) || !snapshot(binding.getVerification()).equals(authorization))
            fail("UNLOCK authorization does not match the frozen operation binding");
    }

    private void requireSelected(Job job,JobStep step) {
        if(!job.isBeOrchestrated() || job.getSchemaVersion()!=3 || job.getStatus()!=JobStatus.RUNNING || job.getCancelRequestedAt()!=null
                || job.getRobot()==null || job.getServiceRequest()==null || job.getServiceRequest().getRequestType()!=ServiceRequestType.DELIVERY
                || job.getSelectedJobStep()==null || !job.getSelectedJobStep().getJobStepId().equals(step.getJobStepId())
                || step.getStep().getStepType()!=StepType.CONTROL_HARDWARE || step.getStatus()!=JobStepStatus.PENDING)
            fail("Authorization requires the selected PENDING UNLOCK of a running DELIVERY Job");
        String action=step.getStep().getParamsJson().path("action").asText();
        if(!Set.of("UNLOCK","UNLOCK_COMPARTMENT").contains(action) || step.getStep().getInputMappingJson().has("action"))
            fail("UNLOCK action must be fixed in the workflow definition");
        if(job.getStartedAt()==null || !job.getStartedAt().plusSeconds(job.getWorkflow().getMaxDurationSec()).isAfter(Instant.now(clock)))
            fail("Authorization cannot be issued after the workflow deadline");
    }
    private void requireVerification(Job job,JobStep current,AuthVerification verification) {
        Instant now=Instant.now(clock);
        JobStep auth=verification.getAuthJobStep();
        String permission=verification.getPurpose()==AuthVerificationPurpose.DELIVERY_SENDER?"DELIVERY_SEND":"DELIVERY_RECEIVE";
        JsonNode configured=current.getStep().getInputMappingJson().has("authorization")
                ?current.getStep().getInputMappingJson().path("authorization"):current.getStep().getParamsJson().path("authorization");
        if(!configured.path("purpose").isTextual() || !verification.getPurpose().name().equals(configured.path("purpose").asText()))
            fail("UNLOCK purpose must be fixed and match the workflow authorization configuration");
        DeliveryRequest delivery=deliveries.findById(job.getServiceRequest().getServiceRequestId())
                .orElseThrow(()->new BusinessRuleException("Authorization requires this Job's DeliveryRequest"));
        if(verification.getPurpose()==AuthVerificationPurpose.DELIVERY_SENDER && delivery.getPickedUpAt()!=null
                || verification.getPurpose()==AuthVerificationPurpose.DELIVERY_RECEIVER && (delivery.getPickedUpAt()==null || delivery.getDeliveredAt()!=null))
            fail("UNLOCK purpose does not match the current delivery milestone");
        if(!verification.getJob().getJobId().equals(job.getJobId()) || auth.getStatus()!=JobStepStatus.SUCCEEDED
                || auth.getSelectedAt()==null || auth.getExecutionSequenceNo()>=current.getExecutionSequenceNo()
                || !permission.equals(verification.getRequiredPermission()) || verification.getRevokedAt()!=null
                || !verification.getValidUntil().isAfter(now)) fail("Verification is expired, revoked, incomplete or outside this Job/purpose");
        Account actor=verification.getActorAccount();
        if(actor.getStatus()!=AccountStatus.ACTIVE || actor.isMustChangePassword() || !Set.of(Role.ADMIN,Role.STAFF).contains(actor.getRole())
                || verification.getActorTokenVersion()==null || verification.getActorTokenVersion()!=actor.getTokenVersion())
            fail("Verification actor is no longer authorized");
        // A newer authentication visit invalidates an earlier stage grant, even if its expiry has not elapsed.
        if(steps.findByJobId(job.getJobId()).stream().anyMatch(s->s.getSelectedAt()!=null
                && s.getStep().getStepType()==StepType.AUTHENTICATE && s.getExecutionSequenceNo()>auth.getExecutionSequenceNo()
                && s.getExecutionSequenceNo()<current.getExecutionSequenceNo())) fail("Verification is superseded by another authentication visit");
        if(auth.getResultJson()==null || !auth.getResultJson().path("verified").asBoolean()
                || !verification.getVerificationId().toString().equals(auth.getResultJson().path("verification_id").asText())
                || !verification.getPurpose().name().equals(auth.getResultJson().path("purpose").asText())) fail("Verification does not match its committed AUTHENTICATE output");
    }
    private JsonNode snapshot(AuthVerification v) {
        return json.createObjectNode().put("mode","STAFF_VERIFIED").put("verification_id",v.getVerificationId().toString())
                .put("actor_account_id",v.getActorAccount().getAccountId().toString()).put("purpose",v.getPurpose().name())
                .put("valid_until",v.getValidUntil().toString());
    }
    private boolean sameBinding(RobotOperationAuthorization binding,Job job,JobStep step,JsonNode value) {
        return binding.getRobot().getRobotId().equals(job.getRobot().getRobotId()) && binding.getOperationKey().equals(step.getOperationKey())
                && binding.getAuthorizationJson().equals(value);
    }
    private void fail(String message) {throw new BusinessRuleException(message);}
}
