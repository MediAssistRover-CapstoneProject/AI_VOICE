package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.mqtt.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.*;
import com.mediassistrover.backend.service.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MqttNavigationGroupIntegrationTest extends MySqlIntegrationTest {
    @Autowired EntityManager em;@Autowired ObjectMapper json;@Autowired PlatformTransactionManager manager;
    @Autowired MqttProperties config;@Autowired MqttConnectionState connection;@Autowired MqttWire wire;@Autowired MqttInboundRouter router;
    @Autowired MqttDurablePublisher publisher;@Autowired MqttPublicationPlans plans;@Autowired NavigationProfileService profiles;
    @Autowired JobRepository jobs;@Autowired JobStepRepository steps;@Autowired RobotRepository robots;@Autowired RobotCommandOutboxRepository commands;
    @Autowired JobNavigationSelectionRepository selections;@Autowired RobotNavigationProfileRepository profileRows;
    @Autowired WorkflowOrchestratorService orchestrator;@Autowired OrchestrationWorkService work;
    @Autowired PiIntegrationService legacyTelemetry;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;@Autowired DeviceJwtTokenService tokens;
    @MockitoBean PahoMqttTransport transport;
    TransactionTemplate tx;MqttTestFixture f;UUID groupId,secondId,deliveryId;DevicePrincipal device;List<JsonNode> publications=new ArrayList<>();

    @BeforeEach void setup(){tx=new TransactionTemplate(manager);config.setEnabled(true);config.setDispatchEnabled(true);config.setAllowSimulation(true);
        config.setEnabledStepTypes(new HashSet<>(List.of("MOVE","WAIT","CONTROL_HARDWARE","EXECUTE_SENSING")));config.getRobotIds().clear();connection.connectedNow();
        when(transport.connected()).thenReturn(true);when(transport.publish(anyString(),any(byte[].class),anyInt())).thenAnswer(call->{publications.add(wire.parse(call.getArgument(1)));return CompletableFuture.completedFuture(null);});}
    @AfterEach void cleanup(){if(f!=null)tx.executeWithoutResult(status->{
        if(deliveryId!=null){em.createNativeQuery("UPDATE Job SET service_request_id=NULL WHERE job_id=:id").setParameter("id",f.jobId).executeUpdate();
            em.createNativeQuery("DELETE FROM DeliveryRequest WHERE service_request_id=:id").setParameter("id",deliveryId).executeUpdate();
            em.createNativeQuery("DELETE FROM ServiceRequest WHERE service_request_id=:id").setParameter("id",deliveryId).executeUpdate();}
        em.createNativeQuery("DELETE FROM JobNavigationSelection WHERE selector_job_step_id IN (SELECT s.job_step_id FROM JobStep s JOIN JobTask t ON t.job_task_id=s.job_task_id WHERE t.job_id=:id)").setParameter("id",f.jobId).executeUpdate();
        if(groupId!=null){em.createNativeQuery("DELETE FROM EndpointGroupMember WHERE endpoint_group_id=:id").setParameter("id",groupId).executeUpdate();em.createNativeQuery("DELETE FROM EndpointGroup WHERE endpoint_group_id=:id").setParameter("id",groupId).executeUpdate();}
        em.createNativeQuery("DELETE FROM Zone WHERE map_id=:id").setParameter("id",f.mapId).executeUpdate();MqttTestFixture.cleanup(em,f);
    });config.setEnabled(false);config.setDispatchEnabled(false);config.getRobotIds().clear();connection.disconnected();}

    void fixture(boolean grouped){f=tx.execute(status->MqttTestFixture.create(em,json,StepType.MOVE,null));config.getRobotIds().add(f.robotId);
        tx.executeWithoutResult(status->{Robot robot=em.find(Robot.class,f.robotId);robot.setDeviceSecretHash("test-device-registration");
            if(grouped){Job job=em.find(Job.class,f.jobId);JobStep move=em.find(JobStep.class,f.piStepId);Task task=move.getStep().getTask();
                EndpointGroup group=EndpointGroup.builder().map(job.getMap()).groupCode("G-"+UUID.randomUUID()).groupName("Group").selectionPolicy(SelectionPolicy.PRIORITY).allowFallback(true).active(true).build();em.persist(group);groupId=group.getEndpointGroupId();
                Endpoint second=Endpoint.builder().map(job.getMap()).endpointCode("Z-"+UUID.randomUUID()).endpointName("Second").endpointType(EndpointType.WAYPOINT)
                        .xM(BigDecimal.TEN).yM(BigDecimal.ONE).yawRad(BigDecimal.ZERO).active(true).accessible(true).build();em.persist(second);secondId=second.getEndpointId();
                for(int i=0;i<2;i++){Endpoint e=em.find(Endpoint.class,i==0?f.endpointId:secondId);em.persist(EndpointGroupMember.builder().id(new EndpointGroupMemberId(groupId,e.getEndpointId())).endpointGroup(group).endpoint(e).map(job.getMap()).sequenceNo(i+1).active(true).build());}
                job.setInputJson(json.createObjectNode().put("endpoint_group_id",groupId.toString()));job.getWorkflow().setMaxTotalVisits(40);move.getStep().setMaxVisits(3);
                ((ObjectNode)move.getStep().getParamsJson()).remove("endpoint_id");move.getStep().setInputMappingJson(json.createObjectNode().set("endpoint_id",ref("/outputs/SELECT/data/endpoint_id")));
                Step resolve=define(job,task,"RESOLVE",StepType.DATA_SERVICE,10,json.createObjectNode().put("service_key","RESOLVE_TARGETS").put("endpoint_group_id",groupId.toString()),json.createObjectNode());
                Step init=define(job,task,"INIT",StepType.SET_CONTEXT,11,operation("SET",0),json.createObjectNode());
                Step select=define(job,task,"SELECT",StepType.DATA_SERVICE,12,json.createObjectNode().put("service_key","SELECT_TARGET"),json.createObjectNode().set("targets",ref("/outputs/RESOLVE/data/targets")));
                ((ObjectNode)select.getInputMappingJson()).set("index",ref("/vars/target_index"));
                Step has=define(job,task,"HAS",StepType.CHECK_CONDITION,13,json.createObjectNode().set("expression",condition("/outputs/SELECT/data/has_target",true)),json.createObjectNode());
                Step next=define(job,task,"NEXT",StepType.SET_CONTEXT,14,operation("INCREMENT",1),json.createObjectNode());
                job.getWorkflow().setStartStep(resolve);route(job,resolve,init,TransitionOutcome.SUCCESS,null,1);route(job,init,select,TransitionOutcome.SUCCESS,null,1);
                route(job,select,has,TransitionOutcome.SUCCESS,null,1);route(job,has,move.getStep(),TransitionOutcome.SUCCESS,condition("/result/value",true),1);
                route(job,has,null,TransitionOutcome.SUCCESS,null,2);route(job,next,select,TransitionOutcome.SUCCESS,null,1);
                // Retain the default failure edge to FAILED, and add only a confirmed path-unavailable fallback edge.
                em.createNativeQuery("UPDATE StepTransition SET priority=2 WHERE from_step_id=:id AND outcome='FAILURE'").setParameter("id",move.getStep().getStepId()).executeUpdate();
                route(job,move.getStep(),next,TransitionOutcome.FAILURE,condition("/error/errorCode","PATH_UNAVAILABLE"),1);
            }
            work.request(em.find(Job.class,f.jobId));
        });device=principal();drain(18);}
    Step define(Job job,Task task,String code,StepType type,int seq,ObjectNode params,ObjectNode mapping){Step step=Step.builder().workflow(job.getWorkflow()).task(task).stepCode(code).stepName(code).stepType(type).sequenceNo(seq)
            .enabled(true).paramsJson(params).inputMappingJson(mapping).timeoutSec(60).maxAttempts(1).retryDelaySec(0).retryCodesJson(json.createArrayNode()).maxVisits(3).auditLevel(StepAuditLevel.FULL).build();em.persist(step);
        JobTask jt=em.find(JobStep.class,f.piStepId).getJobTask();em.persist(JobStep.builder().jobTask(jt).step(step).visitNo(1).executionSequenceNo(seq).stepCodeSnapshot(code).stepTypeSnapshot(type.name()).status(JobStepStatus.PENDING)
                .attemptNo(1).timeoutSecSnapshot(60).resolvedInputsJson(json.createObjectNode()).operationKey(job.getJobId()+":"+code+":1").build());
        for(TransitionOutcome outcome:List.of(TransitionOutcome.FAILURE,TransitionOutcome.TIMEOUT))route(job,step,null,outcome,null,1);return step;}
    void route(Job job,Step from,Step to,TransitionOutcome outcome,JsonNode condition,int priority){em.persist(StepTransition.builder().workflow(job.getWorkflow()).fromStep(from).toStep(to).outcome(outcome).conditionJson(condition).priority(priority).defaultTransition(condition==null).terminalStatus(to==null?TerminalStatus.FAILED:null).build());}
    ObjectNode operation(String op,int value){ObjectNode body=json.createObjectNode();body.putArray("operations").addObject().put("path","/vars/target_index").put("operation",op).put("value",value);return body;}
    ObjectNode ref(String path){return json.createObjectNode().put("$ref",path);}ObjectNode condition(String path,Object value){return json.createObjectNode().put("path",path).put("operator","EQ").set("value",json.valueToTree(value));}
    DevicePrincipal principal(){Robot r=robots.findById(f.robotId).orElseThrow();return new DevicePrincipal(r.getRobotId(),r.getRobotCode(),r.getDeviceTokenVersion(),"test",Instant.now().plusSeconds(300));}
    void drain(int limit){for(int i=0;i<limit;i++){try{orchestrator.advance(f.jobId);}catch(BusinessRuleException ex){orchestrator.reject(f.jobId,ex.getMessage());}}}
    void receive(ObjectNode raw){router.route(MqttTopics.topic(f.robotId,"events"),wire.bytes(raw),1,false);}
    void hello(){receive(f.hello(json));heartbeat();}
    void heartbeat(){router.route(MqttTopics.topic(f.robotId,"heartbeat"),wire.bytes(f.heartbeat(json)),0,false);}
    NavigationProfileService.Confirm confirmation(){var manifest=profiles.manifest(device);return new NavigationProfileService.Confirm(f.transportId,f.producerId,f.mapId,manifest.profile().path("map_asset_sha256").asText(),manifest.profileHash());}
    void profile(){profiles.confirm(confirmation(),device);}
    Job job(){return jobs.findById(f.jobId).orElseThrow();}
    RobotCommandOutbox execute(){drain(6);JobStep selected=steps.findById(job().getSelectedJobStep().getJobStepId()).orElseThrow();f.piStepId=selected.getJobStepId();f.operationKey=selected.getOperationKey();RobotCommandOutbox command=commands.findByJobStepId(f.piStepId).orElseThrow();f.commandId=command.getCommandId();publisher.command(f.commandId);
        receive(f.event(json,"COMMAND_ACK",json.createObjectNode().put("commandId",f.commandId.toString()).put("commandType","EXECUTE_STEP").put("accepted",true)));
        receive(f.event(json,"STEP_STARTED",json.createObjectNode().put("attemptNo",1).put("stepType","MOVE").set("resolvedInputs",command.getPayloadJson().path("step").path("params"))));return command;}
    void failure(String code,boolean stop){ObjectNode body=json.createObjectNode().put("status","FAILED").put("errorCode",code).put("retryable",false).put("physicalStopConfirmed",stop);body.putNull("details");receive(f.event(json,"STEP_ERROR",body));drain(18);}
    void success(){ObjectNode output=f.output(json);String id=steps.findById(f.piStepId).orElseThrow().getResolvedInputsJson().path("endpoint_id").asText();output.put("endpoint_id",id);for(JsonNode attempt:output.path("attempts"))((ObjectNode)attempt).put("endpoint_id",id);
        ObjectNode body=json.createObjectNode().put("status","SUCCEEDED").put("retryable",false).put("physicalStopConfirmed",true);body.putNull("errorCode");body.putNull("details");body.set("result",output);receive(f.event(json,"STEP_RESULT",body));drain(12);}

    @Test void moveWaitsForAuthenticatedProfileThenDispatchesOneFrozenEndpoint(){fixture(false);hello();drain(6);assertThat(commands.findByJobStepId(f.piStepId)).isEmpty();
        profile();var command=execute();assertThat(command.getNavigationProfileHash()).isEqualTo(confirmation().profileHash());
        assertThat(command.getPayloadJson().path("step").path("params").has("endpoint_group_id")).isFalse();success();assertThat(job().getStatus()).isEqualTo(JobStatus.SUCCEEDED);}
    @Test void pathUnavailableWithStopCreatesANewVisitForTheNextCandidate(){fixture(true);hello();profile();var first=execute();JsonNode original=first.getPayloadJson().deepCopy();failure("PATH_UNAVAILABLE",true);
        var second=execute();assertThat(second.getPayloadJson().path("step").path("params").path("endpoint_id").asText()).isEqualTo(secondId.toString());
        assertThat(second.getOperationKey()).isNotEqualTo(first.getOperationKey());assertThat(second.getCommandId()).isNotEqualTo(first.getCommandId());
        assertThat(commands.findById(first.getCommandId()).orElseThrow().getPayloadJson()).isEqualTo(original);success();assertThat(job().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(selections.history(f.jobId,groupId)).hasSize(2);}
    @Test void abortDoesNotTryAnotherEndpoint(){fixture(true);hello();profile();execute();failure("NAV_ABORTED",true);assertThat(job().getStatus()).isEqualTo(JobStatus.FAILED);assertThat(commands.findByJobIdOrderByCreatedAt(f.jobId)).hasSize(1);}
    @Test void missingStopHoldsRecoveryAndDoesNotTryAnotherEndpoint(){fixture(true);hello();profile();execute();failure("PATH_UNAVAILABLE",false);assertThat(job().getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(commands.findByJobIdOrderByCreatedAt(f.jobId)).hasSize(1);}
    @Test void exhaustsCandidatesWithoutRepeatingAnEndpoint(){fixture(true);hello();profile();execute();failure("PATH_UNAVAILABLE",true);execute();failure("PATH_UNAVAILABLE",true);
        assertThat(job().getStatus()).isEqualTo(JobStatus.FAILED);assertThat(commands.findByJobIdOrderByCreatedAt(f.jobId)).hasSize(2);assertThat(job().getContextJson().path("target_index").asInt()).isEqualTo(2);}
    @Test void groupCanDisableFallback(){fixture(true);tx.executeWithoutResult(s->em.find(EndpointGroup.class,groupId).setAllowFallback(false));hello();profile();execute();failure("PATH_UNAVAILABLE",true);
        assertThat(job().getStatus()).isEqualTo(JobStatus.FAILED);assertThat(commands.findByJobIdOrderByCreatedAt(f.jobId)).hasSize(1);}
    @Test void recordsResolvedDeliveryEndpointOnlyAfterTheChosenMoveSucceeds(){fixture(true);
        deliveryId=tx.execute(status->{Job job=em.find(Job.class,f.jobId);ServiceRequest request=ServiceRequest.builder().requestedByAccount(em.find(Account.class,f.accountId))
                .requestCode("D-"+UUID.randomUUID()).requestType(ServiceRequestType.DELIVERY).sourceType(RequestSourceType.STAFF_UI).status(ServiceRequestStatus.IN_PROGRESS)
                .priority(50).idempotencyKey(UUID.randomUUID().toString()).requestDataJson(json.createObjectNode()).requestedAt(Instant.now()).build();em.persist(request);job.setServiceRequest(request);
            em.persist(DeliveryRequest.builder().serviceRequest(request).map(job.getMap()).pickupEndpoint(em.find(Endpoint.class,f.endpointId))
                    .deliveryGroup(em.find(EndpointGroup.class,groupId)).targetMode(TargetMode.ENDPOINT_GROUP).recipientName("recipient").requiresConfirmation(true).build());return request.getServiceRequestId();});
        hello();profile();execute();failure("PATH_UNAVAILABLE",true);
        UUID before=tx.execute(status->{Endpoint e=em.find(DeliveryRequest.class,deliveryId).getResolvedDeliveryEndpoint();return e==null?null:e.getEndpointId();});assertThat(before).isNull();
        execute();success();UUID resolved=tx.execute(status->em.find(DeliveryRequest.class,deliveryId).getResolvedDeliveryEndpoint().getEndpointId());assertThat(resolved).isEqualTo(secondId);
    }
    @Test void replacingAProfileCannotReinterpretAnUnpublishedOutbox(){fixture(false);hello();profile();drain(5);var command=commands.findByJobStepId(f.piStepId).orElseThrow();String hash=command.getNavigationProfileHash();
        tx.executeWithoutResult(status->em.find(Endpoint.class,f.endpointId).setXM(new BigDecimal("12")));profile();
        assertThat(plans.command(command.getCommandId())).isNull();assertThat(commands.findById(command.getCommandId()).orElseThrow().getNavigationProfileHash()).isEqualTo(hash);}
    @Test void expiredCommandWhileWaitingForAProfileNeverPublishes(){fixture(false);hello();profile();drain(5);var command=commands.findByJobStepId(f.piStepId).orElseThrow();
        tx.executeWithoutResult(status->em.find(RobotCommandOutbox.class,command.getCommandId()).setExpiresAt(Instant.now().minusSeconds(1)));
        assertThat(plans.command(command.getCommandId())).isNull();assertThat(commands.findById(command.getCommandId()).orElseThrow().getState()).isEqualTo("EXPIRED");
        assertThat(commands.findById(command.getCommandId()).orElseThrow().getSendAttempts()).isZero();}
    @Test void rejectsWrongProfileHashAndProducerSession(){fixture(false);hello();var body=confirmation();
        assertThatThrownBy(()->profiles.confirm(new NavigationProfileService.Confirm(body.transportSessionId(),body.producerSessionId(),body.mapId(),body.mapAssetHash(),"b".repeat(64)),device)).hasMessageContaining("differs");
        assertThatThrownBy(()->profiles.confirm(new NavigationProfileService.Confirm(body.transportSessionId(),UUID.randomUUID(),body.mapId(),body.mapAssetHash(),body.profileHash()),device)).hasMessageContaining("session");
        assertThat(profileRows.findById(f.robotId)).isEmpty();}
    @Test void duplicateProfileAcknowledgementKeepsItsTimestamp(){fixture(false);hello();var first=profiles.confirm(confirmation(),device);var duplicate=profiles.confirm(confirmation(),device);
        assertThat(duplicate.duplicate()).isTrue();assertThat(duplicate.acknowledgedAt()).isEqualTo(first.acknowledgedAt());}
    @Test void catalogEditBeforeFirstPublishHoldsTheFrozenCommand(){fixture(false);hello();profile();drain(5);var command=commands.findByJobStepId(f.piStepId).orElseThrow();JsonNode before=command.getPayloadJson().deepCopy();
        tx.executeWithoutResult(s->em.find(Endpoint.class,f.endpointId).setXM(new BigDecimal("12")));assertThat(plans.command(command.getCommandId())).isNull();
        assertThat(commands.findById(command.getCommandId()).orElseThrow().getSendAttempts()).isZero();assertThat(commands.findById(command.getCommandId()).orElseThrow().getPayloadJson()).isEqualTo(before);}
    @Test void profileCannotBeReplacedDuringUnresolvedMove(){fixture(false);hello();profile();execute();tx.executeWithoutResult(s->em.find(Endpoint.class,f.endpointId).setXM(new BigDecimal("12")));
        assertThatThrownBy(()->profiles.confirm(confirmation(),device)).hasMessageContaining("unresolved");}
    @Test void newTransportRequiresANewProfileAcknowledgement(){fixture(false);hello();profile();f.transportId=UUID.randomUUID();hello();
        tx.executeWithoutResult(s->work.request(em.find(Job.class,f.jobId)));drain(6);assertThat(commands.findByJobStepId(f.piStepId)).isEmpty();profile();execute();}
    @Test void deviceTokenRevocationInvalidatesProfileReadiness(){fixture(false);hello();profile();tx.executeWithoutResult(s->em.find(Robot.class,f.robotId).setDeviceTokenVersion(1));drain(6);
        assertThat(commands.findByJobStepId(f.piStepId)).isEmpty();assertThatThrownBy(()->profiles.manifest(device)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);}
    @Test void mapAssetChangeDuringMoveHoldsRecovery(){fixture(false);hello();profile();execute();ObjectNode hb=f.heartbeat(json);((ObjectNode)hb.path("payload")).put("mapAssetHash","b".repeat(64));
        router.route(MqttTopics.topic(f.robotId,"heartbeat"),wire.bytes(hb),0,false);assertThat(job().getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);}
    @Test void enrolledRobotCannotOverwriteMqttMapOrStatusThroughLegacyHeartbeat(){fixture(false);hello();profile();
        var body=new com.mediassistrover.backend.dto.pi.request.HeartbeatPayload(RobotStatus.IDLE,"old-runtime","old-firmware",1,json.createArrayNode(),"b".repeat(64),"b".repeat(64),f.mapId,null,BatteryUnit.UNKNOWN,null,null);
        var envelope=new com.mediassistrover.backend.dto.pi.request.PiEnvelope<>("1.0",UUID.randomUUID(),"legacy-heartbeat",UUID.randomUUID(),f.robotId,null,null,"HEARTBEAT",Instant.now(),f.producerId,1,body);
        assertThatThrownBy(()->legacyTelemetry.heartbeat(envelope,device)).hasMessageContaining("through MQTT");
        var robot=robots.findById(f.robotId).orElseThrow();assertThat(robot.getStatus()).isEqualTo(RobotStatus.BUSY);assertThat(robot.getMapAssetHash()).isEqualTo("a".repeat(64));}
    @Test void profileEndpointsExcludeInactiveOrInaccessiblePoints(){fixture(true);String before=profiles.manifest(device).profileHash();tx.executeWithoutResult(s->em.find(Endpoint.class,secondId).setAccessible(false));
        var after=profiles.manifest(device);assertThat(after.profileHash()).isNotEqualTo(before);assertThat(after.profile().path("endpoints").has(secondId.toString())).isFalse();}
    @Test void restProfileAckRequiresDeviceIdentityAndValidBody() throws Exception {fixture(false);hello();String token=tx.execute(s->tokens.issue(em.find(Robot.class,f.robotId)).value());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/pi/catalog/navigation-profile").header("Authorization","Bearer "+token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.profile.map_id").value(f.mapId.toString()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/pi/catalog/navigation-profile/ack").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsBytes(confirmation())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/pi/catalog/navigation-profile/ack").header("Authorization","Bearer "+token).contentType("application/json").content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/pi/catalog/navigation-profile"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());}
}
