package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.mqtt.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.*;
import com.mediassistrover.backend.service.*;
import com.mediassistrover.backend.dto.job.request.JobRequests.CancelJobRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MqttRolloutIntegrationTest extends MySqlIntegrationTest {
    @Autowired MqttRolloutService rollout;@Autowired MqttOperationsStatusService operations;
    @Autowired MqttProperties mqtt;@Autowired MqttConnectionState connection;@Autowired MqttInboundRouter router;
    @Autowired MqttWire wire;@Autowired MqttDurablePublisher publisher;@Autowired WorkflowOrchestratorService orchestrator;
    @Autowired JobExecutionService execution;@Autowired JobService jobService;@Autowired OrchestrationWorkService wakeups;
    @Autowired PiJobRuntimeService legacy;@Autowired PiStepService piSteps;@Autowired PiIntegrationService integration;
    @Autowired WorkflowService workflowService;@Autowired JobRepository jobs;@Autowired JobStepRepository steps;
    @Autowired RobotCommandOutboxRepository commands;@Autowired ObjectMapper json;@Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager tm;@Autowired jakarta.persistence.EntityManager em;
    @MockitoBean PahoMqttTransport transport;
    TransactionTemplate tx;MqttTestFixture f;
    UUID extraWorkflow,requestId,scheduleId,createdJob;
    @BeforeEach void setup() {
        tx=new TransactionTemplate(tm);jdbc.update("DELETE FROM MqttRolloutAudit");
        jdbc.update("UPDATE MqttRolloutControl SET mode='LEGACY_COMPATIBLE',dispatch_halted=FALSE,revision=0,updated_by=NULL,note='Test reset' WHERE control_id=1");
        mqtt.setEnabled(true);mqtt.setDispatchEnabled(true);mqtt.setAllowSimulation(true);mqtt.setEnabledStepTypes(new HashSet<>(Set.of("WAIT")));mqtt.getRobotIds().clear();
        connection.connectedNow();when(transport.connected()).thenReturn(true);when(transport.publish(anyString(),any(byte[].class),anyInt())).thenReturn(CompletableFuture.completedFuture(null));
        f=tx.execute(s->MqttTestFixture.create(em,json,StepType.MOVE,null));
        tx.executeWithoutResult(s->{var job=em.find(Job.class,f.jobId);var step=em.find(JobStep.class,f.piStepId);var definition=step.getStep();
            definition.setStepType(StepType.WAIT);definition.setParamsJson(json.createObjectNode().put("wait_type","DURATION").put("duration_sec",2));step.setStepTypeSnapshot("WAIT");
            job.setMap(null);job.getRobot().setCurrentMap(null);job.getRobot().setMapAssetHash(null);});
        f.type=StepType.WAIT;
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM MqttRolloutAudit");jdbc.update("UPDATE MqttRolloutControl SET mode='LEGACY_COMPATIBLE',dispatch_halted=FALSE,revision=0,updated_by=NULL,note='Test reset' WHERE control_id=1");
        tx.executeWithoutResult(s->{
            if(createdJob!=null)for(String sql:List.of("DELETE FROM JobEvent WHERE job_id=:id","DELETE FROM WorkflowOrchestrationWork WHERE job_id=:id","DELETE FROM JobStep WHERE job_task_id IN (SELECT job_task_id FROM JobTask WHERE job_id=:id)","DELETE FROM JobTask WHERE job_id=:id","DELETE FROM Job WHERE job_id=:id"))em.createNativeQuery(sql).setParameter("id",createdJob).executeUpdate();
            if(scheduleId!=null)em.find(Job.class,f.jobId).setSchedule(null);
            em.flush();
            if(scheduleId!=null)em.createNativeQuery("DELETE FROM Schedule WHERE schedule_id=:id").setParameter("id",scheduleId).executeUpdate();
            if(requestId!=null)em.createNativeQuery("DELETE FROM ServiceRequest WHERE service_request_id=:id").setParameter("id",requestId).executeUpdate();
            if(extraWorkflow!=null)em.createNativeQuery("DELETE FROM Workflow WHERE workflow_id=:id").setParameter("id",extraWorkflow).executeUpdate();
            MqttTestFixture.cleanup(em,f);
        });mqtt.setEnabled(false);mqtt.setDispatchEnabled(false);mqtt.getRobotIds().clear();connection.disconnected();
    }
    AccountPrincipal admin(){return tx.execute(s->AccountPrincipal.fromAccount(em.find(Account.class,f.accountId)));}
    DevicePrincipal device(){return new DevicePrincipal(f.robotId,"test",0,"test",Instant.now().plusSeconds(3600));}
    MqttRolloutService.State change(MqttRolloutService.Mode mode,boolean halted){return rollout.change(new MqttRolloutService.Change(mode,halted,rollout.state().revision(),"Integration acceptance"),admin());}
    void advance(){tx.executeWithoutResult(s->wakeups.request(em.find(Job.class,f.jobId)));orchestrator.advance(f.jobId);}
    void ready(){mqtt.getRobotIds().add(f.robotId);var map=f.mapId;f.mapId=null;router.route(MqttTopics.topic(f.robotId,"events"),wire.bytes(f.hello(json)),1,false);router.route(MqttTopics.topic(f.robotId,"heartbeat"),wire.bytes(f.heartbeat(json)),0,false);f.mapId=map;}
    void legacyJob(JobStatus status){tx.executeWithoutResult(s->{var j=em.find(Job.class,f.jobId);j.setBeOrchestrated(false);j.setSchemaVersion(2);j.setStatus(status);j.setDispatchCommandId(UUID.randomUUID());j.setDispatchExpiresAt(Instant.now().plusSeconds(60));});}
    UUID legacyWorkflow(){return tx.execute(s->{var w=Workflow.builder().workflowCode("LEGACY-"+UUID.randomUUID()).workflowName("Legacy").serviceType("INFORMATION").versionNo(99).schemaVersion(2).graphVersion(3).maxDurationSec(600).maxTotalVisits(10).status(WorkflowStatus.PUBLISHED).acceptsNewJobs(true).definitionHash("b".repeat(64)).compiledDefinitionJson(json.createObjectNode()).inputSchemaJson(json.createObjectNode()).inputUiJson(json.createObjectNode()).defaultInputsJson(json.createObjectNode()).outputMappingJson(json.createObjectNode()).requiredCapabilitiesJson(json.createArrayNode()).build();em.persist(w);extraWorkflow=w.getWorkflowId();return extraWorkflow;});}
    UUID request(){return tx.execute(s->{var r=ServiceRequest.builder().requestedByAccount(em.find(Account.class,f.accountId)).requestCode("CUTOVER-"+UUID.randomUUID()).requestType(ServiceRequestType.INFORMATION).sourceType(RequestSourceType.STAFF_UI).priority(1).status(ServiceRequestStatus.QUEUED).requestDataJson(json.createObjectNode().put("query","hours")).idempotencyKey(UUID.randomUUID().toString()).requestedAt(Instant.now()).build();em.persist(r);requestId=r.getServiceRequestId();return requestId;});}
    @Test void drainingAllowsOldJobsButBlocksTheirCreationAndPublishing(){legacyWorkflow();legacyJob(JobStatus.QUEUED);change(MqttRolloutService.Mode.DRAINING,false);
        assertThat(legacy.nextCommand(device())).isPresent();assertThat(rollout.legacyBlockers()).isGreaterThanOrEqualTo(1);
        assertThatThrownBy(()->workflowService.publish(extraWorkflow,f.accountId,new RequestMetadata(null,null,null))).isInstanceOf(BusinessRuleException.class).hasMessageContaining("schema 3");
        request();assertThatThrownBy(()->tx.execute(s->execution.createForRequest(em.find(ServiceRequest.class,requestId)))).isInstanceOf(BusinessRuleException.class).hasMessageContaining("No published workflow");
        assertThat(jobs.findByServiceRequestServiceRequestId(requestId)).isEmpty();
        assertThatThrownBy(()->change(MqttRolloutService.Mode.MQTT_ONLY,false)).isInstanceOf(BusinessRuleException.class).hasMessageContaining("legacy Jobs");
    }
    @Test void drainingSelectsSchema3EvenWhenLegacyVersionIsHigher(){legacyWorkflow();request();change(MqttRolloutService.Mode.DRAINING,false);
        tx.executeWithoutResult(s->{var old=em.find(Job.class,f.jobId);old.setStatus(JobStatus.CANCELLED);var w=old.getWorkflow();w.setServiceType("INFORMATION");w.setStatus(WorkflowStatus.PUBLISHED);w.setAcceptsNewJobs(true);});mqtt.getRobotIds().add(f.robotId);
        createdJob=tx.execute(s->execution.createForRequest(em.find(ServiceRequest.class,requestId)).getJobId());
        var j=jobs.findById(createdJob).orElseThrow();assertThat(j.isBeOrchestrated()).isTrue();assertThat(j.getSchemaVersion()).isEqualTo(3);assertThat(j.getDispatchCommandId()).isNull();assertThat(steps.findByJobId(createdJob)).hasSize(2);
    }
    @Test void explicitLegacyScheduleIsBlockedWithoutChangingItsTransport(){legacyWorkflow();change(MqttRolloutService.Mode.DRAINING,false);
        assertThatThrownBy(()->tx.execute(s->execution.createForSchedule(Schedule.builder().scheduleId(UUID.randomUUID()).workflow(em.find(Workflow.class,extraWorkflow)).build(),Instant.now(),json.createObjectNode()))).isInstanceOf(BusinessRuleException.class).hasMessageContaining("schema 3");
        assertThat(jobs.findById(f.jobId).orElseThrow().getSchemaVersion()).isEqualTo(3);
    }
    @Test void anAlreadyCreatedLegacyScheduleRetryReturnsItsOriginalJobDuringDrain(){legacyWorkflow();legacyJob(JobStatus.QUEUED);
        Instant fire=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        tx.executeWithoutResult(s->{var schedule=Schedule.builder().createdByAccount(em.find(Account.class,f.accountId)).workflow(em.find(Workflow.class,extraWorkflow)).robot(em.find(Robot.class,f.robotId)).scheduleCode("ROLLOUT-"+UUID.randomUUID()).scheduleName("Legacy retry").scheduleType(ScheduleType.ONCE).timezone("Asia/Saigon").startsAt(fire).inputTemplateJson(json.createObjectNode()).priority(1).misfirePolicy(MisfirePolicy.FIRE_ONCE).status(ScheduleStatus.ACTIVE).build();em.persist(schedule);em.flush();scheduleId=schedule.getScheduleId();var job=em.find(Job.class,f.jobId);job.setSchedule(schedule);job.setScheduleFireAt(fire);job.setScheduleItemKey("DEFAULT");});
        change(MqttRolloutService.Mode.DRAINING,false);
        var retried=tx.execute(s->execution.createForSchedule(em.find(Schedule.class,scheduleId),fire,json.createObjectNode()));
        assertThat(retried.getJobId()).isEqualTo(f.jobId);assertThat(retried.getSchemaVersion()).isEqualTo(2);assertThat(retried.isBeOrchestrated()).isFalse();
        // The fixture owns this Job; unlink its schedule before deleting the schedule in cleanup.
        tx.executeWithoutResult(s->em.find(Job.class,f.jobId).setSchedule(null));
    }
    @Test void missingControlFailsClosedAndCannotCreateOrDispatchWork(){jdbc.update("DELETE FROM MqttRolloutControl WHERE control_id=1");
        try{assertThatThrownBy(()->legacy.nextCommand(device())).isInstanceOf(BusinessRuleException.class).hasMessageContaining("control is missing");
            assertThatThrownBy(()->tx.execute(s->rollout.lockForRuntimeCreation())).isInstanceOf(BusinessRuleException.class).hasMessageContaining("control is missing");
            assertThatThrownBy(rollout::dispatchAllowed).isInstanceOf(BusinessRuleException.class).hasMessageContaining("control is missing");
        }finally{jdbc.update("INSERT INTO MqttRolloutControl VALUES (1,'LEGACY_COMPATIBLE',FALSE,0,NULL,UTC_TIMESTAMP(6),'Test reset')");}}
    @Test void cutoverCannotSkipDrainOrReverseAfterRetirement(){mqtt.getRobotIds().add(f.robotId);
        assertThatThrownBy(()->change(MqttRolloutService.Mode.MQTT_ONLY,false)).hasMessageContaining("DRAINING");change(MqttRolloutService.Mode.DRAINING,false);change(MqttRolloutService.Mode.MQTT_ONLY,true);
        assertThatThrownBy(()->change(MqttRolloutService.Mode.LEGACY_COMPATIBLE,false)).hasMessageContaining("cannot be reversed");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM MqttRolloutAudit",Integer.class)).isEqualTo(2);
    }
    @Test void staleRevisionAndNonAdminCannotMutateControl(){var state=change(MqttRolloutService.Mode.DRAINING,false);
        assertThatThrownBy(()->rollout.change(new MqttRolloutService.Change(state.mode(),true,0L,"Stale"),admin())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->rollout.change(new MqttRolloutService.Change(state.mode(),true,state.revision(),"Not admin"),new AccountPrincipal(f.accountId,"staff","",Role.STAFF,AccountStatus.ACTIVE,0,null,null,null))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(rollout.state().revision()).isEqualTo(1);
    }
    @Test void recoveryLegacyJobCannotBeIgnoredForCutover(){legacyJob(JobStatus.RECOVERY_REQUIRED);change(MqttRolloutService.Mode.DRAINING,false);mqtt.getRobotIds().add(f.robotId);
        assertThatThrownBy(()->change(MqttRolloutService.Mode.MQTT_ONLY,false)).hasMessageContaining("legacy Jobs");
    }
    @Test void disabledMqttCannotReopenRetiredExecution(){mqtt.getRobotIds().add(f.robotId);change(MqttRolloutService.Mode.DRAINING,false);change(MqttRolloutService.Mode.MQTT_ONLY,false);mqtt.setEnabled(false);mqtt.getRobotIds().clear();
        assertThatThrownBy(()->legacy.nextCommand(device())).isInstanceOf(LegacyExecutionRetiredException.class);
        assertThatThrownBy(()->legacy.commandAck(UUID.randomUUID(),null,device())).isInstanceOf(LegacyExecutionRetiredException.class);
        assertThatThrownBy(()->legacy.jobStarted(f.jobId,null,device())).isInstanceOf(LegacyExecutionRetiredException.class);
        assertThatThrownBy(()->legacy.jobResult(f.jobId,null,device())).isInstanceOf(LegacyExecutionRetiredException.class);
        assertThatThrownBy(()->legacy.jobError(f.jobId,null,device())).isInstanceOf(LegacyExecutionRetiredException.class);
        assertThatThrownBy(()->legacy.stepStarted(f.piStepId,null,device())).isInstanceOf(LegacyExecutionRetiredException.class);
        assertThatThrownBy(()->legacy.stepResult(f.piStepId,null,device())).isInstanceOf(LegacyExecutionRetiredException.class);
        assertThatThrownBy(()->legacy.stepError(f.piStepId,null,device())).isInstanceOf(LegacyExecutionRetiredException.class);
        assertThatThrownBy(()->legacy.createStepVisit(f.jobId,UUID.randomUUID(),null,device())).isInstanceOf(LegacyExecutionRetiredException.class);
        assertThatThrownBy(()->piSteps.execute(null,device())).isInstanceOf(LegacyExecutionRetiredException.class);
        assertThatThrownBy(()->piSteps.result(f.piStepId,device())).isInstanceOf(LegacyExecutionRetiredException.class);
        assertThatThrownBy(()->integration.heartbeat(null,device())).isInstanceOf(LegacyExecutionRetiredException.class);
    }
    @Test void retiredRestReturns410AndCatalogRemainsAvailable()throws Exception {mqtt.getRobotIds().add(f.robotId);change(MqttRolloutService.Mode.DRAINING,false);change(MqttRolloutService.Mode.MQTT_ONLY,false);
        var auth=new UsernamePasswordAuthenticationToken(device(),null,List.of(new SimpleGrantedAuthority("ROLE_DEVICE")));
        mvc.perform(get("/api/v1/pi/commands/next").with(authentication(auth))).andExpect(status().isGone()).andExpect(jsonPath("$.error").value("LEGACY_EXECUTION_RETIRED"));
        mvc.perform(get("/api/v1/pi/catalog/manifest").with(authentication(auth))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/mqtt-rollout").with(authentication(auth))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/mqtt-rollout").with(user(admin()))).andExpect(status().isOk()).andExpect(jsonPath("$.rollout.mode").value("MQTT_ONLY"));
    }
    @Test void adminPutRequiresExplicitHaltAndRevisionAndReturnsCommittedState()throws Exception {
        mvc.perform(put("/api/v1/admin/mqtt-rollout").with(user(admin())).contentType("application/json")
                .content("{\"mode\":\"DRAINING\",\"expectedRevision\":0,\"note\":\"Missing halt\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/admin/mqtt-rollout").with(user(admin())).contentType("application/json")
                .content("{\"mode\":\"DRAINING\",\"dispatchHalted\":true,\"note\":\"Missing revision\"}")).andExpect(status().isBadRequest());
        assertThat(rollout.state().revision()).isZero();
        mvc.perform(put("/api/v1/admin/mqtt-rollout").with(user(admin())).contentType("application/json")
                .content("{\"mode\":\"DRAINING\",\"dispatchHalted\":true,\"expectedRevision\":0,\"note\":\"Acceptance\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("DRAINING")).andExpect(jsonPath("$.dispatchHalted").value(true)).andExpect(jsonPath("$.revision").value(1));
    }
    @Test void haltBlocksCreationAndPublicationOfExecuteButPreservesSnapshot(){ready();change(MqttRolloutService.Mode.DRAINING,true);advance();assertThat(commands.findByJobIdOrderByCreatedAt(f.jobId)).isEmpty();
        change(MqttRolloutService.Mode.DRAINING,false);advance();var command=commands.findByJobStepId(f.piStepId).orElseThrow();var snapshot=command.getPayloadJson().deepCopy();
        assertThat(snapshot.has("workflowDefinition")).isFalse();assertThat(snapshot.has("context")).isFalse();assertThat(snapshot.has("jobSteps")).isFalse();assertThat(snapshot.path("step").path("params").has("$ref")).isFalse();
        change(MqttRolloutService.Mode.DRAINING,true);clearInvocations(transport);publisher.command(command.getCommandId());verify(transport,never()).publish(eq(MqttTopics.topic(f.robotId,"commands")),any(byte[].class),anyInt());
        assertThat(commands.findById(command.getCommandId()).orElseThrow().getSendAttempts()).isZero();assertThat(commands.findById(command.getCommandId()).orElseThrow().getPayloadJson()).isEqualTo(snapshot);
        change(MqttRolloutService.Mode.DRAINING,false);publisher.command(command.getCommandId());assertThat(commands.findById(command.getCommandId()).orElseThrow().getSendAttempts()).isEqualTo(1);
    }
    @Test void cancelCanPublishWhileDispatchIsHalted(){ready();advance();advance();var execute=commands.findByJobStepId(f.piStepId).orElseThrow();f.commandId=execute.getCommandId();publisher.command(f.commandId);
        router.route(MqttTopics.topic(f.robotId,"events"),wire.bytes(f.event(json,"COMMAND_ACK",json.createObjectNode().put("commandId",f.commandId.toString()).put("commandType","EXECUTE_STEP").put("accepted",true).put("status","ACCEPTED"))),1,false);
        router.route(MqttTopics.topic(f.robotId,"events"),wire.bytes(f.event(json,"STEP_STARTED",json.createObjectNode().put("stepType","WAIT").put("attemptNo",1).set("resolvedInputs",execute.getPayloadJson().path("step").path("params")))),1,false);
        change(MqttRolloutService.Mode.DRAINING,true);jobService.cancel(f.jobId,new CancelJobRequest("Rollout stopped"),admin(),new RequestMetadata(null,null,null));advance();
        var cancel=commands.findByJobStepIdAndCommandType(f.piStepId,"CANCEL_STEP").orElseThrow();publisher.command(cancel.getCommandId());assertThat(commands.findById(cancel.getCommandId()).orElseThrow().getSendAttempts()).isEqualTo(1);assertThat(jobs.findById(f.jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
    }
    @Test void statusReportsQueueAgesAndMissingSessionWithoutLeakingPayloads(){mqtt.getRobotIds().add(f.robotId);var report=operations.status();assertThat(report.robots()).hasSize(1);assertThat(report.robots().getFirst().dispatchReady()).isFalse();assertThat(report.robots().getFirst().failureReason()).isEqualTo("HELLO required");
        assertThat(report.workflows()).isNotEmpty();assertThat(json.valueToTree(report).has("password")).isFalse();assertThat(report.rollout().mode()).isEqualTo(MqttRolloutService.Mode.LEGACY_COMPATIBLE);
        ready();advance();advance();report=operations.status();assertThat(report.outbox().count()).isGreaterThanOrEqualTo(1);assertThat(report.unpublished().oldestAgeSeconds()).isGreaterThanOrEqualTo(0);assertThat(report.robots().getFirst().dispatchReady()).isTrue();
        change(MqttRolloutService.Mode.DRAINING,true);assertThat(operations.status().dispatchEffective()).isFalse();
    }
    @Test void sharedCreationFenceMakesDrainWaitForAlreadyStartedTransaction()throws Exception {
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {var creator=pool.submit(()->tx.execute(s->{rollout.lockForRuntimeCreation();held.countDown();try{assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}return null;}));
            assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();var actor=admin();var transition=pool.submit(()->rollout.change(new MqttRolloutService.Change(MqttRolloutService.Mode.DRAINING,false,0L,"Concurrent drain"),actor));
            assertThatThrownBy(()->transition.get(250,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);release.countDown();creator.get(10,TimeUnit.SECONDS);assertThat(transition.get(10,TimeUnit.SECONDS).mode()).isEqualTo(MqttRolloutService.Mode.DRAINING);
        }finally{release.countDown();pool.shutdownNow();}
    }
}
