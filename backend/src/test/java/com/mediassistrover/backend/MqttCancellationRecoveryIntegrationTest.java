package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mediassistrover.backend.dto.job.request.*;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.mqtt.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MqttCancellationRecoveryIntegrationTest extends MySqlIntegrationTest {
    @Autowired ObjectMapper json; @Autowired MqttWire wire; @Autowired MqttProperties config;
    @Autowired MqttConnectionState connection; @Autowired MqttInboundRouter router; @Autowired MqttDurablePublisher publisher;
    @Autowired MqttOperationRecoveryService recovery; @Autowired WorkflowOrchestratorService orchestrator; @Autowired OrchestrationWorkService work;
    MqttMaintenanceScheduler maintenance; @Autowired MqttSessionRegistry registry; @Autowired MqttInboxProcessor processor;
    @Autowired JobRepository jobs; @Autowired JobStepRepository steps; @Autowired RobotRepository robots;
    @Autowired RobotCommandOutboxRepository commands; @Autowired RobotMqttSessionRepository sessions; @Autowired RobotMqttInboxRepository inbox;
    @Autowired MqttOperationRecoveryReceiptRepository receipts; @Autowired JobEventRepository events;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc; @Autowired com.mediassistrover.backend.security.JwtTokenService jwt;
    @Autowired PlatformTransactionManager manager; @Autowired jakarta.persistence.EntityManager em;
    @Autowired NavigationProfileService profiles;
    @MockitoBean PahoMqttTransport transport;
    TransactionTemplate tx;MqttTestFixture f;AccountPrincipal admin;
    final List<JsonNode> published=new java.util.concurrent.CopyOnWriteArrayList<>();
    @BeforeEach void setup(){tx=new TransactionTemplate(manager);config.setEnabled(true);config.setDispatchEnabled(true);config.setAllowSimulation(true);
        maintenance=new MqttMaintenanceScheduler(config,transport,inbox,commands,sessions,registry,processor,publisher,wire,Clock.systemUTC());
        config.setEnabledStepTypes(new HashSet<>(List.of("WAIT","MOVE","CONTROL_HARDWARE","EXECUTE_SENSING")));config.getRobotIds().clear();connection.connectedNow();published.clear();
        when(transport.connected()).thenReturn(true);when(transport.publish(anyString(),any(byte[].class),anyInt())).thenAnswer(call->{published.add(wire.parse(call.getArgument(1)));return CompletableFuture.completedFuture(null);});}
    @AfterEach void cleanup(){if(f!=null)tx.executeWithoutResult(s->MqttTestFixture.cleanup(em,f));config.setEnabled(false);config.setDispatchEnabled(false);config.getRobotIds().clear();connection.disconnected();}
    void fixture(StepType type){f=tx.execute(s->MqttTestFixture.create(em,json,type,null));config.getRobotIds().add(f.robotId);
        if(f.accountId!=null)admin=tx.execute(s->AccountPrincipal.fromAccount(em.find(Account.class,f.accountId)));
        tx.executeWithoutResult(s->work.request(jobs.findById(f.jobId).orElseThrow()));orchestrator.advance(f.jobId);}
    void receive(ObjectNode event){router.route(MqttTopics.topic(f.robotId,"events"),wire.bytes(event),1,false);}
    void heartbeat(){router.route(MqttTopics.topic(f.robotId,"heartbeat"),wire.bytes(f.heartbeat(json)),0,false);}
    void hello(String state){ObjectNode hello=f.hello(json);if(state!=null)((ArrayNode)hello.path("payload").path("states")).addObject().put("jobId",f.jobId.toString()).put("jobStepId",f.piStepId.toString()).put("operationKey",f.operationKey).put("status",state);receive(hello);heartbeat();}
    void dispatch(boolean started){hello(null);confirmProfile();orchestrator.advance(f.jobId);var execute=commands.findByJobStepId(f.piStepId).orElseThrow();f.commandId=execute.getCommandId();publisher.command(f.commandId);
        if(started){receive(f.event(json,"COMMAND_ACK",json.createObjectNode().put("commandId",f.commandId.toString()).put("commandType","EXECUTE_STEP").put("accepted",true)));
            receive(f.event(json,"STEP_STARTED",json.createObjectNode().put("attemptNo",1).put("stepType",f.type.name()).set("resolvedInputs",execute.getPayloadJson().path("step").path("params"))));}}
    void confirmProfile(){if(f.type!=StepType.MOVE)return;var robot=robots.findById(f.robotId).orElseThrow();var device=new com.mediassistrover.backend.security.DevicePrincipal(robot.getRobotId(),robot.getRobotCode(),robot.getDeviceTokenVersion(),"test",Instant.now().plusSeconds(300));var manifest=profiles.manifest(device);profiles.confirm(new NavigationProfileService.Confirm(f.transportId,f.producerId,f.mapId,manifest.profile().path("map_asset_sha256").asText(),manifest.profileHash()),device);}
    RobotCommandOutbox cancel(){return commands.findByJobStepIdAndCommandType(f.piStepId,"CANCEL_STEP").orElseThrow();}
    void requestCancel(){tx.executeWithoutResult(s->{var job=jobs.findById(f.jobId).orElseThrow();job.setCancelRequestedAt(Instant.now());work.request(job);});orchestrator.advance(f.jobId);}
    void expireStep(){tx.executeWithoutResult(s->{steps.findById(f.piStepId).orElseThrow().setStartedAt(Instant.now().minusSeconds(61));work.request(jobs.findById(f.jobId).orElseThrow());});orchestrator.advance(f.jobId);}
    void error(String state,Boolean stopped,UUID correlation){ObjectNode payload=json.createObjectNode().put("status",state).put("errorCode","TEST_STOP").put("retryable",false);payload.putNull("details");if(stopped!=null)payload.put("physicalStopConfirmed",stopped);
        ObjectNode event=f.event(json,"STEP_ERROR",payload);if(correlation!=null)event.put("correlationId",correlation.toString());receive(event);}
    ObjectNode cancelAck(String state,Boolean stopped,boolean accepted,boolean nullScope){var command=cancel();ObjectNode p=json.createObjectNode().put("commandId",command.getCommandId().toString()).put("commandType","CANCEL_STEP").put("accepted",accepted).put("status",state);if(stopped!=null)p.put("physicalStopConfirmed",stopped);
        ObjectNode e=f.event(json,"COMMAND_ACK",p);e.put("correlationId",command.getCommandId().toString());if(nullScope){e.putNull("jobId");e.putNull("jobStepId");e.put("operationKey",e.path("messageId").asText());}return e;}
    Job job(){return jobs.findById(f.jobId).orElseThrow();} JobStep step(){return steps.findById(f.piStepId).orElseThrow();}
    MqttOperationRecoveryRequest body(MqttOperationRecoveryRequest.Evidence evidence){return new MqttOperationRecoveryRequest(f.commandId,f.operationKey,f.transportId,evidence,true,JobRequests.ReconcileOutcome.CONFIRM_CANCELLED,"Inspection completed");}

    @Test void timeoutCreatesOneCancelAndWaitsForStopBeforeTimeoutEdge(){fixture(StepType.MOVE);dispatch(true);expireStep();var cancel=cancel();
        assertThat(step().getStatus()).isEqualTo(JobStepStatus.RUNNING);assertThat(step().getInterruptionReason()).isEqualTo("STEP_TIMEOUT");assertThat(job().getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(robots.findById(f.robotId).orElseThrow().getStatus()).isEqualTo(RobotStatus.BUSY);publisher.command(cancel.getCommandId());
        JsonNode sent=published.getLast();assertThat(sent.path("commandType").asText()).isEqualTo("CANCEL_STEP");assertThat(sent.has("step")).isFalse();assertThat(sent.path("jobStepId").asText()).isEqualTo(f.piStepId.toString());
        tx.executeWithoutResult(s->work.request(jobs.findById(f.jobId).orElseThrow()));orchestrator.advance(f.jobId);assertThat(commands.findByJobIdOrderByCreatedAt(f.jobId)).hasSize(2);
        error("CANCELLED",true,f.commandId);assertThat(step().getStatus()).isEqualTo(JobStepStatus.CANCELLED);orchestrator.advance(f.jobId);
        assertThat(job().getStatus()).isEqualTo(JobStatus.FAILED);assertThat(steps.findById(f.endStepId).orElseThrow().getStartedAt()).isNull();
        receive(cancelAck("CANCELLED",true,true,false));assertThat(inbox.findAll().stream().filter(r->"COMMAND_ACK".equals(r.getMessageType())).allMatch(r->Boolean.TRUE.equals(r.getAccepted()))).isTrue();}

    @Test void cancelAckWithNullScopeUsesStoredCommandAndEndsOnlyAfterStop(){fixture(StepType.MOVE);dispatch(true);requestCancel();publisher.command(cancel().getCommandId());
        ObjectNode ack=cancelAck("CANCELLED",true,true,true);receive(ack);assertThat(job().getStatus()).isEqualTo(JobStatus.CANCELLED);assertThat(step().getStatus()).isEqualTo(JobStepStatus.CANCELLED);
        long count=events.count();receive(ack);assertThat(events.count()).isEqualTo(count);
        error("CANCELLED",true,f.commandId);assertThat(job().getStatus()).isEqualTo(JobStatus.CANCELLED);assertThat(robots.findById(f.robotId).orElseThrow().getStatus()).isEqualTo(RobotStatus.IDLE);}

    @Test void errorCanCorrelateCancelCommandBeforeItsAck(){fixture(StepType.MOVE);dispatch(true);requestCancel();publisher.command(cancel().getCommandId());
        error("CANCELLED",true,cancel().getCommandId());assertThat(job().getStatus()).isEqualTo(JobStatus.CANCELLED);receive(cancelAck("CANCELLED",true,true,false));assertThat(cancel().getState()).isEqualTo("STOP_CONFIRMED");}

    @Test void cancelAcceptanceAloneCannotReleaseRobotAndDeadlineRequiresRecovery(){fixture(StepType.MOVE);dispatch(true);requestCancel();publisher.command(cancel().getCommandId());receive(cancelAck("ACCEPTED",null,true,false));
        assertThat(job().getStatus()).isEqualTo(JobStatus.RUNNING);assertThat(step().getStatus()).isEqualTo(JobStepStatus.RUNNING);
        tx.executeWithoutResult(s->{commands.findById(cancel().getCommandId()).orElseThrow().setExpiresAt(Instant.now().minusSeconds(1));work.request(jobs.findById(f.jobId).orElseThrow());});orchestrator.advance(f.jobId);
        assertThat(job().getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(robots.findById(f.robotId).orElseThrow().getStatus()).isEqualTo(RobotStatus.ERROR);}

    @Test void cancelWithoutStopOrRejectedCancelCannotContinueGraph(){for(String variant:List.of("MISSING","FALSE","REJECTED")){fixture(StepType.MOVE);dispatch(true);expireStep();publisher.command(cancel().getCommandId());
        receive(cancelAck("CANCELLED",variant.equals("MISSING")?null:false,!variant.equals("REJECTED"),false));assertThat(job().getStatus()).as(variant).isEqualTo(JobStatus.RECOVERY_REQUIRED);
        orchestrator.advance(f.jobId);assertThat(steps.findById(f.endStepId).orElseThrow().getStartedAt()).isNull();tx.executeWithoutResult(s->MqttTestFixture.cleanup(em,f));f=null;}}

    @Test void unpublishedCancelEndsLocallyAndNeverDispatchesItsExecute(){fixture(StepType.WAIT);hello(null);orchestrator.advance(f.jobId);requestCancel();
        assertThat(job().getStatus()).isEqualTo(JobStatus.CANCELLED);assertThat(commands.findByJobStepId(f.piStepId).orElseThrow().getState()).isEqualTo("ABANDONED");
        publisher.command(commands.findByJobStepId(f.piStepId).orElseThrow().getCommandId());assertThat(published).noneMatch(p->"COMMAND".equals(p.path("messageType").asText()));}

    @Test void waitDurationClockNeverCreatesSuccessAndTimeoutUsesCancel(){fixture(StepType.WAIT);dispatch(true);tx.executeWithoutResult(s->{steps.findById(f.piStepId).orElseThrow().setStartedAt(Instant.now().minusSeconds(3));work.request(jobs.findById(f.jobId).orElseThrow());});orchestrator.advance(f.jobId);
        assertThat(step().getStatus()).isEqualTo(JobStepStatus.RUNNING);assertThat(step().getResultJson()).isNull();assertThat(commands.findByJobStepIdAndCommandType(f.piStepId,"CANCEL_STEP")).isEmpty();expireStep();assertThat(cancel()).isNotNull();}

    @Test void workflowDeadlineCancelsAndDoesNotFinishBeforeStop(){fixture(StepType.MOVE);dispatch(true);tx.executeWithoutResult(s->{var job=jobs.findById(f.jobId).orElseThrow();job.setStartedAt(Instant.now().minusSeconds(601));work.request(job);});orchestrator.advance(f.jobId);
        assertThat(cancel().getInterruptionReason()).isEqualTo("WORKFLOW_TIMEOUT");assertThat(job().getStatus()).isEqualTo(JobStatus.RUNNING);publisher.command(cancel().getCommandId());error("CANCELLED",true,f.commandId);
        assertThat(job().getStatus()).isEqualTo(JobStatus.FAILED);assertThat(job().getErrorCode()).isEqualTo("WORKFLOW_DURATION_EXCEEDED");}

    @Test void stopCommandStillPublishesWhenNewExecuteDispatchIsDisabled(){fixture(StepType.MOVE);dispatch(true);requestCancel();config.setDispatchEnabled(false);config.getEnabledStepTypes().clear();publisher.command(cancel().getCommandId());
        assertThat(published.getLast().path("commandType").asText()).isEqualTo("CANCEL_STEP");assertThat(cancel().getSendAttempts()).isEqualTo(1);}

    @Test void noHeartbeatAfterBackendDisconnectHoldsOperationAndOldSessionCannotResume(){fixture(StepType.MOVE);dispatch(true);connection.disconnected();when(transport.connected()).thenReturn(false);
        tx.executeWithoutResult(s->sessions.findById(f.robotId).orElseThrow().setLastHeartbeatAt(Instant.now().minusSeconds(16)));maintenance.heartbeats();
        assertThat(job().getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(sessions.findById(f.robotId).orElseThrow().isClosed()).isTrue();
        connection.connectedNow();when(transport.connected()).thenReturn(true);f.transportId=UUID.randomUUID();hello("RUNNING");publisher.command(f.commandId);
        assertThat(published).filteredOn(p->"EXECUTE_STEP".equals(p.path("commandType").asText())).hasSize(1);}

    @Test void staffStopClaimCannotReplacePiEvidenceAndRunningJournalBlocksRecovery(){fixture(StepType.MOVE);dispatch(true);error("FAILED",null,f.commandId);hello("RUNNING");
        assertThatThrownBy(()->recovery.reconcile(f.jobId,f.piStepId,"review",body(MqttOperationRecoveryRequest.Evidence.PI_STOP_CONFIRMED),admin,null)).hasMessageContaining("terminal");
        hello("CANCELLED");assertThatThrownBy(()->recovery.reconcile(f.jobId,f.piStepId,"review",body(MqttOperationRecoveryRequest.Evidence.PI_STOP_CONFIRMED),admin,null)).hasMessageContaining("No committed Pi stop");
        assertThatThrownBy(()->recovery.reconcile(f.jobId,f.piStepId,"inspect",body(MqttOperationRecoveryRequest.Evidence.STAFF_INSPECTED_STOP),admin,null)).hasMessageContaining("OPERATOR_RECONCILED");}

    @Test void staffInspectionAfterLocalOperatorReconcileIsIdempotentAndRequiresNewHello(){fixture(StepType.MOVE);dispatch(true);error("FAILED",null,f.commandId);
        assertThat(recovery.evidence(f.jobId,admin).path("commands").get(0).path("operatorReconciled").asBoolean()).isFalse();
        receive(f.event(json,"OPERATOR_RECONCILED",json.createObjectNode().put("reason","Checked motor and cart").put("status","CANCELLED")));hello("CANCELLED");var body=body(MqttOperationRecoveryRequest.Evidence.STAFF_INSPECTED_STOP);
        assertThat(recovery.evidence(f.jobId,admin).path("commands").get(0).path("operatorReconciled").asBoolean()).isTrue();
        JsonNode receipt=recovery.reconcile(f.jobId,f.piStepId,"inspection-1",body,admin,new RequestMetadata("127.0.0.1","test","recovery"));
        assertThat(job().getStatus()).isEqualTo(JobStatus.CANCELLED);assertThat(commands.findById(f.commandId).orElseThrow().getPhysicalStopConfirmed()).isNull();assertThat(commands.findById(f.commandId).orElseThrow().getState()).isEqualTo("RESOLVED_BY_STAFF");
        assertThat(sessions.findById(f.robotId).orElseThrow().isClosed()).isTrue();assertThat(robots.findById(f.robotId).orElseThrow().getStatus()).isEqualTo(RobotStatus.STARTING);
        assertThat(recovery.reconcile(f.jobId,f.piStepId,"inspection-1",body,admin,null)).isEqualTo(receipt);assertThat(receipts.count()).isEqualTo(1);
        var different=new MqttOperationRecoveryRequest(f.commandId,f.operationKey,f.transportId,body.evidence(),true,body.outcome(),"different note");
        assertThatThrownBy(()->recovery.reconcile(f.jobId,f.piStepId,"inspection-1",different,admin,null)).hasMessageContaining("different actor/body");
        assertThatThrownBy(()->recovery.reconcile(f.jobId,f.piStepId,"new-key",body,admin,null)).hasMessageContaining("recovery Job");
        f.transportId=UUID.randomUUID();hello("CANCELLED");assertThat(registry.fresh(sessions.findById(f.robotId).orElseThrow())).isTrue();}

    @Test void piStopRecoveryRejectsStaleSessionAndWrongOperation(){fixture(StepType.MOVE);dispatch(true);requestCancel();publisher.command(cancel().getCommandId());receive(cancelAck("CANCELLED",false,true,false));
        error("CANCELLED",true,f.commandId);hello("CANCELLED");var b=body(MqttOperationRecoveryRequest.Evidence.PI_STOP_CONFIRMED);
        var stale=new MqttOperationRecoveryRequest(f.commandId,f.operationKey,UUID.randomUUID(),b.evidence(),true,b.outcome(),b.note());assertThatThrownBy(()->recovery.reconcile(f.jobId,f.piStepId,"stale",stale,admin,null)).hasMessageContaining("current HELLO");
        var wrong=new MqttOperationRecoveryRequest(UUID.randomUUID(),f.operationKey,f.transportId,b.evidence(),true,b.outcome(),b.note());assertThatThrownBy(()->recovery.reconcile(f.jobId,f.piStepId,"wrong",wrong,admin,null)).hasMessageContaining("commandId");
        recovery.reconcile(f.jobId,f.piStepId,"valid",b,admin,new RequestMetadata("127.0.0.1","test","valid"));assertThat(job().getStatus()).isEqualTo(JobStatus.CANCELLED);}

    @Test void recoveryRequiresOperationalScopeAndNeverConsumesAForeignActorClaim(){fixture(StepType.MOVE);dispatch(true);error("FAILED",null,f.commandId);
        receive(f.event(json,"OPERATOR_RECONCILED",json.createObjectNode().put("reason","Stopped locally").put("status","CANCELLED")));hello("CANCELLED");
        var staff=tx.execute(s->{Account actor=em.find(Account.class,f.accountId);actor.setRole(Role.STAFF);return AccountPrincipal.fromAccount(actor);});
        var body=body(MqttOperationRecoveryRequest.Evidence.STAFF_INSPECTED_STOP);
        assertThatThrownBy(()->recovery.reconcile(f.jobId,f.piStepId,"scope",body,staff,null)).hasMessageContaining("operational scope");
        tx.executeWithoutResult(s->{jobs.findById(f.jobId).orElseThrow().setAssignedStaffAccount(em.find(Account.class,f.accountId));em.find(Account.class,f.accountId).setStatus(AccountStatus.INACTIVE);});
        assertThatThrownBy(()->recovery.reconcile(f.jobId,f.piStepId,"inactive",body,staff,null)).hasMessageContaining("unrestricted Staff/Admin");
        tx.executeWithoutResult(s->em.find(Account.class,f.accountId).setStatus(AccountStatus.ACTIVE));recovery.reconcile(f.jobId,f.piStepId,"assigned",body,staff,null);assertThat(job().getStatus()).isEqualTo(JobStatus.CANCELLED);}

    @Test void concurrentRecoveryUsesOneReceiptAndRollbackRestoresHeldRobot() throws Exception {fixture(StepType.MOVE);dispatch(true);error("FAILED",null,f.commandId);
        receive(f.event(json,"OPERATOR_RECONCILED",json.createObjectNode().put("reason","Stopped locally").put("status","CANCELLED")));hello("CANCELLED");var body=body(MqttOperationRecoveryRequest.Evidence.STAFF_INSPECTED_STOP);
        tx.executeWithoutResult(s->{recovery.reconcile(f.jobId,f.piStepId,"rolled-back",body,admin,null);s.setRollbackOnly();});
        assertThat(job().getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(receipts.count()).isZero();assertThat(sessions.findById(f.robotId).orElseThrow().isClosed()).isFalse();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var latch=new java.util.concurrent.CountDownLatch(1);
        try {var first=pool.submit(()->{latch.await();return recovery.reconcile(f.jobId,f.piStepId,"race",body,admin,null);});
            var second=pool.submit(()->{latch.await();return recovery.reconcile(f.jobId,f.piStepId,"race",body,admin,null);});latch.countDown();
            assertThat(first.get(15,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(second.get(15,java.util.concurrent.TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
        assertThat(receipts.count()).isEqualTo(1);assertThat(job().getStatus()).isEqualTo(JobStatus.CANCELLED);}

    @Test void acceptedButNeverStartedOperationExpiresIntoCancelRatherThanNextStep(){fixture(StepType.MOVE);dispatch(false);
        receive(f.event(json,"COMMAND_ACK",json.createObjectNode().put("commandId",f.commandId.toString()).put("commandType","EXECUTE_STEP").put("accepted",true)));
        tx.executeWithoutResult(s->{commands.findById(f.commandId).orElseThrow().setExpiresAt(Instant.now().minusSeconds(1));work.request(jobs.findById(f.jobId).orElseThrow());});orchestrator.advance(f.jobId);
        assertThat(cancel().getInterruptionReason()).isEqualTo("STEP_TIMEOUT");assertThat(step().getStatus()).isEqualTo(JobStepStatus.PENDING);assertThat(job().getStatus()).isEqualTo(JobStatus.RUNNING);
        publisher.command(cancel().getCommandId());receive(cancelAck("CANCELLED",true,true,true));orchestrator.advance(f.jobId);assertThat(job().getStatus()).isEqualTo(JobStatus.FAILED);}

    @Test void successfulResultRacingTimeoutUsesTimeoutEdgeAfterConfirmedStop(){fixture(StepType.MOVE);dispatch(true);expireStep();
        ObjectNode result=json.createObjectNode().put("status","SUCCEEDED").put("physicalStopConfirmed",true).put("retryable",false);result.putNull("errorCode");result.putNull("details");result.set("result",f.output(json));receive(f.event(json,"STEP_RESULT",result));
        assertThat(step().getStatus()).isEqualTo(JobStepStatus.SUCCEEDED);orchestrator.advance(f.jobId);assertThat(job().getStatus()).isEqualTo(JobStatus.FAILED);assertThat(steps.findById(f.endStepId).orElseThrow().getStartedAt()).isNull();}
    @Test void lateCancelAckForStoppedVisitIsAcceptedAfterTimeoutBranchSelectedNextStep(){fixture(StepType.MOVE);dispatch(true);
        tx.executeWithoutResult(s->{StepTransition route=em.createQuery("select t from StepTransition t where t.workflow.workflowId=:id and t.outcome='TIMEOUT'",StepTransition.class).setParameter("id",f.workflowId).getSingleResult();route.setTerminalStatus(null);route.setToStep(steps.findById(f.endStepId).orElseThrow().getStep());});
        expireStep();publisher.command(cancel().getCommandId());error("CANCELLED",true,f.commandId);orchestrator.advance(f.jobId);
        assertThat(job().getSelectedJobStep().getJobStepId()).isEqualTo(f.endStepId);ObjectNode ack=cancelAck("CANCELLED",true,true,false);receive(ack);
        assertThat(inbox.findById(UUID.fromString(ack.path("messageId").asText())).orElseThrow().getAccepted()).isTrue();assertThat(job().getStatus()).isEqualTo(JobStatus.RUNNING);
        orchestrator.advance(f.jobId);orchestrator.advance(f.jobId);assertThat(job().getStatus()).isEqualTo(JobStatus.SUCCEEDED);}
    @Test void recoveryHttpRequiresAuthenticationHeaderAndValidatedStopClaim() throws Exception {fixture(StepType.MOVE);dispatch(true);error("FAILED",null,f.commandId);
        receive(f.event(json,"OPERATOR_RECONCILED",json.createObjectNode().put("reason","Checked locally").put("status","CANCELLED")));hello("CANCELLED");
        String token=tx.execute(s->jwt.issueAccessToken(em.find(Account.class,f.accountId)).value());String path="/api/v1/jobs/"+f.jobId+"/operations/"+f.piStepId+"/reconcile";
        var b=body(MqttOperationRecoveryRequest.Evidence.STAFF_INSPECTED_STOP);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key","http").contentType("application/json").content(json.writeValueAsBytes(b)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        ObjectNode falseClaim=(ObjectNode)wire.tree(b);falseClaim.put("confirmedStopped",false);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization","Bearer "+token).header("Idempotency-Key","http").contentType("application/json").content(wire.bytes(falseClaim)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/jobs/"+f.jobId+"/operations/recovery").header("Authorization","Bearer "+token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.jobStepId").value(f.piStepId.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.physicalUncertainty.requiresStaffReconciliation").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.journal").doesNotExist());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization","Bearer "+token).header("Idempotency-Key","http").contentType("application/json").content(json.writeValueAsBytes(b)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.requiresNewHello").value(true));
        assertThat(job().getStatus()).isEqualTo(JobStatus.CANCELLED);
    }
    @Test void lateStopEvidenceDoesNotAutomaticallyReverseRecovery(){fixture(StepType.MOVE);dispatch(true);error("RECOVERY_REQUIRED",null,f.commandId);
        error("CANCELLED",true,f.commandId);assertThat(step().getStatus()).isEqualTo(JobStepStatus.RECOVERY_REQUIRED);assertThat(job().getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);
        assertThat(commands.findById(f.commandId).orElseThrow().getPhysicalStopConfirmed()).isTrue();hello("CANCELLED");
        recovery.reconcile(f.jobId,f.piStepId,"late-stop",body(MqttOperationRecoveryRequest.Evidence.PI_STOP_CONFIRMED),admin,null);assertThat(job().getStatus()).isEqualTo(JobStatus.CANCELLED);}
    @Test void terminalSelectedOperationCannotReleaseRobotWithOtherUnresolvedJournalEntries(){fixture(StepType.MOVE);dispatch(true);error("RECOVERY_REQUIRED",null,f.commandId);error("CANCELLED",true,f.commandId);
        ObjectNode hello=f.hello(json);var states=(ArrayNode)hello.path("payload").path("states");states.addObject().put("jobId",f.jobId.toString()).put("jobStepId",f.piStepId.toString()).put("operationKey",f.operationKey).put("status","CANCELLED");
        states.addObject().put("jobId",f.jobId.toString()).put("jobStepId",f.endStepId.toString()).put("operationKey",f.jobId+":END:1").put("status","RUNNING");receive(hello);heartbeat();
        assertThatThrownBy(()->recovery.reconcile(f.jobId,f.piStepId,"other-active",body(MqttOperationRecoveryRequest.Evidence.PI_STOP_CONFIRMED),admin,null)).hasMessageContaining("every operation");
        assertThat(job().getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(robots.findById(f.robotId).orElseThrow().getStatus()).isEqualTo(RobotStatus.ERROR);}
}
