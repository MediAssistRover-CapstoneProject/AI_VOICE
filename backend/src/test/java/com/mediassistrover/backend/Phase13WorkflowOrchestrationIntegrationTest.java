package com.mediassistrover.backend;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.mqtt.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.*;
import com.mediassistrover.backend.service.*;
import com.mediassistrover.backend.dto.job.request.WorkflowEventRequest;
import com.mediassistrover.backend.dto.emergency.request.EmergencyRequests.Resolution;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class Phase13WorkflowOrchestrationIntegrationTest extends MySqlIntegrationTest {
    @Autowired ObjectMapper json;@Autowired MqttWire wire;@Autowired MqttProperties mqtt;@Autowired MqttConnectionState connection;
    @Autowired MqttInboundRouter router;@Autowired MqttDurablePublisher publisher;@Autowired WorkflowOrchestratorService orchestrator;
    @Autowired JobExecutionService execution;@Autowired OrchestrationWorkService work;@Autowired JobStepResponseService responses;
    @Autowired WorkflowEventService workflowEvents;@Autowired EmergencyService emergency;
    @Autowired JobRepository jobs;@Autowired JobStepRepository steps;@Autowired WorkflowRepository workflows;@Autowired AccountRepository accounts;
    @Autowired ServiceRequestRepository requests;@Autowired RobotRepository robots;@Autowired RobotCommandOutboxRepository commands;
    @Autowired com.mediassistrover.backend.validation.WorkflowExecutionReadiness readiness;@Autowired com.mediassistrover.backend.ai.AiProperties aiProperties;
    @Autowired WorkflowValidationService validation;
    @Autowired DetectionEventRepository detections;@Autowired EmergencyIncidentRepository incidents;@Autowired NavigationProfileService navigationProfiles;
    @Autowired PlatformTransactionManager tm;@Autowired jakarta.persistence.EntityManager em;
    @MockitoBean PahoMqttTransport transport;
    TransactionTemplate tx;MqttTestFixture f;UUID originalJobId,requestId,seedId,staffId;
    @BeforeEach void setup(){tx=new TransactionTemplate(tm);mqtt.setEnabled(true);mqtt.setDispatchEnabled(true);mqtt.setAllowSimulation(true);mqtt.getRobotIds().clear();mqtt.setEnabledStepTypes(new HashSet<>(Set.of("EXECUTE_SENSING","WAIT","MOVE")));connection.connectedNow();when(transport.connected()).thenReturn(true);when(transport.publish(anyString(),any(byte[].class),anyInt())).thenReturn(CompletableFuture.completedFuture(null));}
    @AfterEach void cleanup(){if(f!=null)tx.executeWithoutResult(s->{
        for(String sql:List.of("DELETE FROM JobStepResponse WHERE job_step_id IN (SELECT t.job_step_id FROM JobStep t JOIN JobTask k ON k.job_task_id=t.job_task_id WHERE k.job_id=:id)",
                "DELETE FROM AlertRecipient WHERE alert_id IN (SELECT alert_id FROM Alert WHERE service_request_id=:id)","DELETE FROM Alert WHERE service_request_id=:id"))em.createNativeQuery(sql).setParameter("id",sql.startsWith("DELETE FROM JobStepResponse")?f.jobId:requestId).executeUpdate();
        em.createNativeQuery("UPDATE Job SET service_request_id=NULL,recovery_of_job_id=NULL WHERE job_id=:id").setParameter("id",f.jobId).executeUpdate();
        if(requestId!=null){em.createNativeQuery("DELETE FROM EscortRequest WHERE service_request_id=:id").setParameter("id",requestId).executeUpdate();em.createNativeQuery("DELETE FROM ServiceRequest WHERE service_request_id=:id").setParameter("id",requestId).executeUpdate();}
        em.createNativeQuery("DELETE FROM EmergencyIncident WHERE robot_id=:id").setParameter("id",f.robotId).executeUpdate();
        em.createNativeQuery("DELETE FROM DetectionEvent WHERE robot_id=:id").setParameter("id",f.robotId).executeUpdate();
        em.createNativeQuery("DELETE FROM AuditLog WHERE account_id=:id").setParameter("id",f.accountId).executeUpdate();
        if(originalJobId!=null)for(String sql:List.of("DELETE FROM JobEvent WHERE job_id=:id","DELETE FROM WorkflowOrchestrationWork WHERE job_id=:id","DELETE FROM JobStep WHERE job_task_id IN (SELECT job_task_id FROM JobTask WHERE job_id=:id)","DELETE FROM JobTask WHERE job_id=:id","DELETE FROM Job WHERE job_id=:id"))em.createNativeQuery(sql).setParameter("id",originalJobId).executeUpdate();
        if(seedId!=null){var w=em.find(Workflow.class,seedId);w.setStatus(WorkflowStatus.DRAFT);w.setAcceptsNewJobs(false);em.flush();}
        if(staffId!=null)em.createNativeQuery("DELETE FROM Account WHERE account_id=:id").setParameter("id",staffId).executeUpdate();
        MqttTestFixture.cleanup(em,f);
    });mqtt.setEnabled(false);mqtt.setDispatchEnabled(false);mqtt.getRobotIds().clear();connection.disconnected();}
    void resources(){f=tx.execute(s->MqttTestFixture.create(em,json,StepType.MOVE,null));mqtt.getRobotIds().add(f.robotId);originalJobId=f.jobId;tx.executeWithoutResult(s->{em.find(Job.class,originalJobId).setStatus(JobStatus.CANCELLED);
        var staff=accounts.saveAndFlush(Account.builder().accountCode("P13-"+UUID.randomUUID()).username("p13."+UUID.randomUUID()).fullName("Staff recipient").role(Role.STAFF).status(AccountStatus.ACTIVE).authProvider(AuthProvider.LOCAL).passwordHash("unused").build());staffId=staff.getAccountId();});}
    void start(String code,ServiceRequestType type,ObjectNode data) {
        resources();
        f.jobId=tx.execute(s->{var seed=workflows.findAll().stream().filter(w->w.getSchemaVersion()==3 && w.getWorkflowCode().equals(code)).max(Comparator.comparingInt(Workflow::getVersionNo)).orElseThrow();seedId=seed.getWorkflowId();var compilation=validation.validateAndCompile(seed);seed.setDefinitionHash(compilation.definitionHash());seed.setCompiledDefinitionJson(compilation.compiledDefinition());seed.setStatus(WorkflowStatus.PUBLISHED);seed.setAcceptsNewJobs(true);
            if(type==ServiceRequestType.FALL_DETECTION_RESPONSE){var detection=detections.saveAndFlush(DetectionEvent.builder().detectionEventId(UUID.randomUUID()).robot(em.find(Robot.class,f.robotId)).map(em.find(FacilityMap.class,f.mapId)).eventType(DetectionEventType.SUSPECTED_FALL).confidenceScore(new BigDecimal("0.9")).locationBasis(DetectionLocationBasis.UNKNOWN).modelVersion("fall-producer").eventPayloadJson(json.createObjectNode()).detectedAt(Instant.now()).receivedAt(Instant.now()).build());data.put("detection_event_id",detection.getDetectionEventId().toString()).put("confidence",.9);}
            if(type==ServiceRequestType.NAVIGATION_RECOVERY){em.find(Job.class,originalJobId).setStatus(JobStatus.FAILED);data.put("map_id",f.mapId.toString()).put("destination_endpoint_id",f.endpointId.toString()).put("recovery_of_job_id",originalJobId.toString());}
            data.put("robot_id",f.robotId.toString());var request=requests.saveAndFlush(ServiceRequest.builder().requestedByAccount(em.find(Account.class,f.accountId)).requestedRobot(em.find(Robot.class,f.robotId))
                    .requestCode("P13-"+UUID.randomUUID()).requestType(type).sourceType(RequestSourceType.STAFF_UI).priority(50).status(ServiceRequestStatus.QUEUED).requestDataJson(data).idempotencyKey(UUID.randomUUID().toString()).requestedAt(Instant.now()).build());requestId=request.getServiceRequestId();
            if(type==ServiceRequestType.ESCORT)em.persist(EscortRequest.builder().serviceRequest(request).map(em.find(FacilityMap.class,f.mapId)).destinationEndpoint(em.find(Endpoint.class,f.endpointId))
                    .targetMode(TargetMode.ENDPOINT).patientReference("patient-test").escortMode(EscortMode.GUIDED).walkingSpeedMps(new BigDecimal("0.05")).confirmationTimeoutSec(60).build());
            return execution.createForRequest(request).getJobId();});
        f.type=StepType.EXECUTE_SENSING;f.sensing="ROBOT_HEALTH";advance();receive(f.hello(json));router.route(MqttTopics.topic(f.robotId,"heartbeat"),wire.bytes(f.heartbeat(json)),0,false);advance();
    }
    void advance(){tx.executeWithoutResult(s->work.request(em.find(Job.class,f.jobId)));orchestrator.advance(f.jobId);}
    void drain(int n){for(int i=0;i<n;i++)advance();}
    void receive(ObjectNode event){router.route(MqttTopics.topic(f.robotId,"events"),wire.bytes(event),1,false);}
    JobStep selected(){return tx.execute(s->{var step=em.find(Job.class,f.jobId).getSelectedJobStep();org.hibernate.Hibernate.initialize(step);return step;});}
    AccountPrincipal actor(){return AccountPrincipal.fromAccount(accounts.findById(f.accountId).orElseThrow());}
    void callback(boolean confirmed){var step=selected();responses.respond(step.getJobStepId(),UUID.randomUUID().toString(),json.createObjectNode().put("responseType","INTERACTION_CONFIRMED").set("data",json.createObjectNode().put("confirmed",confirmed)),actor());}
    void executeHealth(boolean ready){JobStep step=selected();f.piStepId=step.getJobStepId();f.operationKey=step.getOperationKey();var command=commands.findByJobStepId(f.piStepId).orElseThrow();f.commandId=command.getCommandId();publisher.command(f.commandId);
        receive(f.event(json,"COMMAND_ACK",json.createObjectNode().put("commandId",f.commandId.toString()).put("commandType","EXECUTE_STEP").put("accepted",true).put("status","ACCEPTED")));
        receive(f.event(json,"STEP_STARTED",json.createObjectNode().put("stepType","EXECUTE_SENSING").put("attemptNo",1).set("resolvedInputs",command.getPayloadJson().path("step").path("params"))));
        var output=f.output(json);output.put("captured_at",Instant.now().toString());((ObjectNode)output.path("metrics")).put("sensors_ready",ready);
        receive(f.event(json,"STEP_RESULT",json.createObjectNode().put("status","SUCCEEDED").put("retryable",false).putNull("details").putNull("errorCode").put("physicalStopConfirmed",true).set("result",output)));}
    JobStatus status(){return jobs.findById(f.jobId).orElseThrow().getStatus();}

    @Test void startupHealthReadSuccessWithSensorsNotReadyStillHoldsRecovery(){start("WF_ROBOT_STARTUP_STANDBY",ServiceRequestType.ROBOT_STARTUP_STANDBY,json.createObjectNode());executeHealth(false);drain(6);assertThat(steps.findById(f.piStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.SUCCEEDED);assertThat(status()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(robots.findById(f.robotId).orElseThrow().getStatus()).isEqualTo(RobotStatus.ERROR);}
    @Test void startupReadyFollowsConditionAndNotificationToSuccess(){start("WF_ROBOT_STARTUP_STANDBY",ServiceRequestType.ROBOT_STARTUP_STANDBY,json.createObjectNode());executeHealth(true);drain(8);assertThat(status()).isEqualTo(JobStatus.SUCCEEDED);assertThat(jobs.findById(f.jobId).orElseThrow().getResultJson().path("ready").asBoolean()).isTrue();assertThat(commands.findByJobIdOrderByCreatedAt(f.jobId)).hasSize(1);}
    @Test void chargingWaitsForStaffEventAndReportsOnlyManualCompletion(){UUID correlation=UUID.randomUUID();start("WF_AUTO_CHARGING",ServiceRequestType.AUTO_CHARGING,json.createObjectNode().put("charge_request_id",correlation.toString()));drain(4);assertThat(selected().getStepCodeSnapshot()).isEqualTo("WAIT_STAFF");assertThat(commands.findByJobIdOrderByCreatedAt(f.jobId)).isEmpty();
        workflowEvents.publish(f.jobId,new WorkflowEventRequest(UUID.randomUUID(),"STAFF_CHARGE_COMPLETED",correlation.toString(),Instant.now(),json.createObjectNode().put("source","STAFF_MANUAL")),actor(),new RequestMetadata(null,null,null));drain(2);executeHealth(false);drain(7);assertThat(status()).isEqualTo(JobStatus.SUCCEEDED);assertThat(jobs.findById(f.jobId).orElseThrow().getResultJson().path("automatic_charging").asBoolean()).isFalse();}
    @Test void fallReviewMustExplicitlyConfirmBeforeCreatingAnIncident(){start("WF_FALL_DETECTION_RESPONSE",ServiceRequestType.FALL_DETECTION_RESPONSE,json.createObjectNode());drain(5);assertThat(selected().getStepCodeSnapshot()).isEqualTo("STAFF_REVIEW");assertThat(selected().getResolvedInputsJson().path("action").asText()).isEqualTo("CONFIRM");assertThat(incidents.count()).isZero();callback(true);drain(7);assertThat(incidents.findAll()).hasSize(1);assertThat(incidents.findAll().getFirst().getIncidentType()).isEqualTo("SUSPECTED_FALL");}
    @Test void fallReviewFalseDoesNotCreateIncident(){start("WF_FALL_DETECTION_RESPONSE",ServiceRequestType.FALL_DETECTION_RESPONSE,json.createObjectNode());drain(5);callback(false);drain(8);assertThat(incidents.count()).isZero();assertThat(status()).isEqualTo(JobStatus.SUCCEEDED);}
    @Test void falseAlarmRequestsCancellationOfAnActiveBePiAction(){start("WF_ROBOT_STARTUP_STANDBY",ServiceRequestType.ROBOT_STARTUP_STANDBY,json.createObjectNode());JobStep step=selected();var command=commands.findByJobStepId(step.getJobStepId()).orElseThrow();publisher.command(command.getCommandId());
        UUID incident=tx.execute(s->{var i=incidents.saveAndFlush(EmergencyIncident.builder().robot(em.find(Robot.class,f.robotId)).map(em.find(FacilityMap.class,f.mapId)).incidentType("SOS").severity(EmergencyIncidentSeverity.CRITICAL).status(EmergencyIncidentStatus.OPEN).description("Test false alarm").occurredAt(Instant.now()).build());em.find(ServiceRequest.class,requestId).setRelatedIncidentId(i.getIncidentId());return i.getIncidentId();});
        emergency.falseAlarm(incident,new Resolution("Staff confirmed false alarm"),actor(),new RequestMetadata(null,null,null));assertThat(status()).isEqualTo(JobStatus.RUNNING);advance();assertThat(commands.findByJobStepIdAndCommandType(step.getJobStepId(),"CANCEL_STEP")).isPresent();assertThat(status()).isEqualTo(JobStatus.RUNNING);}
    @Test void informationAnswerReachesUiWithAnswerAndSourceAndWaitsForDisplayCompletion(){start("WF_INFORMATION_ASSISTANCE",ServiceRequestType.INFORMATION,json.createObjectNode().put("question","A question without a published answer"));drain(2);var answer=selected();assertThat(answer.getStepCodeSnapshot()).isEqualTo("ANSWER");assertThat(answer.getStatus()).isEqualTo(JobStepStatus.RUNNING);assertThat(answer.getResolvedInputsJson().path("data").path("answer").asText()).isNotBlank();assertThat(answer.getResolvedInputsJson().path("data").path("found").asBoolean()).isFalse();
        responses.respond(answer.getJobStepId(),UUID.randomUUID().toString(),json.createObjectNode().put("responseType","UI_DISPLAYED").set("data",json.createObjectNode().put("prompt_key","INFORMATION_ANSWER")),actor());drain(2);assertThat(status()).isEqualTo(JobStatus.SUCCEEDED);assertThat(commands.findByJobIdOrderByCreatedAt(f.jobId)).isEmpty();}
    @Test void escortConfirmationSelectsOneNavigationActionWithEscortMode(){start("WF_PATIENT_ESCORT",ServiceRequestType.ESCORT,json.createObjectNode());drain(2);callback(true);drain(4);var move=selected();assertThat(move.getStepTypeSnapshot()).isEqualTo("MOVE");assertThat(move.getResolvedInputsJson().path("mode").asText()).isEqualTo("ESCORT");
        var device=new DevicePrincipal(f.robotId,robots.findById(f.robotId).orElseThrow().getRobotCode(),0,"test",Instant.now().plusSeconds(300));var profile=navigationProfiles.manifest(device);navigationProfiles.confirm(new NavigationProfileService.Confirm(f.transportId,f.producerId,f.mapId,profile.profile().path("map_asset_sha256").asText(),profile.profileHash()),device);advance();assertThat(commands.findByJobIdOrderByCreatedAt(f.jobId)).hasSize(1);assertThat(commands.findByJobStepId(move.getJobStepId()).orElseThrow().getPayloadJson().path("step").path("params").path("endpoint_id").asText()).isEqualTo(f.endpointId.toString());}
    @Test void speakRequiresConfiguredMqttAudioClient(){start("WF_INFORMATION_ASSISTANCE",ServiceRequestType.INFORMATION,json.createObjectNode().put("question","Question"));tx.executeWithoutResult(s->{s.setRollbackOnly();var w=em.find(Workflow.class,seedId);var answer=em.createQuery("select s from Step s where s.workflow.workflowId=:id and s.stepCode='ANSWER'",Step.class).setParameter("id",seedId).getSingleResult();((ObjectNode)answer.getParamsJson()).put("action","SPEAK");assertThatThrownBy(()->readiness.requireReady(w)).hasMessageContaining("MQTT dispatch");});}
    @Test void scheduleCreatesAnOrchestratedRuntimeOnlyOncePerFire(){resources();tx.executeWithoutResult(s->{s.setRollbackOnly();var w=workflows.findAll().stream().filter(x->x.getSchemaVersion()==3 && x.getWorkflowCode().equals("WF_ROBOT_STARTUP_STANDBY")).findFirst().orElseThrow();var compiled=validation.validateAndCompile(w);w.setDefinitionHash(compiled.definitionHash());w.setCompiledDefinitionJson(compiled.compiledDefinition());w.setStatus(WorkflowStatus.PUBLISHED);w.setAcceptsNewJobs(true);
        var schedule=Schedule.builder().workflow(w).robot(em.find(Robot.class,f.robotId)).createdByAccount(em.find(Account.class,f.accountId)).scheduleCode("P13-"+UUID.randomUUID()).scheduleName("Startup test")
                .scheduleType(ScheduleType.ONCE).timezone("Asia/Saigon").startsAt(Instant.now()).inputTemplateJson(json.createObjectNode()).priority(1).misfirePolicy(MisfirePolicy.FIRE_ONCE).status(ScheduleStatus.ACTIVE).build();em.persist(schedule);em.flush();Instant fire=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);var first=execution.createForSchedule(schedule,fire,json.createObjectNode());var duplicate=execution.createForSchedule(schedule,fire,json.createObjectNode());assertThat(first.getJobId()).isEqualTo(duplicate.getJobId());assertThat(first.isBeOrchestrated()).isTrue();assertThat(first.getDispatchCommandId()).isNull();assertThat(steps.findByJobId(first.getJobId())).hasSize(3);});}
    @Test void patrolUsesNavigationAndRemainsGatedWhenAiProducerIsDisabled(){resources();tx.executeWithoutResult(s->{var w=workflows.findAll().stream().filter(x->x.getSchemaVersion()==3 && x.getWorkflowCode().equals("WF_CROWD_MONITORING")).findFirst().orElseThrow();aiProperties.setSensingEnabled(false);assertThatThrownBy(()->readiness.requireReady(w)).hasMessageContaining("producer/model commissioning");});}
    @Test void recoveryWaitsForPathClearedThenCreatesANewMoveOperation(){UUID correlation=UUID.randomUUID();start("WF_NAVIGATION_RECOVERY",ServiceRequestType.NAVIGATION_RECOVERY,json.createObjectNode().put("recovery_request_id",correlation.toString()));drain(3);assertThat(selected().getStepCodeSnapshot()).isEqualTo("WAIT");assertThat(commands.findByJobIdOrderByCreatedAt(f.jobId)).isEmpty();
        workflowEvents.publish(f.jobId,new WorkflowEventRequest(UUID.randomUUID(),"PATH_CLEARED",correlation.toString(),Instant.now(),json.createObjectNode().put("confirmed",true)),actor(),new RequestMetadata(null,null,null));drain(2);var move=selected();assertThat(move.getStepCodeSnapshot()).isEqualTo("RETRY");assertThat(move.getStatus()).isEqualTo(JobStepStatus.PENDING);assertThat(move.getResolvedInputsJson().path("endpoint_id").asText()).isEqualTo(f.endpointId.toString());assertThat(move.getOperationKey()).startsWith(f.jobId.toString());}
}
