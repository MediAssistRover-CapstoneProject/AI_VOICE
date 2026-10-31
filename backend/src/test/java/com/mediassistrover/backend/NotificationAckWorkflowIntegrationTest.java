package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.dto.job.request.WorkflowEventRequest;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class NotificationAckWorkflowIntegrationTest extends MySqlIntegrationTest {
    @Autowired ObjectMapper json; @Autowired PlatformTransactionManager manager; @Autowired jakarta.persistence.EntityManager em;
    @Autowired WorkflowOrchestratorService orchestrator; @Autowired OrchestrationWorkService wakeups; @Autowired WorkflowEventService workflowEvents;
    @Autowired AlertService alertService; @Autowired AlertRepository alerts; @Autowired AlertRecipientRepository recipients;
    @Autowired JobRepository jobs; @Autowired JobStepRepository steps; @Autowired JobEventRepository events;
    @Autowired com.mediassistrover.backend.validation.WorkflowExecutionReadiness readiness;
    TransactionTemplate tx;MqttTestFixture f;AccountPrincipal first,second,outsider;List<UUID> accountIds=new ArrayList<>();UUID finishStep;
    @BeforeEach void setup(){tx=new TransactionTemplate(manager);accountIds.clear();}
    @AfterEach void cleanup(){if(f!=null)tx.executeWithoutResult(s->{
        em.createNativeQuery("DELETE FROM AuditLog WHERE entity_id IN (SELECT alert_id FROM Alert WHERE job_step_id IN (SELECT x.job_step_id FROM JobStep x JOIN JobTask t ON t.job_task_id=x.job_task_id WHERE t.job_id=:id))").setParameter("id",f.jobId).executeUpdate();
        em.createNativeQuery("DELETE FROM AlertRecipient WHERE alert_id IN (SELECT a.alert_id FROM Alert a JOIN JobStep x ON x.job_step_id=a.job_step_id JOIN JobTask t ON t.job_task_id=x.job_task_id WHERE t.job_id=:id)").setParameter("id",f.jobId).executeUpdate();
        em.createNativeQuery("DELETE FROM Alert WHERE job_step_id IN (SELECT x.job_step_id FROM JobStep x JOIN JobTask t ON t.job_task_id=x.job_task_id WHERE t.job_id=:id)").setParameter("id",f.jobId).executeUpdate();
        MqttTestFixture.cleanup(em,f);
        for(UUID id:accountIds){em.createNativeQuery("DELETE FROM AuditLog WHERE account_id=:id").setParameter("id",id).executeUpdate();em.createNativeQuery("DELETE FROM Account WHERE account_id=:id").setParameter("id",id).executeUpdate();}
    });}
    AccountPrincipal account(Role role){String suffix=UUID.randomUUID().toString();Account a=Account.builder().accountCode("NA-"+suffix).username("na-"+suffix).fullName("Ack test").role(role).status(AccountStatus.ACTIVE).authProvider(AuthProvider.LOCAL).passwordHash("unused").build();em.persist(a);accountIds.add(a.getAccountId());return AccountPrincipal.fromAccount(a);}
    void fixture(String policy,boolean requireAck){f=tx.execute(s->{var created=MqttTestFixture.create(em,json,StepType.WAIT,null);first=account(Role.STAFF);second=account(Role.STAFF);outsider=account(Role.ADMIN);
        JobStep notify=em.find(JobStep.class,created.piStepId);Step definition=notify.getStep();definition.setStepType(StepType.NOTIFY);notify.setStepTypeSnapshot("NOTIFY");
        ObjectNode params=json.createObjectNode().put("notification_type","STAFF_REVIEW").put("severity","WARNING").put("require_ack",requireAck).put("ack_policy",policy);
        params.putObject("data").put("message","Inspect request");params.putArray("recipients").add(first.accountId().toString()).add(second.accountId().toString());definition.setParamsJson(params);
        JobStep wait=em.find(JobStep.class,created.endStepId);wait.setStepTypeSnapshot("WAIT");wait.getStep().setStepType(StepType.WAIT);
        wait.getStep().setParamsJson(json.createObjectNode().put("wait_type","EVENT").put("event_type","ALERT_ACKNOWLEDGED"));
        wait.getStep().setInputMappingJson(json.createObjectNode().set("correlation_key",json.createObjectNode().put("$ref","/outputs/PI/alert_id")));
        ObjectNode set=json.createObjectNode();set.putArray("operations").addObject().put("path","/vars/ack_completed").put("value",true);
        Step end=MqttTestFixture.definition(definition.getWorkflow(),definition.getTask(),StepType.SET_CONTEXT,"FINISH",3,set,json);em.persist(end);
        StepTransition route=em.createQuery("select t from StepTransition t where t.fromStep.stepId=:step and t.outcome='SUCCESS'",StepTransition.class).setParameter("step",wait.getStep().getStepId()).getSingleResult();route.setTerminalStatus(null);route.setToStep(end);
        em.persist(StepTransition.builder().workflow(definition.getWorkflow()).fromStep(end).outcome(TransitionOutcome.SUCCESS).terminalStatus(TerminalStatus.SUCCEEDED).priority(1).defaultTransition(true).build());
        em.persist(StepTransition.builder().workflow(definition.getWorkflow()).fromStep(wait.getStep()).outcome(TransitionOutcome.TIMEOUT).terminalStatus(TerminalStatus.FAILED).priority(1).defaultTransition(true).build());
        JobStep last=JobStep.builder().jobTask(notify.getJobTask()).step(end).visitNo(1).executionSequenceNo(3).stepCodeSnapshot("FINISH").stepTypeSnapshot("SET_CONTEXT")
                .status(JobStepStatus.PENDING).attemptNo(1).timeoutSecSnapshot(60).resolvedInputsJson(json.createObjectNode()).operationKey(created.jobId+":FINISH:1").build();em.persist(last);finishStep=last.getJobStepId();
        readiness.requireReady(definition.getWorkflow());em.flush();return created;});
        tx.executeWithoutResult(s->wakeups.request(jobs.findById(f.jobId).orElseThrow()));orchestrator.advance(f.jobId);orchestrator.advance(f.jobId);}
    UUID alert(){return UUID.fromString(steps.findById(f.piStepId).orElseThrow().getResultJson().path("alert_id").asText());}
    void beginWait(){orchestrator.advance(f.jobId);orchestrator.advance(f.jobId);}
    void drain(){for(int i=0;i<5;i++)orchestrator.advance(f.jobId);}
    JobStep waitStep(){return steps.findById(f.endStepId).orElseThrow();}
    long ackEvents(){return events.findAll().stream().filter(e->"ALERT_ACKNOWLEDGED".equals(e.getEventType()) && e.getJob().getJobId().equals(f.jobId)).count();}
    @Test void notificationPersistsAndCompletesWithoutDeliveryOrAck(){fixture("ANY",false);
        var notify=steps.findById(f.piStepId).orElseThrow();assertThat(notify.getStatus()).isEqualTo(JobStepStatus.SUCCEEDED);
        assertThat(notify.getResultJson().path("persisted").asBoolean()).isTrue();assertThat(notify.getResultJson().path("requires_ack").asBoolean()).isFalse();
        assertThat(alerts.findById(alert()).orElseThrow().getStatus()).isEqualTo(AlertStatus.OPEN);assertThat(recipients.findByAlertAlertIdOrderByCreatedAtAsc(alert())).allMatch(r->r.getDeliveryStatus()==AlertDeliveryStatus.PENDING);
        alertService.get(alert(),first);assertThat(ackEvents()).isZero();assertThat(steps.findById(f.piStepId).orElseThrow().getResultJson()).isEqualTo(notify.getResultJson());}
    @Test void ackBeforeWaitIsStoredAndConsumedOnce(){fixture("ANY",true);UUID id=alert();alertService.acknowledge(id,first,null);
        assertThat(ackEvents()).isEqualTo(1);assertThat(waitStep().getStatus()).isEqualTo(JobStepStatus.PENDING);beginWait();assertThat(waitStep().getStatus()).isEqualTo(JobStepStatus.SUCCEEDED);
        assertThat(waitStep().getResultJson().path("event").path("correlation_key").asText()).isEqualTo(id.toString());alertService.acknowledge(id,first,null);alertService.acknowledge(id,second,null);assertThat(ackEvents()).isEqualTo(1);
        drain();assertThat(jobs.findById(f.jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);assertThat(jobs.findById(f.jobId).orElseThrow().getContextJson().path("ack_completed").asBoolean()).isTrue();}
    @Test void ackAllWaitsForEverySnapshotRecipientAndViewingIsNotAck(){fixture("ALL",true);beginWait();assertThat(waitStep().getStatus()).isEqualTo(JobStepStatus.RUNNING);alertService.get(alert(),first);
        assertThat(ackEvents()).isZero();alertService.acknowledge(alert(),first,null);assertThat(waitStep().getStatus()).isEqualTo(JobStepStatus.RUNNING);assertThat(ackEvents()).isZero();
        alertService.acknowledge(alert(),second,null);assertThat(waitStep().getStatus()).isEqualTo(JobStepStatus.SUCCEEDED);assertThat(ackEvents()).isEqualTo(1);
        assertThat(waitStep().getResultJson().path("event").path("data").path("acknowledged_recipients").asInt()).isEqualTo(2);}
    @Test void nonRecipientEvenAdminCannotAckAndStaffCannotForgeWorkflowAck(){fixture("ANY",true);beginWait();
        assertThatThrownBy(()->alertService.acknowledge(alert(),outsider,null)).hasMessageContaining("not assigned");
        var request=new WorkflowEventRequest(UUID.randomUUID(),"ALERT_ACKNOWLEDGED",alert().toString(),Instant.now(),json.createObjectNode());
        assertThatThrownBy(()->workflowEvents.publish(f.jobId,request,outsider,null)).hasMessageContaining("recipient ACK policy");assertThat(ackEvents()).isZero();assertThat(waitStep().getStatus()).isEqualTo(JobStepStatus.RUNNING);}
    @Test void ackRollbackDoesNotPersistReceiptEventOrCompleteWait(){fixture("ANY",true);beginWait();
        tx.executeWithoutResult(s->{alertService.acknowledge(alert(),first,null);s.setRollbackOnly();});assertThat(ackEvents()).isZero();assertThat(waitStep().getStatus()).isEqualTo(JobStepStatus.RUNNING);
        assertThat(recipients.findByAlertAlertIdOrderByCreatedAtAsc(alert())).allMatch(r->r.getAcknowledgedAt()==null);alertService.acknowledge(alert(),first,null);assertThat(waitStep().getStatus()).isEqualTo(JobStepStatus.SUCCEEDED);}
    @Test void concurrentAllAckCreatesOneEventAndOneCompletion() throws Exception {fixture("ALL",true);beginWait();UUID id=alert();var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var gate=new java.util.concurrent.CountDownLatch(1);
        try{var a=pool.submit(()->{gate.await();return alertService.acknowledge(id,first,null);});var b=pool.submit(()->{gate.await();return alertService.acknowledge(id,second,null);});gate.countDown();a.get(15,java.util.concurrent.TimeUnit.SECONDS);b.get(15,java.util.concurrent.TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertThat(ackEvents()).isEqualTo(1);assertThat(waitStep().getStatus()).isEqualTo(JobStepStatus.SUCCEEDED);}
    @Test void ackForDifferentAlertDoesNotWakeCorrelatedWait(){fixture("ANY",true);beginWait();Alert unrelated=tx.execute(s->alertService.createSystemAlert("OTHER:"+UUID.randomUUID(),"OTHER",AlertSeverity.INFO,"Other alert",json.createObjectNode(),null,null,null,null,true,AlertAckPolicy.ANY,Instant.now().plusSeconds(60),json.createArrayNode().add(first.accountId().toString())));
        try{alertService.acknowledge(unrelated.getAlertId(),first,null);assertThat(waitStep().getStatus()).isEqualTo(JobStepStatus.RUNNING);assertThat(ackEvents()).isZero();}
        finally{tx.executeWithoutResult(s->{em.createNativeQuery("DELETE FROM AuditLog WHERE entity_id=:id").setParameter("id",unrelated.getAlertId()).executeUpdate();em.createNativeQuery("DELETE FROM AlertRecipient WHERE alert_id=:id").setParameter("id",unrelated.getAlertId()).executeUpdate();em.createNativeQuery("DELETE FROM Alert WHERE alert_id=:id").setParameter("id",unrelated.getAlertId()).executeUpdate();});}}
    @Test void lateAckDoesNotCompleteExpiredWaitAndExpiredAlertRejectsAck(){fixture("ANY",true);beginWait();
        tx.executeWithoutResult(s->steps.findById(f.endStepId).orElseThrow().setStartedAt(Instant.now().minusSeconds(61)));alertService.acknowledge(alert(),first,null);
        assertThat(waitStep().getStatus()).isEqualTo(JobStepStatus.RUNNING);orchestrator.advance(f.jobId);assertThat(waitStep().getStatus()).isEqualTo(JobStepStatus.TIMED_OUT);
        assertThat(jobs.findById(f.jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
        tx.executeWithoutResult(s->alerts.findById(alert()).orElseThrow().setExpiresAt(Instant.now().minusSeconds(1)));
        assertThatThrownBy(()->alertService.acknowledge(alert(),second,null)).hasMessageContaining("window has expired");}
}
