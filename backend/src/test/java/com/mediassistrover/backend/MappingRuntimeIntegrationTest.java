package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.mapping.*;
import com.mediassistrover.backend.mqtt.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.*;
import com.mediassistrover.backend.service.*;
import com.mediassistrover.backend.dto.job.request.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MappingRuntimeIntegrationTest extends MySqlIntegrationTest {
    @Autowired ObjectMapper json;@Autowired MqttWire wire;@Autowired MqttProperties mqtt;@Autowired MappingProperties mapping;
    @Autowired MqttConnectionState connection;@Autowired MqttInboundRouter router;@Autowired MqttDurablePublisher publisher;
    @Autowired WorkflowOrchestratorService orchestrator;@Autowired OrchestrationWorkService wakeups;@Autowired WorkflowEventService workflowEvents;
    @Autowired MappingLifecycleService lifecycle;@Autowired MappingArtifactService artifacts;@Autowired MappingPreviewService previews;
    @Autowired JobRepository jobs;@Autowired JobStepRepository steps;@Autowired RobotRepository robots;@Autowired AccountRepository accounts;
    @Autowired MappingSessionRepository sessionRows;@Autowired MapArtifactRepository artifactRows;@Autowired FacilityMapRepository maps;
    @Autowired RobotCommandOutboxRepository commands;@Autowired RobotMqttInboxRepository inbox;
    @Autowired WorkflowRepository workflows;@Autowired WorkflowValidationService validation;
    @Autowired JobExecutionService execution;@Autowired JobService jobService;
    @Autowired MqttRolloutService rollout;
    @Autowired MappingRecoveryService recovery;@Autowired RobotMqttSessionRepository links;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;@Autowired DeviceJwtTokenService tokens;
    @Autowired PlatformTransactionManager tm;@Autowired jakarta.persistence.EntityManager em;
    @MockitoBean PahoMqttTransport transport;
    @TempDir Path directory;
    TransactionTemplate tx;MqttTestFixture fixture;UUID originalStart;ObjectNode manifest;byte[] archive;
    @BeforeEach void setup(){tx=new TransactionTemplate(tm);mqtt.setEnabled(true);mqtt.setDispatchEnabled(true);mqtt.setAllowSimulation(true);mqtt.getRobotIds().clear();
        mqtt.setEnabledStepTypes(new HashSet<>(Set.of("WAIT","EXECUTE_SENSING")));mapping.setEnabled(true);mapping.setStorageDir(directory.toString());connection.connectedNow();
        when(transport.connected()).thenReturn(true);when(transport.publish(anyString(),any(byte[].class),anyInt())).thenReturn(CompletableFuture.completedFuture(null));
    }
    @AfterEach void cleanup(){if(fixture!=null)tx.executeWithoutResult(s->{
        UUID newMap=artifactRows.findAll().stream().filter(a->sessionRows.findById(a.getSessionId()).map(r->r.getJobId().equals(fixture.jobId)).orElse(false)).map(MapArtifact::getMapId).filter(Objects::nonNull).findFirst().orElse(null);
        em.createNativeQuery("DELETE FROM MapArtifact WHERE session_id IN (SELECT session_id FROM MappingSession WHERE job_id=:id)").setParameter("id",fixture.jobId).executeUpdate();
        em.createNativeQuery("DELETE FROM MappingSession WHERE job_id=:id").setParameter("id",fixture.jobId).executeUpdate();
        if(newMap!=null)em.createNativeQuery("DELETE FROM Map WHERE map_id=:id").setParameter("id",newMap).executeUpdate();MqttTestFixture.cleanup(em,fixture);
        });mqtt.setEnabled(false);mqtt.setDispatchEnabled(false);mqtt.getRobotIds().clear();mapping.setEnabled(false);connection.disconnected();}
    void create() {
        fixture=tx.execute(s->{var f=MqttTestFixture.create(em,json,StepType.MOVE,null);f.type=StepType.EXECUTE_SENSING;f.sensing="MAPPING";
            Job job=jobs.findById(f.jobId).orElseThrow();job.getRobot().setDeviceSecretHash("test-only");job.setMap(null);job.setInputJson(json.createObjectNode().put("floor_id",f.floorId.toString()).put("map_name","Survey"));job.getWorkflow().setServiceType("MAPPING");
            JobStep first=steps.findById(f.piStepId).orElseThrow(),wait=steps.findById(f.endStepId).orElseThrow();
            Step start=first.getStep(),waitDefinition=wait.getStep();start.setStepType(StepType.EXECUTE_SENSING);start.setParamsJson(json.createObjectNode().put("sensing_type","MAPPING").put("action","START"));start.setInputMappingJson(refs("mapping_session_id","/vars/mapping_session_id"));
            first.setStepTypeSnapshot("EXECUTE_SENSING");waitDefinition.setStepType(StepType.WAIT);waitDefinition.setParamsJson(json.createObjectNode().put("wait_type","EVENT").put("event_type","MAPPING_SAVE_REQUESTED"));waitDefinition.setInputMappingJson(refs("correlation_key","/vars/mapping_session_id"));wait.setStepTypeSnapshot("WAIT");
            Step save=MqttTestFixture.definition(job.getWorkflow(),start.getTask(),StepType.EXECUTE_SENSING,"SAVE",3,json.createObjectNode().put("sensing_type","MAPPING").put("action","SAVE"),json);
            save.setInputMappingJson(refs("mapping_session_id","/vars/mapping_session_id","artifact_id","/vars/artifact_id"));save.setTimeoutSec(90);em.persist(save);
            Step commit=MqttTestFixture.definition(job.getWorkflow(),start.getTask(),StepType.DATA_SERVICE,"COMMIT",4,json.createObjectNode().put("service_key","COMMIT_MAP_ARTIFACT"),json);
            commit.setInputMappingJson(refs("mapping_session_id","/vars/mapping_session_id","artifact_id","/vars/artifact_id","floor_id","/inputs/floor_id","map_name","/inputs/map_name"));em.persist(commit);
            Step stop=MqttTestFixture.definition(job.getWorkflow(),start.getTask(),StepType.EXECUTE_SENSING,"STOP",5,json.createObjectNode().put("sensing_type","MAPPING").put("action","STOP"),json);stop.setInputMappingJson(refs("mapping_session_id","/vars/mapping_session_id"));stop.setTimeoutSec(20);em.persist(stop);
            for(Step def:List.of(save,commit,stop))em.persist(JobStep.builder().jobTask(first.getJobTask()).step(def).visitNo(1).executionSequenceNo(def.getSequenceNo()).stepCodeSnapshot(def.getStepCode()).stepTypeSnapshot(def.getStepType().name())
                    .resolvedInputsJson(json.createObjectNode()).status(JobStepStatus.PENDING).attemptNo(1).timeoutSecSnapshot(def.getTimeoutSec()).operationKey(f.jobId+":"+def.getStepCode()+":1").build());
            em.flush();em.createNativeQuery("DELETE FROM StepTransition WHERE workflow_id=:id").setParameter("id",f.workflowId).executeUpdate();
            var chain=List.of(start,waitDefinition,save,commit,stop);
            for(int i=0;i<chain.size();i++)for(TransitionOutcome outcome:List.of(TransitionOutcome.SUCCESS,TransitionOutcome.FAILURE,TransitionOutcome.TIMEOUT))em.persist(StepTransition.builder().workflow(job.getWorkflow()).fromStep(chain.get(i))
                    .toStep(outcome==TransitionOutcome.SUCCESS && i<4?chain.get(i+1):null).outcome(outcome).priority(1).defaultTransition(true)
                    .terminalStatus(outcome==TransitionOutcome.SUCCESS?(i==4?TerminalStatus.SUCCEEDED:null):i==4?TerminalStatus.RECOVERY_REQUIRED:TerminalStatus.FAILED).build());return f;});
        originalStart=fixture.piStepId;mqtt.getRobotIds().add(fixture.robotId);advance();
        ObjectNode hello=fixture.hello(json);((ObjectNode)hello.path("payload")).put("mappingEnabled",true).set("mappingSession",json.createObjectNode());receive(hello);heartbeat(false);advance();
        manifest=MappingTestArchive.manifest(json,session().getArtifactId(),MappingTestArchive.YAML,MappingTestArchive.PGM);archive=MappingTestArchive.archive(json,manifest);
    }
    ObjectNode refs(String... kv){var n=json.createObjectNode();for(int i=0;i<kv.length;i+=2)n.putObject(kv[i]).put("$ref",kv[i+1]);return n;}
    void advance(){tx.executeWithoutResult(s->wakeups.request(jobs.findById(fixture.jobId).orElseThrow()));orchestrator.advance(fixture.jobId);}
    void receive(ObjectNode event){router.route(MqttTopics.topic(fixture.robotId,"events"),wire.bytes(event),1,false);}
    void heartbeat(boolean open){var raw=fixture.heartbeat(json);var p=(ObjectNode)raw.path("payload");p.putNull("currentMapId");p.putNull("mapAssetHash");p.set("mappingSession",open?json.createObjectNode().put("session_id",session().getSessionId().toString()).put("job_id",fixture.jobId.toString()).put("session_open",true).put("state","MAPPING"):json.createObjectNode());router.route(MqttTopics.topic(fixture.robotId,"heartbeat"),wire.bytes(raw),0,false);}
    JobStep selected(){return tx.execute(s->{var step=jobs.findById(fixture.jobId).orElseThrow().getSelectedJobStep();org.hibernate.Hibernate.initialize(step);return step;});}
    MappingSession session(){return sessionRows.findByJobId(fixture.jobId).orElseThrow();}
    JobStatus status(){return jobs.findById(fixture.jobId).orElseThrow().getStatus();}
    AccountPrincipal actor(){return AccountPrincipal.fromAccount(accounts.findById(fixture.accountId).orElseThrow());}
    DevicePrincipal device(){var r=robots.findById(fixture.robotId).orElseThrow();return new DevicePrincipal(r.getRobotId(),r.getRobotCode(),r.getDeviceTokenVersion(),"mapping-test",Instant.now().plusSeconds(300));}
    RobotCommandOutbox issue(){advance();JobStep step=selected();fixture.piStepId=step.getJobStepId();fixture.operationKey=step.getOperationKey();var command=commands.findByJobStepId(step.getJobStepId()).orElseThrow();fixture.commandId=command.getCommandId();publisher.command(command.getCommandId());return command;}
    void startIssued(){receive(fixture.event(json,"COMMAND_ACK",json.createObjectNode().put("commandId",fixture.commandId.toString()).put("commandType","EXECUTE_STEP").put("accepted",true).put("status","ACCEPTED")));
        receive(fixture.event(json,"STEP_STARTED",json.createObjectNode().put("attemptNo",1).put("stepType","EXECUTE_SENSING").set("resolvedInputs",commands.findById(fixture.commandId).orElseThrow().getPayloadJson().path("step").path("params"))));}
    ObjectNode output(String action,boolean uploaded){var n=json.createObjectNode().put("sensing_type","MAPPING").put("mapping_session_id",session().getSessionId().toString()).put("captured_at",Instant.now().toString());n.putNull("evidence_ref");var m=n.putObject("metrics");
        if(action.equals("START"))m.put("state","MAPPING").put("preview_ready",true);else if(action.equals("STOP"))m.put("state","STOPPED").put("stopped",true);else {m.setAll(manifest);m.put("state",uploaded?"SAVED":"SAVED_LOCAL").put("saved_locally",true).put("uploaded",uploaded);}return n;}
    ObjectNode result(String action,boolean uploaded,boolean stopped){var p=json.createObjectNode().put("status","SUCCEEDED").put("retryable",false).put("physicalStopConfirmed",stopped);p.putNull("errorCode");p.putNull("details");p.set("result",output(action,uploaded));return fixture.event(json,"STEP_RESULT",p);}
    void complete(String action){issue();startIssued();receive(result(action,action.equals("SAVE"),true));}
    void waiting(){create();complete("START");advance();advance();assertThat(selected().getStatus()).isEqualTo(JobStepStatus.RUNNING);assertThat(selected().getStepTypeSnapshot()).isEqualTo("WAIT");heartbeat(true);}
    void saveRequested(){workflowEvents.publish(fixture.jobId,new WorkflowEventRequest(UUID.randomUUID(),"MAPPING_SAVE_REQUESTED",session().getSessionId().toString(),Instant.now(),json.createObjectNode()),actor(),new RequestMetadata("test","test","test"));advance();advance();}
    MappingArtifactService.Receipt upload(){return artifacts.upload(session().getSessionId(),session().getArtifactId(),manifest.path("asset_hash").asText(),MappingArchiveReader.hash(archive),new ByteArrayInputStream(archive),device());}

    @Test void fullMappingProducesOneDraftMapAndThreeDistinctPiOperations(){waiting();assertThat(session().isPotentiallyOpen()).isTrue();saveRequested();issue();startIssued();assertThat(upload().stored()).isTrue();receive(result("SAVE",true,true));advance();advance();advance();
        var artifact=artifactRows.findById(session().getArtifactId()).orElseThrow();assertThat(artifact.getMapId()).isNotNull();assertThat(maps.findById(artifact.getMapId()).orElseThrow().getStatus()).isEqualTo(MapStatus.DRAFT);
        complete("STOP");advance();assertThat(status()).isEqualTo(JobStatus.SUCCEEDED);assertThat(session().isPotentiallyOpen()).isFalse();
        var cmds=commands.findByJobIdOrderByCreatedAt(fixture.jobId);assertThat(cmds).hasSize(3);assertThat(cmds).extracting(RobotCommandOutbox::getOperationKey).doesNotHaveDuplicates();
        for(var c:cmds){assertThat(c.getPayloadJson().has("jobSteps")).isFalse();assertThat(c.getPayloadJson().path("step").path("params").path("mapping_session_id").asText()).isEqualTo(session().getSessionId().toString());}}
    @Test void autonomyApiThenExistingSaveUploadCommitStopAndPngIsOneMappingJob() throws Exception {
        waiting();long before=commands.findByJobIdOrderByCreatedAt(fixture.jobId).size();
        var raw=fixture.heartbeat(json);var p=(ObjectNode)raw.path("payload");p.putNull("currentMapId").putNull("mapAssetHash");
        var report=json.createObjectNode().put("session_id",session().getSessionId().toString()).put("job_id",fixture.jobId.toString())
                .put("state","MAPPING").put("session_open",true);
        report.set("autonomy",json.createObjectNode().put("available",true).put("state","IDLE").put("vehicle","articulated")
                .put("coverage_complete",false).put("control_selected",false));p.set("mappingSession",report);
        router.route(MqttTopics.topic(fixture.robotId,"heartbeat"),wire.bytes(raw),0,false);
        var options=json.createObjectNode().put("action","START").put("runId",UUID.randomUUID().toString()).put("maxDurationSec",180).put("stopOnReturn",false);
        var request=json.createObjectNode().put("mappingSessionId",session().getSessionId().toString())
                .put("controlSessionId",UUID.randomUUID().toString()).put("controlSequence",1).put("requestedAt",Instant.now().toString());request.set("autonomy",options);
        var user=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(actor());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/jobs/"+fixture.jobId+"/mapping-autonomy")
                .with(user).contentType("application/json").content(json.writeValueAsBytes(request)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/jobs/"+fixture.jobId+"/mapping-session").with(user))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.autonomy.available").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.autonomy.vehicle").value("articulated"));
        assertThat(commands.findByJobIdOrderByCreatedAt(fixture.jobId)).hasSize((int)before);
        assertThat(selected().getStepTypeSnapshot()).isEqualTo("WAIT");
        var completed=fixture.heartbeat(json);var completedPayload=(ObjectNode)completed.path("payload");
        completedPayload.putNull("currentMapId").putNull("mapAssetHash");
        var completedReport=report.deepCopy();
        var covered=json.createObjectNode().put("available",true).put("state","STOPPED").put("finished",true)
                .put("reason","COVERAGE_COMPLETE").put("coverage_complete",true).put("exploration_mode","frontier");
        covered.set("exploration",json.createObjectNode().put("enabled",true).put("state","COMPLETE").put("frontier_count",0));
        covered.set("manual_control",json.createObjectNode().put("source","gamepad").put("layout","vendor_mecanum")
                .put("session_required",false).put("forward_mps",.25).put("angular_rps",.8).put("continuous",true));
        completedReport.set("autonomy",covered);completedPayload.set("mappingSession",completedReport);
        router.route(MqttTopics.topic(fixture.robotId,"heartbeat"),wire.bytes(completed),0,false);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/jobs/"+fixture.jobId+"/mapping-session").with(user))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.autonomy.coverage_complete").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.autonomy.manual_control.layout").value("vendor_mecanum"));
        saveRequested();issue();startIssued();upload();receive(result("SAVE",true,true));advance();advance();advance();
        UUID map=artifactRows.findById(session().getArtifactId()).orElseThrow().getMapId();
        complete("STOP");advance();assertThat(status()).isEqualTo(JobStatus.SUCCEEDED);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/maps/"+map+"/image.png").with(user))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("image/png"));
    }
    @Test void uploadRetryReturnsSameReceiptAndChangedContentConflicts(){waiting();saveRequested();issue();startIssued();var first=upload();assertThat(upload()).isEqualTo(first);manifest.put("frame_id","other");archive=MappingTestArchive.archive(json,manifest);assertThatThrownBy(this::upload).hasMessageContaining("different content");assertThat(artifactRows.findById(session().getArtifactId())).isPresent();}
    @Test void wrongHashScopeOrArtifactCannotStore(){waiting();saveRequested();issue();startIssued();assertThatThrownBy(()->artifacts.upload(session().getSessionId(),session().getArtifactId(),manifest.path("asset_hash").asText(),"b".repeat(64),new ByteArrayInputStream(archive),device())).hasMessageContaining("Archive SHA");
        assertThatThrownBy(()->artifacts.upload(session().getSessionId(),UUID.randomUUID(),manifest.path("asset_hash").asText(),MappingArchiveReader.hash(archive),new ByteArrayInputStream(archive),device())).hasMessageContaining("not issued");
        var d=device();assertThatThrownBy(()->artifacts.upload(session().getSessionId(),session().getArtifactId(),manifest.path("asset_hash").asText(),MappingArchiveReader.hash(archive),new ByteArrayInputStream(archive),new DevicePrincipal(UUID.randomUUID(),d.robotCode(),d.tokenVersion(),d.jti(),d.expiresAt()))).hasMessageContaining("Current device");assertThat(artifactRows.findById(session().getArtifactId())).isEmpty();}
    @Test void savedLocallyIsNotACommittedMap(){waiting();saveRequested();issue();startIssued();receive(result("SAVE",false,true));advance();assertThat(selected().getStepTypeSnapshot()).isEqualTo("DATA_SERVICE");assertThatThrownBy(this::advance).hasMessageContaining("durable upload receipt");assertThat(artifactRows.findById(session().getArtifactId())).isEmpty();}
    @Test void closedLocalSaveHeartbeatBeforeErrorStillRunsStopCleanup() {
        waiting();saveRequested();issue();startIssued();
        var raw=fixture.heartbeat(json);var p=(ObjectNode)raw.path("payload");
        p.putNull("currentMapId");p.putNull("mapAssetHash");
        p.set("mappingSession",json.createObjectNode().put("session_id",session().getSessionId().toString())
                .put("job_id",fixture.jobId.toString()).put("session_open",false).put("state","SAVED_LOCAL"));
        router.route(MqttTopics.topic(fixture.robotId,"heartbeat"),wire.bytes(raw),0,false);
        assertThat(status()).isEqualTo(JobStatus.RUNNING);assertThat(session().isPotentiallyOpen()).isTrue();
        receive(fixture.event(json,"STEP_ERROR",json.createObjectNode().put("status","FAILED")
                .put("errorCode","MAP_UPLOAD_UNAUTHORIZED").put("retryable",false).put("physicalStopConfirmed",true).putNull("details")));
        advance();assertThat(selected().isMappingCleanup()).isTrue();
        complete("STOP");advance();
        assertThat(status()).isEqualTo(JobStatus.FAILED);assertThat(session().isPotentiallyOpen()).isFalse();
    }
    @Test void foreignClosedSaveHeartbeatStillRequiresRecovery() {
        waiting();saveRequested();issue();startIssued();
        var raw=fixture.heartbeat(json);var p=(ObjectNode)raw.path("payload");p.putNull("currentMapId");p.putNull("mapAssetHash");
        p.set("mappingSession",json.createObjectNode().put("session_id",UUID.randomUUID().toString())
                .put("job_id",fixture.jobId.toString()).put("session_open",false).put("state","SAVED_LOCAL"));
        router.route(MqttTopics.topic(fixture.robotId,"heartbeat"),wire.bytes(raw),0,false);
        assertThat(status()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(session().isPotentiallyOpen()).isTrue();
    }
    void failedSaveAwaitingCleanup() {
        waiting();saveRequested();issue();startIssued();
        receive(fixture.event(json,"STEP_ERROR",json.createObjectNode().put("status","FAILED")
                .put("errorCode","MAP_UPLOAD_AUTH_REFRESH_FAILED").put("retryable",false).put("physicalStopConfirmed",true).putNull("details")));
        advance();assertThat(selected().isMappingCleanup()).isTrue();
    }
    ObjectNode closedMappingReport() {
        return json.createObjectNode().put("session_id",session().getSessionId().toString())
                .put("job_id",fixture.jobId.toString()).put("artifact_id",session().getArtifactId().toString())
                .put("session_open",false).put("state","SAVED_LOCAL");
    }
    @Test void closedHeartbeatAfterSaveErrorBeforeStopPublishStillAllowsCleanup() {
        failedSaveAwaitingCleanup();var raw=fixture.heartbeat(json);var p=(ObjectNode)raw.path("payload");
        p.putNull("currentMapId");p.putNull("mapAssetHash");p.set("mappingSession",closedMappingReport());
        router.route(MqttTopics.topic(fixture.robotId,"heartbeat"),wire.bytes(raw),0,false);
        assertThat(status()).isEqualTo(JobStatus.RUNNING);assertThat(session().isPotentiallyOpen()).isTrue();
        complete("STOP");advance();assertThat(status()).isEqualTo(JobStatus.FAILED);assertThat(session().isPotentiallyOpen()).isFalse();
    }
    @Test void foreignArtifactAfterSaveErrorStillHoldsRecovery() {
        failedSaveAwaitingCleanup();var raw=fixture.heartbeat(json);var p=(ObjectNode)raw.path("payload");
        p.putNull("currentMapId");p.putNull("mapAssetHash");p.set("mappingSession",closedMappingReport().put("artifact_id",UUID.randomUUID().toString()));
        router.route(MqttTopics.topic(fixture.robotId,"heartbeat"),wire.bytes(raw),0,false);
        assertThat(status()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(session().isPotentiallyOpen()).isTrue();
    }
    void recoverableUnsentStop() {
        failedSaveAwaitingCleanup();var report=closedMappingReport();
        tx.executeWithoutResult(s->{
            jobs.findById(fixture.jobId).orElseThrow().setStatus(JobStatus.RECOVERY_REQUIRED);
            robots.findById(fixture.robotId).orElseThrow().setStatus(RobotStatus.ERROR);
            var link=links.findById(fixture.robotId).orElseThrow();link.setClosed(false);link.setHandshakeAcked(true);link.setDispatchReady(false);
            link.setHelloReceivedAt(Instant.now().minusSeconds(1));link.setLastHeartbeatAt(Instant.now());
            ObjectNode hello=((ObjectNode)link.getHelloJson()).deepCopy();hello.set("mappingSession",report.deepCopy());
            var journal=hello.putArray("states");
            for(var c:commands.findByJobIdOrderByCreatedAt(fixture.jobId))if(c.getSendAttempts()>0) {
                var step=steps.findById(c.getJobStepId()).orElseThrow();
                journal.addObject().put("jobId",fixture.jobId.toString()).put("jobStepId",c.getJobStepId().toString())
                        .put("operationKey",c.getOperationKey()).put("status",step.getStatus().name());
            }
            link.setHelloJson(hello);
            ObjectNode hb=((ObjectNode)fixture.heartbeat(json).path("payload")).deepCopy();hb.putNull("currentMapId").putNull("mapAssetHash");
            hb.set("mappingSession",report.deepCopy());((ObjectNode)hb.path("telemetry")).putObject("odom_speed").put("value",0).put("age_sec",.01);
            link.setHeartbeatJson(hb);
        });
    }
    MappingRecoveryRequest recoveryRequest() {
        return new MappingRecoveryRequest(selected().getJobStepId(),fixture.transportId,true,JobRequests.ReconcileOutcome.CONFIRM_CANCELLED,"Close failed local SAVE after verified stop");
    }
    JsonNode recover(String key,MappingRecoveryRequest request) {
        return recovery.reconcile(fixture.jobId,key,request,actor(),new RequestMetadata("test","test","test"));
    }
    @Test void unsentStopApiClosesOncePreservesActualResultsAndRequiresNewHello() throws Exception {
        recoverableUnsentStop();var request=recoveryRequest();String key=UUID.randomUUID().toString();
        var user=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(actor());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/jobs/"+fixture.jobId+"/mapping-recovery").with(user))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.canReconcile").value(true));
        var first=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/jobs/"+fixture.jobId+"/mapping-recovery")
                .with(user).header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsBytes(request)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.stopWasExecuted").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.requiresNewHello").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(recover(key,request)).isEqualTo(json.readTree(first));
        assertThat(status()).isEqualTo(JobStatus.CANCELLED);assertThat(session().getState()).isEqualTo("OPERATOR_RECONCILED");
        assertThat(session().isPotentiallyOpen()).isFalse();assertThat(links.findById(fixture.robotId).orElseThrow().isClosed()).isTrue();
        assertThat(robots.findById(fixture.robotId).orElseThrow().getStatus()).isEqualTo(RobotStatus.STARTING);
        assertThat(steps.findById(fixture.piStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.FAILED);
        for(var c:commands.findByJobIdOrderByCreatedAt(fixture.jobId)) {
            if(c.getJobStepId().equals(request.jobStepId())) {assertThat(c.getState()).isEqualTo("ABANDONED");assertThat(c.getSendAttempts()).isZero();assertThat(c.getPhysicalStopConfirmed()).isNull();}
            else {assertThat(c.getState()).isEqualTo("COMPLETED");assertThat(c.getPhysicalStopConfirmed()).isTrue();}
        }
        long receipts=tx.execute(s->((Number)em.createNativeQuery("SELECT COUNT(*) FROM MqttOperationRecoveryReceipt WHERE job_step_id=:id").setParameter("id",request.jobStepId()).getSingleResult()).longValue());
        assertThat(receipts).isEqualTo(1);
        assertThatThrownBy(()->recover(key,new MappingRecoveryRequest(request.jobStepId(),request.transportSessionId(),true,request.outcome(),"Different note")))
                .isInstanceOf(com.mediassistrover.backend.exception.ConflictException.class);
    }
    @Test void unsentStopRecoveryRejectsStaleHeartbeatAndMovingRobot() {
        recoverableUnsentStop();var request=recoveryRequest();
        tx.executeWithoutResult(s->links.findById(fixture.robotId).orElseThrow().setLastHeartbeatAt(Instant.now().minusSeconds(20)));
        assertThatThrownBy(()->recover(UUID.randomUUID().toString(),request)).hasMessageContaining("FRESH_PI_HELLO");
        tx.executeWithoutResult(s->{var link=links.findById(fixture.robotId).orElseThrow();link.setLastHeartbeatAt(Instant.now());
            ((ObjectNode)link.getHeartbeatJson().path("telemetry").path("odom_speed")).put("value",.03);});
        assertThatThrownBy(()->recover(UUID.randomUUID().toString(),request)).hasMessageContaining("STATIONARY_ODOMETRY");
        assertThat(status()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(session().isPotentiallyOpen()).isTrue();
    }
    @Test void unsentStopRecoveryRejectsMissingAcceptedStopReceipt() {
        recoverableUnsentStop();var request=recoveryRequest();
        tx.executeWithoutResult(s->{var c=commands.findById(fixture.commandId).orElseThrow();inbox.findById(UUID.fromString(c.getResultEnvelopeJson().path("messageId").asText())).orElseThrow().setAccepted(false);});
        assertThatThrownBy(()->recover(UUID.randomUUID().toString(),request)).hasMessageContaining("STOP_RECEIPT_AND_JOURNAL");
        assertThat(status()).isEqualTo(JobStatus.RECOVERY_REQUIRED);
    }
    @Test void unsentStopRecoveryRejectsChangedTransportAndArtifact() {
        recoverableUnsentStop();var request=recoveryRequest();
        assertThatThrownBy(()->recover(UUID.randomUUID().toString(),new MappingRecoveryRequest(request.jobStepId(),UUID.randomUUID(),true,request.outcome(),request.note())))
                .hasMessageContaining("Transport session changed");
        tx.executeWithoutResult(s->{var link=links.findById(fixture.robotId).orElseThrow();((ObjectNode)link.getHeartbeatJson().path("mappingSession")).put("artifact_id",UUID.randomUUID().toString());});
        assertThatThrownBy(()->recover(UUID.randomUUID().toString(),request)).hasMessageContaining("MATCHING_CLOSED_PI_MAPPING");
    }
    @Test void unsentStopRecoveryRejectsAnyPossiblyPublishedCleanup() {
        recoverableUnsentStop();var request=recoveryRequest();
        tx.executeWithoutResult(s->{var step=steps.findById(request.jobStepId()).orElseThrow();
            var c=commands.findByJobStepId(request.jobStepId()).orElseGet(()->commands.save(RobotCommandOutbox.builder()
                    .commandId(UUID.randomUUID()).robotId(fixture.robotId).jobId(fixture.jobId).jobStepId(request.jobStepId())
                    .operationKey(step.getOperationKey()).commandType("EXECUTE_STEP").state("PENDING").payloadJson(json.createObjectNode()).expiresAt(Instant.now().plusSeconds(60)).createdAt(Instant.now()).build()));
            c.setSendAttempts(1);c.setState("PUBLISHED");c.setLastPublishAt(Instant.now());});
        assertThatThrownBy(()->recover(UUID.randomUUID().toString(),request)).hasMessageContaining("STOP_MAY_HAVE_BEEN_SENT");
        assertThat(status()).isEqualTo(JobStatus.RECOVERY_REQUIRED);
    }
    @Test void unsentZeroAttemptOutboxIsAbandonedWithoutInventingStopReceipt() {
        recoverableUnsentStop();var request=recoveryRequest();
        tx.executeWithoutResult(s->{var step=steps.findById(request.jobStepId()).orElseThrow();
            if(commands.findByJobStepId(request.jobStepId()).isEmpty())commands.save(RobotCommandOutbox.builder()
                    .commandId(UUID.randomUUID()).robotId(fixture.robotId).jobId(fixture.jobId).jobStepId(request.jobStepId())
                    .operationKey(step.getOperationKey()).commandType("EXECUTE_STEP").state("PENDING").payloadJson(json.createObjectNode())
                    .expiresAt(Instant.now().plusSeconds(60)).createdAt(Instant.now()).build());});
        assertThat(recover(UUID.randomUUID().toString(),request).path("status").asText()).isEqualTo("CANCELLED");
        var command=commands.findByJobStepId(request.jobStepId()).orElseThrow();
        assertThat(command.getState()).isEqualTo("ABANDONED");assertThat(command.getSendAttempts()).isZero();
        assertThat(command.getPhysicalStopConfirmed()).isNull();assertThat(command.getResultEnvelopeJson()).isNull();
    }
    @Test void unsentStopRecoveryApiRequiresAuthenticationAndExplicitStopConfirmation() throws Exception {
        recoverableUnsentStop();var request=recoveryRequest();String path="/api/v1/jobs/"+fixture.jobId+"/mapping-recovery";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(actor()))
                .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                .content(json.writeValueAsBytes(new MappingRecoveryRequest(request.jobStepId(),request.transportSessionId(),false,request.outcome(),request.note()))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        assertThat(status()).isEqualTo(JobStatus.RECOVERY_REQUIRED);
    }
    @Test void claimedUploadWithoutReceiptEntersRecovery(){waiting();saveRequested();issue();startIssued();receive(result("SAVE",true,true));assertThat(status()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(session().isPotentiallyOpen()).isTrue();}
    @Test void durableRolloutHaltStillDispatchesCleanupStopForAnOpenMappingSession(){waiting();var before=rollout.state();
        try{rollout.change(new MqttRolloutService.Change(before.mode(),true,before.revision(),"Halt for mapping acceptance"),actor());
            jobService.cancel(fixture.jobId,new JobRequests.CancelJobRequest("Stop during rollout halt"),actor(),new RequestMetadata(null,null,null));advance();
            assertThat(selected().isMappingCleanup()).isTrue();complete("STOP");advance();assertThat(status()).isEqualTo(JobStatus.CANCELLED);assertThat(session().isPotentiallyOpen()).isFalse();
            assertThat(commands.findByJobIdOrderByCreatedAt(fixture.jobId)).hasSize(2);
        }finally{var now=rollout.state();rollout.change(new MqttRolloutService.Change(now.mode(),before.dispatchHalted(),now.revision(),"Restore halt after test"),actor());}}
    @Test void cancelDuringUiWaitCreatesStopAndHoldsRobotUntilResult(){waiting();jobService.cancel(fixture.jobId,new JobRequests.CancelJobRequest("Stop mapping"),actor(),new RequestMetadata("test","test","test"));advance();assertThat(selected().isMappingCleanup()).isTrue();assertThat(status()).isEqualTo(JobStatus.RUNNING);assertThat(robots.findById(fixture.robotId).orElseThrow().getStatus()).isEqualTo(RobotStatus.BUSY);
        mqtt.setDispatchEnabled(false);complete("STOP");advance();assertThat(status()).isEqualTo(JobStatus.CANCELLED);assertThat(session().isPotentiallyOpen()).isFalse();assertThat(robots.findById(fixture.robotId).orElseThrow().getStatus()).isEqualTo(RobotStatus.IDLE);assertThat(commands.findByJobIdOrderByCreatedAt(fixture.jobId)).hasSize(2);}
    @Test void failedCleanupStopHoldsRecoveryWithoutIssuingAnotherStop(){waiting();tx.executeWithoutResult(s->jobs.findById(fixture.jobId).orElseThrow().setCancelRequestedAt(Instant.now()));advance();issue();startIssued();receive(fixture.event(json,"STEP_ERROR",json.createObjectNode().put("status","FAILED").put("errorCode","MAPPING_STOP_FAILED").put("retryable",false).put("physicalStopConfirmed",true).putNull("details")));advance();advance();assertThat(status()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(session().isPotentiallyOpen()).isTrue();assertThat(commands.findByJobIdOrderByCreatedAt(fixture.jobId)).hasSize(2);}
    @Test void disconnectWhileWaitingStillHoldsTheOpenSession(){waiting();router.route(MqttTopics.topic(fixture.robotId,"presence"),wire.bytes(json.createObjectNode().put("robotId",fixture.robotId.toString()).put("transportSessionId",fixture.transportId.toString()).put("online",false)),1,false);assertThat(status()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(session().isPotentiallyOpen()).isTrue();}
    @Test void mappingResultsNeedPhysicalStopAndMatchingManifest(){create();issue();startIssued();receive(result("START",false,false));assertThat(status()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(robots.findById(fixture.robotId).orElseThrow().getStatus()).isEqualTo(RobotStatus.ERROR);}
    @Test void foreignSessionHeartbeatHoldsRecoveryAndEmptyMappingObjectIsIdle(){create();assertThat(status()).isEqualTo(JobStatus.RUNNING);issue();startIssued();receive(result("START",false,true));var hb=fixture.heartbeat(json);var p=(ObjectNode)hb.path("payload");p.putNull("currentMapId");p.putNull("mapAssetHash");p.set("mappingSession",json.createObjectNode().put("session_id",UUID.randomUUID().toString()).put("job_id",fixture.jobId.toString()).put("session_open",true).put("state","MAPPING"));router.route(MqttTopics.topic(fixture.robotId,"heartbeat"),wire.bytes(hb),0,false);assertThat(status()).isEqualTo(JobStatus.RECOVERY_REQUIRED);}
    @Test void wrongUiCorrelationDoesNotResumeWait(){waiting();assertThatThrownBy(()->workflowEvents.publish(fixture.jobId,new WorkflowEventRequest(UUID.randomUUID(),"MAPPING_SAVE_REQUESTED",UUID.randomUUID().toString(),Instant.now(),json.createObjectNode()),actor(),new RequestMetadata(null,null,null))).hasMessageContaining("correlation");assertThat(selected().getStatus()).isEqualTo(JobStepStatus.RUNNING);}
    @Test void lostEstablishedMappingSessionRequiresRecovery(){waiting();heartbeat(false);assertThat(status()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(session().isPotentiallyOpen()).isTrue();}
    @Test void freshSlamPoseIsAcceptedWithoutNavigationMapOrLocationHistory(){waiting();
        var hb=fixture.heartbeat(json);var p=(ObjectNode)hb.path("payload");p.putNull("currentMapId");p.putNull("mapAssetHash");
        p.set("mappingSession",json.createObjectNode().put("session_id",session().getSessionId().toString()).put("job_id",fixture.jobId.toString()).put("session_open",true).put("state","MAPPING"));
        var pose=((ObjectNode)p.path("telemetry")).putObject("map_pose").put("fresh",true).put("age_sec",.05);
        pose.putObject("value").putNull("map_id").put("mapping_session_id",session().getSessionId().toString()).put("x_m",.2).put("y_m",.3).put("yaw_rad",0);
        router.route(MqttTopics.topic(fixture.robotId,"heartbeat"),wire.bytes(hb),0,false);
        assertThat(status()).isEqualTo(JobStatus.RUNNING);
        long count=tx.execute(s->((Number)em.createNativeQuery("SELECT COUNT(*) FROM RobotLocationHistory WHERE robot_id=:id").setParameter("id",fixture.robotId).getSingleResult()).longValue());
        assertThat(count).isZero();
        ((ObjectNode)pose.path("value")).put("mapping_session_id",UUID.randomUUID().toString());
        assertThatThrownBy(()->router.route(MqttTopics.topic(fixture.robotId,"heartbeat"),wire.bytes(hb),0,false)).hasMessageContaining("SLAM map_pose");
    }
    @Test void closedAndStationaryPiAllowsReconcilingMappingWaitWithoutInventingOperation(){waiting();heartbeat(false);
        assertThat(status()).isEqualTo(JobStatus.RECOVERY_REQUIRED);
        assertThatThrownBy(()->jobService.reconcile(fixture.jobId,new JobRequests.ReconcileJobRequest("Close interrupted survey",JobRequests.ReconcileOutcome.CONFIRM_CANCELLED),actor(),new RequestMetadata(null,null,null))).hasMessageContaining("fresh stationary odometry");
        var hello=fixture.hello(json);((ObjectNode)hello.path("payload")).put("mappingEnabled",true).set("mappingSession",json.createObjectNode());receive(hello);
        var hb=fixture.heartbeat(json);var p=(ObjectNode)hb.path("payload");p.putNull("currentMapId");p.putNull("mapAssetHash");p.set("mappingSession",json.createObjectNode());
        ((ObjectNode)p.path("telemetry")).putObject("odom_speed").put("value",0).put("age_sec",.1);
        router.route(MqttTopics.topic(fixture.robotId,"heartbeat"),wire.bytes(hb),0,false);
        jobService.reconcile(fixture.jobId,new JobRequests.ReconcileJobRequest("Closed Pi survey after protocol fix",JobRequests.ReconcileOutcome.CONFIRM_CANCELLED),actor(),new RequestMetadata(null,null,null));
        assertThat(status()).isEqualTo(JobStatus.CANCELLED);assertThat(session().isPotentiallyOpen()).isFalse();
        assertThat(selected().getStatus()).isEqualTo(JobStepStatus.CANCELLED);
    }
    @Test void visitLimitReservesTheUnvisitedStopWithoutExceedingMaxVisits(){waiting();tx.executeWithoutResult(s->em.find(Workflow.class,fixture.workflowId).setMaxTotalVisits(3));saveRequested();
        assertThat(selected().isMappingCleanup()).isTrue();assertThat(selected().getVisitNo()).isEqualTo(1);complete("STOP");advance();assertThat(status()).isEqualTo(JobStatus.FAILED);
        assertThat(steps.findByJobId(fixture.jobId).stream().filter(s->s.getSelectedAt()!=null).count()).isEqualTo(3);}
    @Test void publishedMappingFactoryCreatesFiveVisitsWithNoOldMap(){create();jobService.cancel(fixture.jobId,new JobRequests.CancelJobRequest("Cancel before start"),actor(),new RequestMetadata(null,null,null));advance();
        tx.executeWithoutResult(s->{s.setRollbackOnly();var w=workflows.findAll().stream().filter(x->x.getWorkflowCode().equals("WF_MAPPING")).findFirst().orElseThrow();w.setStatus(WorkflowStatus.PUBLISHED);w.setAcceptsNewJobs(true);
            var job=execution.createForMapping(w.getWorkflowId(),fixture.robotId,fixture.floorId,"Factory map");assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);assertThat(job.isBeOrchestrated()).isTrue();assertThat(job.getMap()).isNull();assertThat(steps.findByJobId(job.getJobId())).hasSize(5);assertThat(job.getInputJson().path("floor_id").asText()).isEqualTo(fixture.floorId.toString());});}
    @Test void newPiProcessCannotAdoptAnOpenSession(){waiting();fixture.producerId=UUID.randomUUID();fixture.transportId=UUID.randomUUID();var hello=fixture.hello(json);((ObjectNode)hello.path("payload")).put("mappingEnabled",true).set("mappingSession",json.createObjectNode().put("session_id",session().getSessionId().toString()).put("job_id",fixture.jobId.toString()).put("state","MAPPING").put("session_open",true));receive(hello);assertThat(status()).isEqualTo(JobStatus.RECOVERY_REQUIRED);}
    @Test void seedIsCompiledDraftAndMappingCreationChecksPublishedWorkflow(){create();var w=workflows.findAll().stream().filter(x->x.getWorkflowCode().equals("WF_MAPPING")).findFirst().orElseThrow();assertThat(w.getStatus()).isEqualTo(WorkflowStatus.DRAFT);assertThat(w.isAcceptsNewJobs()).isFalse();assertThat(w.getDefinitionHash()).hasSize(64);assertThatThrownBy(()->execution.createForMapping(w.getWorkflowId(),fixture.robotId,fixture.floorId,"New Map")).hasMessageContaining("published schema 3");}
    @Test void duplicateResultDoesNotCloseOrAdvanceTwice(){create();issue();startIssued();var event=result("START",false,true);receive(event);long before=inbox.count();receive(event);assertThat(inbox.count()).isEqualTo(before);assertThat(session().getState()).isEqualTo("MAPPING");assertThat(session().isPotentiallyOpen()).isTrue();}
    @Test void cancelActiveSaveFirstCancelsTheOperationThenStopsTheSession(){waiting();saveRequested();issue();startIssued();UUID saveId=fixture.piStepId;
        jobService.cancel(fixture.jobId,new JobRequests.CancelJobRequest("Cancel save"),actor(),new RequestMetadata(null,null,null));advance();
        assertThat(commands.findByJobStepIdAndCommandType(saveId,"CANCEL_STEP")).isPresent();assertThat(selected().isMappingCleanup()).isFalse();
        receive(fixture.event(json,"STEP_ERROR",json.createObjectNode().put("status","CANCELLED").put("errorCode","CANCELLED").put("retryable",false).put("physicalStopConfirmed",true).putNull("details")));
        assertThat(selected().isMappingCleanup()).isTrue();complete("STOP");advance();assertThat(status()).isEqualTo(JobStatus.CANCELLED);assertThat(session().isPotentiallyOpen()).isFalse();assertThat(commands.findByJobIdOrderByCreatedAt(fixture.jobId)).hasSize(4);}
    @Test void workflowExpiryRequiresStop(){waiting();tx.executeWithoutResult(s->jobs.findById(fixture.jobId).orElseThrow().setStartedAt(Instant.now().minusSeconds(601)));advance();
        assertThat(selected().isMappingCleanup()).isTrue();complete("STOP");advance();assertThat(status()).isEqualTo(JobStatus.FAILED);assertThat(session().isPotentiallyOpen()).isFalse();}
    @Test void uiWaitTimeoutRequiresStop(){waiting();tx.executeWithoutResult(s->steps.findById(fixture.endStepId).orElseThrow().setStartedAt(Instant.now().minusSeconds(61)));advance();
        assertThat(selected().isMappingCleanup()).isTrue();complete("STOP");advance();assertThat(status()).isEqualTo(JobStatus.FAILED);assertThat(session().isPotentiallyOpen()).isFalse();}
    @Test void cancelBeforePublishingStartDoesNotCreateUnnecessaryStop(){create();assertThat(session().isPotentiallyOpen()).isFalse();jobService.cancel(fixture.jobId,new JobRequests.CancelJobRequest("Cancel before start"),actor(),new RequestMetadata(null,null,null));advance();assertThat(status()).isEqualTo(JobStatus.CANCELLED);assertThat(commands.findByJobIdOrderByCreatedAt(fixture.jobId)).hasSize(1);assertThat(session().isPotentiallyOpen()).isFalse();}
    @Test void completedSaveMustMatchStoredManifest(){waiting();saveRequested();issue();startIssued();upload();manifest.put("frame_id","different");receive(result("SAVE",true,true));assertThat(status()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(session().isPotentiallyOpen()).isTrue();assertThat(artifactRows.findById(session().getArtifactId()).orElseThrow().getMapId()).isNull();}
    @Test void repeatedCommitCreatesExactlyOneDraftMap(){waiting();saveRequested();issue();startIssued();upload();receive(result("SAVE",true,true));advance();advance();
        ObjectNode again=tx.execute(s->{Job job=jobs.lockById(fixture.jobId).orElseThrow();return artifacts.commit(job.getSelectedJobStep(),job.getSelectedJobStep().getResolvedInputsJson());});
        assertThat(again.path("created").asBoolean()).isFalse();assertThat(again.path("map_id").asText()).isEqualTo(artifactRows.findById(session().getArtifactId()).orElseThrow().getMapId().toString());}
    @Test void previewIsTransientScopedAndRevisionChecked() throws Exception {waiting();long rows=inbox.count();var bytes=new ByteArrayOutputStream();try(var zlib=new java.util.zip.DeflaterOutputStream(bytes)){zlib.write(new byte[]{-1,0,50,100});}
        var p=json.createObjectNode().put("mapping_session_id",session().getSessionId().toString()).put("revision",1).put("width",2).put("height",2).put("resolution",.05).put("frame_id","map")
                .put("captured_at",Instant.now().toString()).put("encoding","zlib-base64-int8-row-major-bottom-up").put("data",Base64.getEncoder().encodeToString(bytes.toByteArray()));p.putArray("origin").add(0).add(0).add(0);p.putObject("pose").putNull("value").putNull("age_sec").put("fresh",false);
        var raw=json.createObjectNode().put("robotId",fixture.robotId.toString()).put("transportSessionId",fixture.transportId.toString()).put("messageType","MAP_PREVIEW").set("payload",p);
        router.route(MqttTopics.topic(fixture.robotId,"map-preview"),wire.bytes(raw),0,false);assertThat(previews.latest(session().getSessionId())).isEqualTo(p);assertThat(inbox.count()).isEqualTo(rows);
        p.put("revision",0).put("captured_at",Instant.now().toString());router.route(MqttTopics.topic(fixture.robotId,"map-preview"),wire.bytes(raw),0,false);assertThat(previews.latest(session().getSessionId()).path("revision").asInt()).isEqualTo(1);
        p.put("mapping_session_id",UUID.randomUUID().toString()).put("revision",2);router.route(MqttTopics.topic(fixture.robotId,"map-preview"),wire.bytes(raw),0,false);assertThat(previews.latest(session().getSessionId()).path("revision").asInt()).isEqualTo(1);
        tx.executeWithoutResult(s->jobs.findById(fixture.jobId).orElseThrow().setCancelRequestedAt(Instant.now()));advance();complete("STOP");advance();assertThat(previews.latest(session().getSessionId())).isNull();}
    @Test void uploadHttpUsesDeviceBearerAndDoesNotRedirect() throws Exception {waiting();saveRequested();issue();startIssued();String path="/api/v1/pi/mapping-sessions/"+session().getSessionId()+"/artifacts/"+session().getArtifactId();
        String token=tx.execute(s->tokens.issue(em.find(Robot.class,fixture.robotId)).value());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).header("Authorization","Bearer "+token).header("X-Asset-SHA256",manifest.path("asset_hash").asText()).header("X-Archive-SHA256",MappingArchiveReader.hash(archive)).contentType("application/gzip").content(archive))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().doesNotExist("Location"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.stored").value(true)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.artifactId").value(session().getArtifactId().toString()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).contentType("application/gzip").content(archive)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());}
}
