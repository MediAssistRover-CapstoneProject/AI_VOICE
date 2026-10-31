package com.mediassistrover.backend.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.dto.verification.request.AuthVerificationRequests.Create;
import com.mediassistrover.backend.dto.verification.response.AuthVerificationResponse;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import com.mediassistrover.backend.validation.RuntimeAuthorizationPolicy;
import com.mediassistrover.backend.validation.StepResultContractValidator;
import com.mediassistrover.backend.validation.StaffOperationalScopePolicy;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class AuthVerificationServiceImpl implements AuthVerificationService {
    private final AuthVerificationRepository repository; private final JobRepository jobRepository;
    private final JobStepRepository stepRepository; private final AccountRepository accountRepository;
    private final JobEventRepository eventRepository; private final AuditService auditService;
    private final ObjectMapper objectMapper; private final Clock clock;
    private final StepResultContractValidator stepResultContractValidator;
    private final OrchestrationWorkService wakeups;
    private final StaffOperationalScopePolicy staffScope;

    @Override @Transactional public AuthVerificationResponse create(UUID jobId,Create r,AccountPrincipal actor,RequestMetadata md){
        Job job=jobRepository.lockById(jobId).orElseThrow(()->new ResourceNotFoundException("Job not found: "+jobId));staffScope.requireJobAccess(job,actor);JobStep step=stepRepository.findById(r.authJobStepId()).orElseThrow(()->new ResourceNotFoundException("JobStep not found: "+r.authJobStepId()));
        if(!step.getJobTask().getJob().getJobId().equals(jobId))throw new BusinessRuleException("Authentication Step does not belong to Job");if(step.getStep().getStepType()!=StepType.AUTHENTICATE)throw new BusinessRuleException("JobStep is not AUTHENTICATE");
        if(r.method()!=AuthVerificationMethod.STAFF_CONFIRMATION)throw new BusinessRuleException("QR_CODE requires proof verification; not supported by this endpoint");
        if(r.purpose()!=RuntimeAuthorizationPolicy.purpose(step))throw new BusinessRuleException("Authentication purpose does not match Step configuration");
        if(r.validForSeconds()<30||r.validForSeconds()>900)throw new BusinessRuleException("Verification validity must be 30-900 seconds");
        if(step.getStep().getParamsJson().has("method")&&!r.method().name().equals(step.getStep().getParamsJson().path("method").asText()))throw new BusinessRuleException("Authentication method does not match Step configuration");
        Account account=account(actor.accountId());
        if(account.getStatus()!=AccountStatus.ACTIVE||account.getRole()!=actor.role()||account.isMustChangePassword()||account.getTokenVersion()!=actor.tokenVersion())throw new AccessDeniedException("An active, unrestricted Staff/Admin account is required");
        String permission=r.purpose()==AuthVerificationPurpose.DELIVERY_SENDER?"DELIVERY_SEND":"DELIVERY_RECEIVE";
        String configured=step.getStep().getParamsJson().path("required_permission").asText(permission);
        if(!permission.equals(configured))throw new BusinessRuleException("Unsupported permission for authentication purpose");
        AuthVerification existing=repository.findByAuthJobStepJobStepId(step.getJobStepId()).orElse(null);
        if(existing!=null){
            if(!existing.getActorAccount().getAccountId().equals(actor.accountId()))throw new AccessDeniedException("Verification belongs to another actor");
            if(existing.getMethod()!=r.method()||existing.getPurpose()!=r.purpose())throw new ConflictException("Verification already issued with another contract");
            if(existing.getRevokedAt()!=null||!existing.getValidUntil().isAfter(Instant.now(clock)))throw new BusinessRuleException("Verification is revoked or expired");
            return response(existing);
        }
        RuntimeAuthorizationPolicy.requireRunning(job);
        if(step.getStatus()!=JobStepStatus.RUNNING)throw new BusinessRuleException("AUTHENTICATE Step must be RUNNING");
        Instant now=Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if(job.isBeOrchestrated() && (job.getSelectedJobStep()==null || !job.getSelectedJobStep().getJobStepId().equals(step.getJobStepId())
                || job.getCancelRequestedAt()!=null || !step.getStartedAt().plusSeconds(step.getTimeoutSecSnapshot()).isAfter(now)
                || !job.getStartedAt().plusSeconds(job.getWorkflow().getMaxDurationSec()).isAfter(now)))
            throw new BusinessRuleException("Verification requires the selected, unexpired JobStep");
        AuthVerification verification=repository.save(AuthVerification.builder().job(job).authJobStep(step).actorAccount(account).actorTokenVersion(account.getTokenVersion()).method(r.method()).purpose(r.purpose()).requiredPermission(permission).issuedAt(now).validUntil(now.plusSeconds(r.validForSeconds())).build());
        ObjectNode result=objectMapper.createObjectNode().put("authenticated",true).put("verified",true).put("verification_id",verification.getVerificationId().toString()).put("actor_user_id",account.getAccountId().toString()).put("purpose",r.purpose().name()).put("valid_until",verification.getValidUntil().toString());
        if(job.getSchemaVersion()==3) result=objectMapper.createObjectNode().put("verified",true).put("verification_id",verification.getVerificationId().toString()).put("actor_account_id",account.getAccountId().toString()).put("purpose",r.purpose().name()).put("service_request_id",job.getServiceRequest().getServiceRequestId().toString()).put("verified_at",now.toString()).put("valid_until",verification.getValidUntil().toString());
        stepResultContractValidator.validate(step,result);
        step.setResultJson(result);step.setStatus(JobStepStatus.SUCCEEDED);step.setEndedAt(now);
        eventRepository.save(JobEvent.builder().jobEventId(UUID.randomUUID()).job(job).robot(job.getRobot()).jobTask(step.getJobTask()).jobStep(step).actorAccount(account).producerType(actor.role()==Role.ADMIN?JobEventProducerType.ADMIN:JobEventProducerType.STAFF).producerSessionId(UUID.randomUUID()).sequenceNo(1).eventType("AUTH_VERIFICATION_ISSUED").previousStatus(JobStepStatus.RUNNING.name()).newStatus(JobStepStatus.SUCCEEDED.name()).statusScope("JOB_STEP").eventDataJson(result).occurredAt(now).receivedAt(now).persistedAt(now).build());
        wakeups.request(job);
        auditService.record(account,"AUTH_VERIFICATION_ISSUED","AuthVerification",verification.getVerificationId(),null,Map.of("jobId",jobId,"purpose",r.purpose().name()),md);return response(verification);
    }
    @Override public AuthVerificationResponse get(UUID id,AccountPrincipal actor){AuthVerification v=verification(id);staffScope.requireJobAccess(v.getJob(),actor);return response(v);}
    @Override public AuthVerification requireUsable(UUID id,UUID requestId,AuthVerificationPurpose purpose,AccountPrincipal actor){AuthVerification v=verification(id);Instant now=Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);if(v.getRevokedAt()!=null||!v.getValidUntil().isAfter(now))throw new BusinessRuleException("Auth verification is revoked or expired");if(v.getPurpose()!=purpose)throw new BusinessRuleException("Auth verification purpose mismatch");if(v.getJob().getServiceRequest()==null||!v.getJob().getServiceRequest().getServiceRequestId().equals(requestId))throw new BusinessRuleException("Auth verification does not belong to DeliveryRequest");if(!v.getActorAccount().getAccountId().equals(actor.accountId())&&actor.role()!=Role.ADMIN)throw new org.springframework.security.access.AccessDeniedException("Verification belongs to another Staff account");return v;}
    private AuthVerificationResponse response(AuthVerification v){return new AuthVerificationResponse(v.getVerificationId(),v.getJob().getJobId(),v.getAuthJobStep().getJobStepId(),v.getActorAccount().getAccountId(),v.getUsedByJobStep()==null?null:v.getUsedByJobStep().getJobStepId(),v.getMethod(),v.getPurpose(),v.getRequiredPermission(),v.getIssuedAt(),v.getValidUntil(),v.getRevokedAt());}
    private AuthVerification verification(UUID id){return repository.findById(id).orElseThrow(()->new ResourceNotFoundException("AuthVerification not found: "+id));}private Account account(UUID id){return accountRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Account not found: "+id));}
}
