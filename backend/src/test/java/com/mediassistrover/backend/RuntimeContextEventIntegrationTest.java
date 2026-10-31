package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.dto.job.request.WorkflowEventRequest;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.*;
import com.mediassistrover.backend.service.impl.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class RuntimeContextEventIntegrationTest extends MySqlIntegrationTest {
    @Autowired ObjectMapper json; @Autowired AccountRepository accounts; @Autowired WorkflowRepository workflows;
    @Autowired TaskRepository tasks; @Autowired StepRepository definitions; @Autowired JobRepository jobs;
    @Autowired JobTaskRepository jobTasks; @Autowired JobStepRepository jobSteps; @Autowired JobEventRepository events;
    @Autowired SetContextStepHandler contextHandler; @Autowired WorkflowEventService eventService;
    @Autowired NotifyStepHandler notifyHandler; @Autowired AlertService alertService; @Autowired AlertRepository alerts;
    @Autowired OperationsMaintenanceService maintenanceService;

    @Test void contextAssignmentsUseOneSnapshotAndRetryCannotIncrementTwice(){
        Fixture f=fixture("WF_CROWD_MONITORING","INCREMENT",JobStepStatus.RUNNING);f.job.setContextJson(json.createObjectNode().put("loops",2));
        var operations=json.createArrayNode();operations.add(json.createObjectNode().put("path","/vars/loops").put("operation","INCREMENT").put("value",1));operations.add(json.createObjectNode().put("path","/vars/original").put("value",2));var input=json.createObjectNode();input.set("operations",operations);
        var execution=contextHandler.execute(f.step,input);assertThat(execution.completed()).isTrue();assertThat(f.job.getContextJson().path("loops").asInt()).isEqualTo(3);assertThat(f.job.getContextJson().path("original").asInt()).isEqualTo(2);assertThat(execution.result().path("changed_keys")).hasSize(2);
    }

    @Test void earlyEventIsDurableConsumedOnceAndDuplicateIsIdempotent(){
        Fixture f=fixture("WF_EMERGENCY_SOS_RESPONSE","WAIT_STAFF",JobStepStatus.PENDING);UUID eventId=UUID.randomUUID();Instant occurred=Instant.now();WorkflowEventRequest request=new WorkflowEventRequest(eventId,"STAFF_ARRIVED","arrival-1",occurred,json.createObjectNode().put("note","at scene"));
        f.step.setResolvedInputsJson(json.createObjectNode().put("wait_type","EVENT").put("event_type","STAFF_ARRIVED").put("correlation_key","arrival-1"));jobSteps.flush();
        var early=eventService.publish(f.job.getJobId(),request,f.principal,metadata());assertThat(early.consumedByJobStepId()).isNull();
        f.step.setStatus(JobStepStatus.RUNNING);f.step.setStartedAt(occurred.minusSeconds(1));jobSteps.flush();
        assertThat(eventService.consumeAvailable(f.step)).isTrue();assertThat(f.step.getStatus()).isEqualTo(JobStepStatus.SUCCEEDED);assertThat(f.step.getResultJson().path("wake_reason").asText()).isEqualTo("EVENT");JobEvent stored=events.findById(eventId).orElseThrow();assertThat(stored.getConsumedByJobStep().getJobStepId()).isEqualTo(f.step.getJobStepId());assertThat(eventService.publish(f.job.getJobId(),request,f.principal,metadata()).duplicate()).isTrue();
    }

    @Test void notificationIsPendingUntilViewedAndAckCompletesLinkedStep(){
        Fixture f=fixture("WF_EMERGENCY_SOS_RESPONSE","ALERT",JobStepStatus.RUNNING);var input=json.createObjectNode().put("notification_type","SOS_TEST").put("severity","CRITICAL").put("message","Assist now").put("require_ack",true).put("ack_policy","ANY");input.putArray("recipients").add(f.account.getAccountId().toString());f.step.setResolvedInputsJson(input);f.step.setStartedAt(Instant.now());
        var dispatched=notifyHandler.execute(f.step,input);UUID alertId=UUID.fromString(dispatched.result().path("notification_id").asText());assertThat(alerts.findById(alertId).orElseThrow().getStatus()).isEqualTo(AlertStatus.OPEN);assertThat(f.step.getStatus()).isEqualTo(JobStepStatus.RUNNING);
        assertThat(alertService.get(alertId,f.principal).recipients().getFirst().deliveryStatus()).isEqualTo(AlertDeliveryStatus.DELIVERED);alertService.acknowledge(alertId,f.principal,metadata());assertThat(f.step.getStatus()).isEqualTo(JobStepStatus.SUCCEEDED);assertThat(f.step.getResultJson().path("acked").asBoolean()).isTrue();
    }

    @Test void ackAllWaitsForEveryRecipientAndDurationCannotFinishEarly(){
        Fixture f=fixture("WF_EMERGENCY_SOS_RESPONSE","ALERT",JobStepStatus.RUNNING);String suffix=UUID.randomUUID().toString().substring(0,8);Account second=accounts.save(Account.builder().accountCode("P17-B-"+suffix).username("p17.b."+suffix).fullName("Second Staff").email("p17.b."+suffix+"@example.test").role(Role.STAFF).authProvider(AuthProvider.LOCAL).passwordHash("unused").status(AccountStatus.ACTIVE).passwordChangedAt(Instant.now()).build());AccountPrincipal secondPrincipal=AccountPrincipal.fromAccount(second);var input=json.createObjectNode().put("notification_type","ACK_ALL_TEST").put("severity","WARNING").put("message","Both recipients must acknowledge").put("require_ack",true).put("ack_policy","ALL");input.putArray("recipients").add(f.account.getAccountId().toString()).add(second.getAccountId().toString());f.step.setResolvedInputsJson(input);var dispatched=notifyHandler.execute(f.step,input);UUID alertId=UUID.fromString(dispatched.result().path("notification_id").asText());alertService.acknowledge(alertId,f.principal,metadata());assertThat(f.step.getStatus()).isEqualTo(JobStepStatus.RUNNING);alertService.acknowledge(alertId,secondPrincipal,metadata());assertThat(f.step.getStatus()).isEqualTo(JobStepStatus.SUCCEEDED);
        Fixture duration=fixture("WF_CROWD_MONITORING","WAIT",JobStepStatus.RUNNING);duration.step.setResolvedInputsJson(json.createObjectNode().put("wait_type","DURATION").put("duration_sec",5));duration.step.setStartedAt(Instant.now().minusSeconds(2));jobSteps.flush();assertThat(maintenanceService.markTimedOutSteps(Instant.now())).isZero();assertThat(duration.step.getStatus()).isEqualTo(JobStepStatus.RUNNING);assertThat(maintenanceService.markTimedOutSteps(duration.step.getStartedAt().plusSeconds(5))).isEqualTo(1);assertThat(duration.step.getStatus()).isEqualTo(JobStepStatus.SUCCEEDED);
    }

    private Fixture fixture(String workflowCode,String stepCode,JobStepStatus status){String suffix=UUID.randomUUID().toString().substring(0,8);Account account=accounts.save(Account.builder().accountCode("P17-"+suffix).username("p17."+suffix).fullName("Phase 17 Staff").email("p17."+suffix+"@example.test").role(Role.STAFF).authProvider(AuthProvider.LOCAL).passwordHash("unused").status(AccountStatus.ACTIVE).passwordChangedAt(Instant.now()).build());Workflow workflow=workflows.findAll().stream().filter(w->w.getSchemaVersion()==2&&w.getWorkflowCode().equals(workflowCode)).findFirst().orElseThrow();Step definition=definitions.findByWorkflowWorkflowIdOrderBySequenceNoAsc(workflow.getWorkflowId()).stream().filter(s->s.getStepCode().equals(stepCode)).findFirst().orElseThrow();Instant now=Instant.now();Job job=jobs.saveAndFlush(Job.builder().workflow(workflow).assignedStaffAccount(account).jobCode("JOB-P17-"+suffix).status(JobStatus.RUNNING).priority(50).inputJson(json.createObjectNode()).contextJson(json.createObjectNode()).workflowHash("a".repeat(64)).protocolVersion(1).schemaVersion(2).graphVersion(3).queuedAt(now.minusSeconds(2)).startedAt(now.minusSeconds(1)).build());JobTask task=jobTasks.saveAndFlush(JobTask.builder().job(job).task(definition.getTask()).sequenceNo(definition.getTask().getSequenceNo()).taskNameSnapshot(definition.getTask().getTaskName()).configSnapshotJson(json.createObjectNode()).status(JobTaskStatus.RUNNING).startedAt(now.minusSeconds(1)).build());JobStep step=jobSteps.saveAndFlush(JobStep.builder().jobTask(task).step(definition).visitNo(1).executionSequenceNo(1).stepCodeSnapshot(stepCode).stepTypeSnapshot(definition.getStepType().name()).resolvedInputsJson(json.createObjectNode()).status(status).attemptNo(1).operationKey("p17:"+UUID.randomUUID()).timeoutSecSnapshot(definition.getTimeoutSec()).startedAt(status==JobStepStatus.RUNNING?now:null).build());return new Fixture(account,AccountPrincipal.fromAccount(account),job,step);}
    private RequestMetadata metadata(){return new RequestMetadata("127.0.0.1","test","phase17-test");}
    private record Fixture(Account account,AccountPrincipal principal,Job job,JobStep step) { }
}
