package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.dto.pi.request.*;
import com.mediassistrover.backend.dto.pi.request.JobRuntimePayloads.*;
import com.mediassistrover.backend.dto.verification.request.AuthVerificationRequests;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.*;
import com.mediassistrover.backend.service.*;
import com.mediassistrover.backend.service.impl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeAuthorizationTest {
    final ObjectMapper json = new ObjectMapper();
    final JobRepository jobs = mock(JobRepository.class);
    final JobStepRepository steps = mock(JobStepRepository.class);
    final JobEventRepository events = mock(JobEventRepository.class);
    final RobotRepository robots = mock(RobotRepository.class);
    final AuthVerificationRepository grants = mock(AuthVerificationRepository.class);
    final AccountRepository accounts = mock(AccountRepository.class);
    final JobTaskRepository tasks = mock(JobTaskRepository.class);
    final WorkflowRuntimeTransitionService transitions = mock(WorkflowRuntimeTransitionService.class);
    final JobStepRuntimeGuard guard = mock(JobStepRuntimeGuard.class);
    final Clock clock = Clock.systemUTC();
    final com.mediassistrover.backend.validation.StaffOperationalScopePolicy staffScope = mock(com.mediassistrover.backend.validation.StaffOperationalScopePolicy.class);
    PiJobRuntimeServiceImpl runtime;
    AuthVerificationServiceImpl auth;
    Job job; JobStep step; Robot robot; Account owner; DevicePrincipal device;

    @BeforeEach void setup() {
        owner = Account.builder().accountId(UUID.randomUUID()).role(Role.STAFF).status(AccountStatus.ACTIVE).build();
        robot = Robot.builder().robotId(UUID.randomUUID()).status(RobotStatus.BUSY).build();
        job = Job.builder().jobId(UUID.randomUUID()).robot(robot).status(JobStatus.RUNNING).assignedStaffAccount(owner).build();
        Step definition = Step.builder().stepId(UUID.randomUUID()).stepType(StepType.AUTHENTICATE)
                .paramsJson(json.createObjectNode().put("purpose", "DELIVERY_SENDER")).build();
        step = JobStep.builder().jobStepId(UUID.randomUUID()).jobTask(JobTask.builder().jobTaskId(UUID.randomUUID()).job(job).build())
                .step(definition).stepTypeSnapshot("AUTHENTICATE").operationKey("operation")
                .status(JobStepStatus.RUNNING).resolvedInputsJson(definition.getParamsJson()).build();
        device = new DevicePrincipal(robot.getRobotId(), "robot", 0, "device", Instant.now().plusSeconds(3600));
        when(jobs.findById(job.getJobId())).thenReturn(Optional.of(job));
        when(jobs.lockById(job.getJobId())).thenReturn(Optional.of(job));
        when(steps.findById(step.getJobStepId())).thenReturn(Optional.of(step));
        when(accounts.findById(owner.getAccountId())).thenReturn(Optional.of(owner));
        when(grants.save(any())).thenAnswer(invocation -> {AuthVerification v=invocation.getArgument(0);v.setVerificationId(UUID.randomUUID());return v;});
        when(steps.findByJobId(job.getJobId())).thenReturn(List.of(step));
        doAnswer(invocation -> {com.mediassistrover.backend.validation.RuntimeAuthorizationPolicy.requireJobScope(invocation.getArgument(0),invocation.getArgument(1));return null;})
                .when(staffScope).requireJobAccess(any(),any());
        runtime = new PiJobRuntimeServiceImpl(mock(com.mediassistrover.backend.mqtt.MqttProperties.class),mock(com.mediassistrover.backend.mqtt.MqttRolloutService.class),jobs,tasks,steps,events,robots,json,clock,
                mock(JobStepInputResolver.class),transitions,guard,mock(com.mediassistrover.backend.validation.StepResultContractValidator.class));
        auth = new AuthVerificationServiceImpl(grants,jobs,steps,accounts,events,mock(AuditService.class),json,clock,mock(com.mediassistrover.backend.validation.StepResultContractValidator.class),mock(com.mediassistrover.backend.service.OrchestrationWorkService.class),staffScope);
    }

    @ParameterizedTest @EnumSource(value=StepType.class,names={"AUTHENTICATE","HANDLE_PAYLOAD","DATA_SERVICE","NOTIFY"})
    void deviceCannotCompleteServerBusinessStep(StepType type) {
        step.getStep().setStepType(type); step.setStepTypeSnapshot(type.name());
        assertThatThrownBy(() -> runtime.stepResult(step.getJobStepId(), envelope("STEP_RESULT",
                new StepResultPayload(json.createObjectNode().put("verified",true))), device))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(step.getStatus()).isEqualTo(JobStepStatus.RUNNING);
        verify(events, never()).save(any());
    }

    @Test void deviceCannotConfirmCargoDoor() {
        step.getStep().setStepType(StepType.INTERACT); step.setStepTypeSnapshot("INTERACT");
        step.setResolvedInputsJson(json.createObjectNode().put("prompt_key","CONFIRM_COMPARTMENT_CLOSED").put("action","CAPTURE_INPUT"));
        assertThatThrownBy(() -> runtime.stepResult(step.getJobStepId(),envelope("STEP_RESULT",
                new StepResultPayload(json.createObjectNode().put("closed",true))),device)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void unrelatedStaffCannotIssueGrant() {
        Account stranger = Account.builder().accountId(UUID.randomUUID()).role(Role.STAFF).build();
        when(accounts.findById(stranger.getAccountId())).thenReturn(Optional.of(stranger));
        assertThatThrownBy(() -> auth.create(job.getJobId(),request(AuthVerificationMethod.STAFF_CONFIRMATION,AuthVerificationPurpose.DELIVERY_SENDER),
                AccountPrincipal.fromAccount(stranger),metadata())).isInstanceOf(AccessDeniedException.class);
        verify(grants,never()).save(any());
    }

    @Test void senderCannotRequestReceiverPurpose() {
        assertThatThrownBy(() -> auth.create(job.getJobId(),request(AuthVerificationMethod.STAFF_CONFIRMATION,AuthVerificationPurpose.DELIVERY_RECEIVER),
                AccountPrincipal.fromAccount(owner),metadata())).isInstanceOf(com.mediassistrover.backend.exception.BusinessRuleException.class);
    }

    @Test void qrWithoutProofIsRejected() {
        assertThatThrownBy(() -> auth.create(job.getJobId(),request(AuthVerificationMethod.QR_CODE,AuthVerificationPurpose.DELIVERY_SENDER),
                AccountPrincipal.fromAccount(owner),metadata())).isInstanceOf(com.mediassistrover.backend.exception.BusinessRuleException.class);
    }

    @Test void operationKeyCannotExposeAnotherJob() {
        Job other = Job.builder().jobId(UUID.randomUUID()).robot(Robot.builder().robotId(UUID.randomUUID()).build()).build();
        JobStep foreign = JobStep.builder().jobStepId(UUID.randomUUID()).jobTask(JobTask.builder().job(other).build()).step(step.getStep()).build();
        when(steps.findByOperationKey("operation")).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> runtime.createStepVisit(job.getJobId(),step.getStep().getStepId(),
                envelope("STEP_VISIT_CREATE",new CreateStepVisitPayload(UUID.randomUUID())),device)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void terminalJobCannotIssueGrant() {
        job.setStatus(JobStatus.CANCELLED);
        assertThatThrownBy(() -> auth.create(job.getJobId(),request(AuthVerificationMethod.STAFF_CONFIRMATION,AuthVerificationPurpose.DELIVERY_SENDER),
                AccountPrincipal.fromAccount(owner),metadata())).isInstanceOf(com.mediassistrover.backend.exception.BusinessRuleException.class);
        verify(grants,never()).save(any());
    }

    @Test void inactiveAccountCannotIssueGrant() {
        owner.setStatus(AccountStatus.INACTIVE);
        assertThatThrownBy(() -> auth.create(job.getJobId(),request(AuthVerificationMethod.STAFF_CONFIRMATION,AuthVerificationPurpose.DELIVERY_SENDER),
                AccountPrincipal.fromAccount(owner),metadata())).isInstanceOf(AccessDeniedException.class);
    }

    @Test void undeclaredPermissionCannotIssueGrant() {
        step.getStep().setParamsJson(json.createObjectNode().put("purpose","DELIVERY_SENDER").put("required_permission","SYSTEM_ADMIN"));
        assertThatThrownBy(() -> auth.create(job.getJobId(),request(AuthVerificationMethod.STAFF_CONFIRMATION,AuthVerificationPurpose.DELIVERY_SENDER),
                AccountPrincipal.fromAccount(owner),metadata())).isInstanceOf(com.mediassistrover.backend.exception.BusinessRuleException.class);
    }

    @Test void adminCanIssueGrantWithoutAssignment() {
        owner.setRole(Role.ADMIN);job.setAssignedStaffAccount(null);
        var result=auth.create(job.getJobId(),request(AuthVerificationMethod.STAFF_CONFIRMATION,AuthVerificationPurpose.DELIVERY_SENDER),
                AccountPrincipal.fromAccount(owner),metadata());
        assertThat(result.requiredPermission()).isEqualTo("DELIVERY_SEND");
        assertThat(step.getStatus()).isEqualTo(JobStepStatus.SUCCEEDED);
    }

    @Test void mappingCannotDowngradeStaffInteraction() {
        step.getStep().setStepType(StepType.INTERACT);
        step.getStep().setParamsJson(json.createObjectNode().put("required_role","STAFF").put("prompt_key","CONFIRM_COMPARTMENT_CLOSED"));
        step.setResolvedInputsJson(json.createObjectNode().put("required_role","PATIENT").put("prompt_key","WELCOME").put("action","DISPLAY"));
        assertThatThrownBy(() -> runtime.stepResult(step.getJobStepId(),envelope("STEP_RESULT",
                new StepResultPayload(json.createObjectNode().put("closed",true))),device)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void sameJobKeyCannotBeReusedForAnotherStep() {
        when(steps.findByOperationKey("operation")).thenReturn(Optional.of(step));
        assertThatThrownBy(() -> runtime.createStepVisit(job.getJobId(),UUID.randomUUID(),
                envelope("STEP_VISIT_CREATE",new CreateStepVisitPayload(UUID.randomUUID())),device))
                .isInstanceOf(com.mediassistrover.backend.exception.ConflictException.class);
    }

    private AuthVerificationRequests.Create request(AuthVerificationMethod method,AuthVerificationPurpose purpose) {
        return new AuthVerificationRequests.Create(step.getJobStepId(),method,purpose,300);
    }
    private RequestMetadata metadata(){return new RequestMetadata("127.0.0.1","test","phase15");}
    private <T> PiEnvelope<T> envelope(String type,T payload){return new PiEnvelope<>("1.0",UUID.randomUUID(),"operation",UUID.randomUUID(),robot.getRobotId(),job.getJobId(),step.getJobStepId(),type,Instant.now(),UUID.randomUUID(),1,payload);}
}
