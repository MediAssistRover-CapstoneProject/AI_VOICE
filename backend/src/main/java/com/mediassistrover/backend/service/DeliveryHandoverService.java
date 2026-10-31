package com.mediassistrover.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** Business milestones require selected visits and committed physical evidence, never a UI hardware claim. */
@Service @RequiredArgsConstructor
public class DeliveryHandoverService {
    private final DeliveryRequestRepository deliveries;
    private final RobotOperationAuthorizationRepository bindings;
    private final JobStepRepository steps;
    private final RobotCommandOutboxRepository commands;
    private final Clock clock;
    private static final Set<String> CLOSE=Set.of("CLOSE_SENDER","CLOSE_RECEIVER","CONFIRM_COMPARTMENT_CLOSED","STAFF_CLOSE_DOOR_AND_CLEAR_MECHANISM");
    private static final Set<String> VERIFY=Set.of("VERIFY_SENDER","VERIFY_RECEIVER","VERIFY_COMPARTMENT_LOCKED","STAFF_PHYSICALLY_VERIFY_LOCK");

    /** Called under the callback's Job lock, in the same transaction as completion and its receipt. */
    @Transactional
    public void record(JobStep current,JsonNode result,Account actor,String responseType) {
        Job job=current.getJobTask().getJob();
        if(job.getSchemaVersion()!=3 || !job.isBeOrchestrated() || job.getServiceRequest()==null
                || job.getServiceRequest().getRequestType()!=ServiceRequestType.DELIVERY) return;
        String prompt=current.getResolvedInputsJson().path("prompt_key").asText();
        boolean cargo="PAYLOAD_CONFIRMED".equals(responseType),close="DOOR_CLOSED".equals(responseType) && CLOSE.contains(prompt),
                verify="LOCK_CHECKED".equals(responseType) && VERIFY.contains(prompt);
        if(!cargo && !close && !verify) return;
        DeliveryRequest delivery=deliveries.lockById(job.getServiceRequest().getServiceRequestId())
                .orElseThrow(()->new BusinessRuleException("Delivery runtime requires its DeliveryRequest"));
        RobotOperationAuthorization binding=bindings.findByVerificationJobJobId(job.getJobId()).stream()
                .filter(b->b.getJobStep().getSelectedAt()!=null && b.getJobStep().getExecutionSequenceNo()<current.getExecutionSequenceNo())
                .max(Comparator.comparingLong(b->b.getJobStep().getExecutionSequenceNo()))
                .orElseThrow(()->new BusinessRuleException("Delivery confirmation requires an authorized UNLOCK operation"));
        AuthVerification authentication=binding.getVerification();
        boolean sender=authentication.getPurpose()==AuthVerificationPurpose.DELIVERY_SENDER;
        if(!binding.getRobot().getRobotId().equals(job.getRobot().getRobotId()) || !binding.getOperationKey().equals(binding.getJobStep().getOperationKey()))
            fail("Delivery operation binding no longer matches robot/operation");
        if(actor.getRole()!=Role.ADMIN && !authentication.getActorAccount().getAccountId().equals(actor.getAccountId()))
            throw new AccessDeniedException("Delivery stage belongs to the verified staff account");
        if(cargo && !(sender?"LOAD":"UNLOAD").equals(current.getResolvedInputsJson().path("action").asText())
                || prompt.endsWith("_SENDER") && !sender || prompt.endsWith("_RECEIVER") && sender)
            fail("Delivery confirmation purpose does not match the authorized stage");
        if(!sender && delivery.getPickedUpAt()==null) fail("Pickup verification must finish before receiver handover");
        List<JobStep> history=steps.findByJobId(job.getJobId()).stream().filter(s->s.getSelectedAt()!=null
                && s.getExecutionSequenceNo()>binding.getJobStep().getExecutionSequenceNo()
                && s.getExecutionSequenceNo()<current.getExecutionSequenceNo()).toList();
        if(history.stream().anyMatch(s->s.getStep().getStepType()==StepType.AUTHENTICATE)) fail("Delivery grant is superseded by another authentication visit");
        String compartment=binding.getJobStep().getResolvedInputsJson().path("compartment_id").asText();
        requirePhysical(binding.getJobStep(),job,"UNLOCK_COMPARTMENT",compartment);
        if(!binding.getAuthorizationJson().equals(binding.getJobStep().getResolvedInputsJson().path("authorization")))
            fail("UNLOCK snapshot differs from its authorization binding");
        Instant now=Instant.now(clock);
        if(cargo) {
            if(!compartment.equals(result.path("compartment_id").asText())) fail("Cargo compartment does not match authorized UNLOCK");
            if(result.path("confirmed").asBoolean()) {
                if(sender) {delivery.setSenderAccount(actor);delivery.setPickupPayloadConfirmedAt(now);}
                else {delivery.setReceiverAccount(actor);delivery.setDeliveryPayloadConfirmedAt(now);}
            }
            return; // No pickup/delivery milestone before close -> LOCK -> physical staff check.
        }
        JobStep payload=latest(history,StepType.HANDLE_PAYLOAD,null);
        if(payload==null || !compartment.equals(payload.getResolvedInputsJson().path("compartment_id").asText())
                || !(sender?"LOAD":"UNLOAD").equals(payload.getResolvedInputsJson().path("action").asText())) fail("Missing cargo visit for this delivery stage");
        boolean positive=close?result.path("closed").asBoolean():result.path("locked").asBoolean();
        if(positive && (payload.getStatus()!=JobStepStatus.SUCCEEDED || !payload.getResultJson().path("confirmed").asBoolean()))
            fail("Cargo was not confirmed; a positive closure/lock check cannot complete handover");
        if(close) return;
        JobStep door=history.stream().filter(s->s.getStep().getStepType()==StepType.INTERACT
                && CLOSE.contains(s.getResolvedInputsJson().path("prompt_key").asText())
                && s.getExecutionSequenceNo()>payload.getExecutionSequenceNo())
                .max(Comparator.comparingLong(JobStep::getExecutionSequenceNo)).orElse(null);
        JobStep lock=latest(history,StepType.CONTROL_HARDWARE,"LOCK_COMPARTMENT");
        if(door==null || lock==null || lock.getExecutionSequenceNo()<=door.getExecutionSequenceNo()
                || door.getStatus()!=JobStepStatus.SUCCEEDED || !door.getResultJson().path("closed").asBoolean())
            fail("Lock check requires a positive door confirmation followed by LOCK");
        requirePhysical(lock,job,"LOCK_COMPARTMENT",compartment);
        if(!positive) return; // Preserve the negative result for the configured graph branch.
        if(sender) {
            if(delivery.getPickedUpAt()!=null) fail("Pickup milestone was already committed");
            delivery.setPickedUpAt(now);delivery.setPickupCompartmentConfirmedAt(now);
        } else {
            if(delivery.getDeliveredAt()!=null) fail("Delivery milestone was already committed");
            delivery.setDeliveredAt(now);delivery.setDeliveryCompartmentConfirmedAt(now);
        }
    }
    private JobStep latest(List<JobStep> history,StepType type,String action) {
        return history.stream().filter(s->s.getStep().getStepType()==type && (action==null || action.equals(s.getResolvedInputsJson().path("action").asText())))
                .max(Comparator.comparingLong(JobStep::getExecutionSequenceNo)).orElse(null);
    }
    private void requirePhysical(JobStep step,Job job,String action,String compartment) {
        RobotCommandOutbox command=commands.findByJobStepId(step.getJobStepId()).orElseThrow(()->new BusinessRuleException("Delivery stage lacks a committed Pi command"));
        JsonNode output=step.getResultJson(), params=command.getPayloadJson().path("step").path("params");
        var expected=step.getResolvedInputsJson().deepCopy();
        if(expected.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode)expected).remove(List.of("service_request_id","maxAttempts"));
        if(step.getStatus()!=JobStepStatus.SUCCEEDED || step.getInterruptionReason()!=null || command.getInterruptionReason()!=null
                || !command.getJobId().equals(job.getJobId()) || !command.getRobotId().equals(job.getRobot().getRobotId())
                || !command.getOperationKey().equals(step.getOperationKey()) || command.getSendAttempts()<1
                || !"COMPLETED".equals(command.getState()) || !Boolean.TRUE.equals(command.getPhysicalStopConfirmed())
                || output==null || !output.path("published").asBoolean() || !action.equals(output.path("action").asText())
                || !compartment.equals(output.path("compartment_id").asText()) || !params.equals(expected)
                || !("LOCK_COMPARTMENT".equals(action)?"LOCK":"UNLOCK").equals(output.path("commanded_state").asText()))
            fail("Delivery requires committed, stopped hardware execution with matching compartment/action");
        // Current Pi reports COMMISSIONING_ONLY for UNLOCK; that can never prove staff authorization.
        if("UNLOCK_COMPARTMENT".equals(action)) {
            if(!"STAFF_VERIFIED".equals(output.path("authorization_mode").asText())) fail("Commissioning UNLOCK cannot authorize a delivery handover");
            Instant validUntil;
            try {validUntil=Instant.parse(step.getResolvedInputsJson().path("authorization").path("valid_until").asText());}
            catch(java.time.DateTimeException ex){throw new BusinessRuleException("UNLOCK authorization expiry is invalid");}
            if(step.getStartedAt()==null || !step.getStartedAt().isBefore(validUntil) || command.getExpiresAt().isAfter(validUntil))
                fail("UNLOCK execution/command expiry exceeds its authorization window");
        }
        if("LOCK_COMPARTMENT".equals(action) && !"DEVICE_COMMAND".equals(output.path("authorization_mode").asText()))
            fail("LOCK authorization mode mismatch");
    }
    private void fail(String message) {throw new BusinessRuleException(message);}
}
