package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.mqtt.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.annotation.DirtiesContext;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MqttRuntimeIntegrationTest extends MySqlIntegrationTest {
    @Autowired ObjectMapper json; @Autowired MqttWire wire; @Autowired MqttProperties properties; @Autowired MqttConnectionState connection;
    @Autowired MqttInboundRouter router; @Autowired MqttInboxStore store; @Autowired MqttInboxProcessor processor;
    @Autowired MqttDurablePublisher publisher; @Autowired MqttPublicationPlans plans; @Autowired MqttSessionRegistry sessions;
    @Autowired WorkflowOrchestratorService orchestrator; @Autowired OrchestrationWorkService wakeups;
    @Autowired JobService jobService;
    @Autowired JobRepository jobs; @Autowired JobStepRepository steps; @Autowired RobotRepository robots;
    @Autowired RobotCommandOutboxRepository commands; @Autowired RobotMqttInboxRepository inbox; @Autowired RobotMqttSessionRepository sessionRows;
    @Autowired JobEventRepository events; @Autowired WorkflowOrchestrationWorkRepository work;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired com.mediassistrover.backend.validation.WorkflowExecutionReadiness readiness;
    @Autowired com.mediassistrover.backend.validation.StepExecutionPolicy executionPolicy;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired NavigationProfileService profiles;
    @MockitoBean PahoMqttTransport transport;
    private TransactionTemplate tx;
    private MqttTestFixture fixture;
    private final List<Publication> published=new java.util.concurrent.CopyOnWriteArrayList<>();
    record Publication(String topic,JsonNode json,int qos) { }

    @BeforeEach void setup() {
        tx=new TransactionTemplate(transactionManager);properties.setEnabled(true);properties.setDispatchEnabled(true);properties.setAllowSimulation(true);
        properties.setEnabledStepTypes(new HashSet<>(List.of("MOVE","WAIT","CONTROL_HARDWARE","EXECUTE_SENSING","INTERACT")));properties.getRobotIds().clear();connection.connectedNow();published.clear();
        when(transport.connected()).thenReturn(true);
        when(transport.publish(anyString(),any(byte[].class),anyInt())).thenAnswer(invocation->{published.add(new Publication(invocation.getArgument(0),wire.parse(invocation.getArgument(1)),invocation.getArgument(2)));return CompletableFuture.completedFuture(null);});
    }
    @AfterEach void cleanup() {
        if (fixture!=null) tx.executeWithoutResult(status->MqttTestFixture.cleanup(entityManager,fixture));
        properties.setEnabled(false);properties.setDispatchEnabled(false);properties.getRobotIds().clear();connection.disconnected();
    }
    private MqttTestFixture fixture(StepType type,String sensing) {
        fixture=tx.execute(status->MqttTestFixture.create(entityManager,json,type,sensing));properties.getRobotIds().add(fixture.robotId);properties.getEnabledStepTypes().add(type.name());
        tx.executeWithoutResult(status->wakeups.request(jobs.findById(fixture.jobId).orElseThrow()));orchestrator.advance(fixture.jobId);return fixture;
    }
    private void receive(ObjectNode raw) {router.route(MqttTopics.topic(fixture.robotId,"events"),wire.bytes(raw),1,false);}
    private void hello() {receive(fixture.hello(json));}
    private void heartbeat() {router.route(MqttTopics.topic(fixture.robotId,"heartbeat"),wire.bytes(fixture.heartbeat(json)),0,false);}
    private RobotCommandOutbox dispatch() {hello();heartbeat();confirmProfile();orchestrator.advance(fixture.jobId);RobotCommandOutbox c=commands.findByJobStepId(fixture.piStepId).orElseThrow();fixture.commandId=c.getCommandId();publisher.command(c.getCommandId());return commands.findById(c.getCommandId()).orElseThrow();}
    private void confirmProfile() {if(fixture.type!=StepType.MOVE)return;var robot=robots.findById(fixture.robotId).orElseThrow();var device=new com.mediassistrover.backend.security.DevicePrincipal(robot.getRobotId(),robot.getRobotCode(),robot.getDeviceTokenVersion(),"test",Instant.now().plusSeconds(300));var manifest=profiles.manifest(device);profiles.confirm(new NavigationProfileService.Confirm(fixture.transportId,fixture.producerId,fixture.mapId,manifest.profile().path("map_asset_sha256").asText(),manifest.profileHash()),device);}
    private ObjectNode ack(boolean accepted) {return fixture.event(json,"COMMAND_ACK",json.createObjectNode().put("commandId",fixture.commandId.toString()).put("commandType","EXECUTE_STEP").put("accepted",accepted).put("status","ACCEPTED"));}
    private ObjectNode start() {return fixture.event(json,"STEP_STARTED",json.createObjectNode().put("attemptNo",1).put("stepType",fixture.type.name()).set("resolvedInputs",commands.findById(fixture.commandId).orElseThrow().getPayloadJson().path("step").path("params")));}
    private ObjectNode result(boolean stopped) {var p=json.createObjectNode().put("stepType",fixture.type.name()).put("status","SUCCEEDED").put("retryable",false).put("physicalStopConfirmed",stopped);p.putNull("errorCode");p.putNull("details");p.set("result",fixture.output(json));return fixture.event(json,"STEP_RESULT",p);}
    private ObjectNode error(String status,Boolean stopped) {var p=json.createObjectNode().put("stepType",fixture.type.name()).put("status",status).put("errorCode","TEST_ERROR").put("errorMessage","test error").put("retryable",false);p.putNull("details");if(stopped!=null)p.put("physicalStopConfirmed",stopped);return fixture.event(json,"STEP_ERROR",p);}
    private Job currentJob() {return jobs.findById(fixture.jobId).orElseThrow();}

    @Test void speechDispatchAcceptsStartedAndAudioResultWithoutInventingChassisStop() {
        fixture(StepType.INTERACT,"SPEAK");var c=dispatch();
        assertThat(c.getPayloadJson().path("step").path("params").path("data").path("text").asText()).isEqualTo("Xin chào");
        receive(ack(true));receive(start());ObjectNode response=result(false);((ObjectNode)response.path("payload")).putNull("physicalStopConfirmed");receive(response);
        orchestrator.advance(fixture.jobId);orchestrator.advance(fixture.jobId);orchestrator.advance(fixture.jobId);
        assertThat(currentJob().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @ParameterizedTest @EnumSource(value=StepType.class, names={"MOVE","CONTROL_HARDWARE","EXECUTE_SENSING","WAIT"})
    void robotCandidateStepTypesDispatchAndAcceptAValidatedResultExactlyOnce(StepType type) {
        fixture(type,type==StepType.EXECUTE_SENSING?"ROBOT_HEALTH":null);RobotCommandOutbox command=dispatch();
        assertThat(command.getPayloadJson().path("step").path("stepType").asText()).isEqualTo(type.name());
        assertThat(command.getPayloadJson().path("step").path("params")).isEqualTo(steps.findById(fixture.piStepId).orElseThrow().getResolvedInputsJson());
        receive(ack(true));receive(start());ObjectNode completion=result(true);receive(completion);
        long eventCount=events.count();long generation=work.findById(fixture.jobId).orElseThrow().getRequestedGeneration();receive(completion);
        JobStep completed=steps.findById(fixture.piStepId).orElseThrow();assertThat(completed.getStatus()).isEqualTo(JobStepStatus.SUCCEEDED);
        assertThat(events.count()).isEqualTo(eventCount);assertThat(work.findById(fixture.jobId).orElseThrow().getRequestedGeneration()).isEqualTo(generation);
        if(type==StepType.SET_CONTEXT)assertThat(currentJob().getContextJson().path("remote_value").asInt()).isEqualTo(1);
    }

    @ParameterizedTest @EnumSource(value=StepType.class, names={"MOVE","CONTROL_HARDWARE","EXECUTE_SENSING","WAIT"})
    void robotCandidateStepTypesAcceptStepError(StepType type) {
        fixture(type,type==StepType.EXECUTE_SENSING?"ROBOT_HEALTH":null);dispatch();receive(error("FAILED",true));
        assertThat(steps.findById(fixture.piStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.FAILED);
        assertThat(steps.findById(fixture.piStepId).orElseThrow().getErrorCode()).isEqualTo("TEST_ERROR");
    }

    @Test void mqttDisabledServerStepUsesItsExistingLocalExecutionPath() {
        fixture=tx.execute(status->MqttTestFixture.create(entityManager,json,StepType.INTERACT,null));properties.getRobotIds().add(fixture.robotId);
        tx.executeWithoutResult(status->wakeups.request(jobs.findById(fixture.jobId).orElseThrow()));orchestrator.advance(fixture.jobId);orchestrator.advance(fixture.jobId);
        assertThat(commands.findByJobStepId(fixture.piStepId)).isEmpty();
        assertThat(steps.findById(fixture.piStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.RUNNING);
    }

    @ParameterizedTest @EnumSource(value=StepType.class, names={"INTERACT","DATA_SERVICE","AUTHENTICATE","HANDLE_PAYLOAD","NOTIFY","CHECK_CONDITION","SET_CONTEXT"})
    void backendOwnedStepTypesNeverCreateMqttCommandsEvenWhenMisconfigured(StepType type) {
        fixture=tx.execute(status->MqttTestFixture.create(entityManager,json,type,null));
        properties.getRobotIds().add(fixture.robotId);properties.getEnabledStepTypes().add(type.name());
        tx.executeWithoutResult(status->wakeups.request(jobs.findById(fixture.jobId).orElseThrow()));
        boolean serverOwned=tx.execute(status->{
            JobStep step=steps.findById(fixture.piStepId).orElseThrow();
            return executionPolicy.isServer(step,step.getResolvedInputsJson());
        });
        assertThat(serverOwned).isTrue();
        for(int i=0;i<3;i++) {
            try {
                orchestrator.advance(fixture.jobId);
            } catch(com.mediassistrover.backend.exception.BusinessRuleException expectedHandlerBlocker) {
                // A backend handler may legitimately require domain fixture data (for example a NOTIFY recipient).
                break;
            }
        }
        assertThat(commands.findByJobStepId(fixture.piStepId)).isEmpty();
    }

    @ParameterizedTest @EnumSource(value=StepType.class, names={"CONTROL_HARDWARE","EXECUTE_SENSING","WAIT"})
    void helloAdvertisingOnlyMoveBlocksEveryOtherRobotCandidate(StepType type) {
        fixture(type,type==StepType.EXECUTE_SENSING?"ROBOT_HEALTH":null);
        receive(fixture.hello(json,Set.of(StepType.MOVE)));heartbeat();
        assertThatThrownBy(()->orchestrator.advance(fixture.jobId))
                .hasMessageContaining("Pi does not enable selected StepType");
        assertThat(commands.findByJobStepId(fixture.piStepId)).isEmpty();
        assertThat(steps.findById(fixture.piStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.PENDING);
    }

    @Test void helloAdvertisingOnlyMoveAllowsMoveAfterAllSafetyGates() {
        fixture(StepType.MOVE,null);receive(fixture.hello(json,Set.of(StepType.MOVE)));heartbeat();confirmProfile();
        orchestrator.advance(fixture.jobId);
        assertThat(commands.findByJobStepId(fixture.piStepId)).isPresent();
    }

    @Test void helloNeedsHeartbeatAndRetainedPresenceCannotAuthorizeDispatch() {
        fixture(StepType.WAIT,null);
        var presence=json.createObjectNode().put("robotId",fixture.robotId.toString()).put("transportSessionId",fixture.transportId.toString()).put("online",true);
        router.route(MqttTopics.topic(fixture.robotId,"presence"),wire.bytes(presence),1,true);orchestrator.advance(fixture.jobId);
        assertThat(commands.findByJobStepId(fixture.piStepId)).isEmpty();hello();
        assertThat(sessionRows.findById(fixture.robotId).orElseThrow().isHandshakeAcked()).isTrue();
        tx.executeWithoutResult(status->wakeups.request(jobs.findById(fixture.jobId).orElseThrow()));orchestrator.advance(fixture.jobId);
        assertThat(commands.findByJobStepId(fixture.piStepId)).isEmpty();heartbeat();orchestrator.advance(fixture.jobId);
        assertThat(commands.findByJobStepId(fixture.piStepId)).isPresent();
        assertThat(published).filteredOn(p->"HELLO_ACK".equals(p.json.path("messageType").asText())).hasSize(1);
        assertThat(inbox.count()).isEqualTo(1); // heartbeat/presence do not enter durable inbox
    }

    @Test void oneCommandContainsOnlyFrozenActionAndRequiresBothAckStages() {
        fixture(StepType.WAIT,null);RobotCommandOutbox c=dispatch();
        JsonNode command=c.getPayloadJson();assertThat(command.has("workflowDefinition")).isFalse();assertThat(command.has("context")).isFalse();assertThat(command.has("jobSteps")).isFalse();
        assertThat(command.path("step").path("params").path("duration_sec").asInt()).isEqualTo(2);assertThat(command.path("step").has("attemptNo")).isFalse();
        tx.executeWithoutResult(status->{steps.findById(fixture.piStepId).orElseThrow().getStep().setParamsJson(json.createObjectNode().put("wait_type","EVENT").put("event_type","CONFIG_CHANGED").put("correlation_key","changed"));
            jobs.findById(fixture.jobId).orElseThrow().setContextJson(json.createObjectNode().put("changed",true));});
        heartbeat();orchestrator.advance(fixture.jobId);assertThat(commands.findById(c.getCommandId()).orElseThrow().getPayloadJson()).isEqualTo(command);
        assertThat(steps.findById(fixture.piStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.PENDING);
        receive(ack(true));assertThat(steps.findById(fixture.piStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.PENDING);
        receive(start());assertThat(steps.findById(fixture.piStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.RUNNING);
        receive(result(true));orchestrator.advance(fixture.jobId);orchestrator.advance(fixture.jobId);orchestrator.advance(fixture.jobId);
        assertThat(currentJob().getStatus()).isEqualTo(JobStatus.SUCCEEDED);assertThat(currentJob().getContextJson().path("done").asBoolean()).isTrue();
        assertThat(published).filteredOn(p->p.topic.endsWith("/commands")).hasSize(1);
    }

    @Test void duplicateResultAcksAgainWithoutCompletingOrAdvancingTwice() {
        fixture(StepType.WAIT,null);dispatch();receive(ack(true));receive(start());ObjectNode result=result(true);receive(result);
        long generation=work.findById(fixture.jobId).orElseThrow().getRequestedGeneration();long eventCount=events.count();receive(result);
        assertThat(events.count()).isEqualTo(eventCount);assertThat(work.findById(fixture.jobId).orElseThrow().getRequestedGeneration()).isEqualTo(generation);
        assertThat(published).filteredOn(p->result.path("messageId").asText().equals(p.json.path("messageId").asText())).hasSize(2);
    }

    @Test void conflictingIdentityIsRejectedAndOriginalReceiptRemainsAccepted() {
        fixture(StepType.WAIT,null);dispatch();ObjectNode ack=ack(true);receive(ack);ObjectNode changed=ack.deepCopy();((ObjectNode)changed.path("payload")).put("accepted",false);
        assertThatThrownBy(()->receive(changed)).hasMessageContaining("conflicting business content");
        assertThat(inbox.findById(UUID.fromString(ack.path("messageId").asText())).orElseThrow().getAccepted()).isTrue();
        assertThat(currentJob().getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);
        assertThat(published.getLast().json.path("accepted").asBoolean()).isFalse();
    }

    @Test void startedWithDifferentParamsRollsBackAndRejectsBeforePiCanAct() {
        fixture(StepType.WAIT,null);dispatch();receive(ack(true));ObjectNode start=start();((ObjectNode)start.path("payload").path("resolvedInputs")).put("duration_sec",3);receive(start);
        assertThat(steps.findById(fixture.piStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.PENDING);
        assertThat(currentJob().getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(published.getLast().json.path("accepted").asBoolean()).isFalse();
    }

    @Test void errorBeforeStartedPreservesTimeoutAndUsesConfiguredTimeoutRoute() {
        fixture(StepType.WAIT,null);dispatch();receive(error("TIMED_OUT",null));
        assertThat(steps.findById(fixture.piStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.TIMED_OUT);
        orchestrator.advance(fixture.jobId);assertThat(currentJob().getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test void restartErrorWithoutPhysicalStopKeepsReportedStatusAndHoldsRobot() {
        fixture(StepType.CONTROL_HARDWARE,null);dispatch();receive(error("FAILED",null));
        assertThat(steps.findById(fixture.piStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.FAILED);
        assertThat(currentJob().getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(robots.findById(fixture.robotId).orElseThrow().getStatus()).isEqualTo(RobotStatus.ERROR);
        orchestrator.advance(fixture.jobId);assertThat(steps.findById(fixture.endStepId).orElseThrow().getStartedAt()).isNull();
    }

    @Test void moveSuccessWithoutStopEvidenceCannotRunNextStep() {
        fixture(StepType.MOVE,null);dispatch();receive(ack(true));receive(start());receive(result(false));
        assertThat(steps.findById(fixture.piStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.SUCCEEDED);
        assertThat(currentJob().getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);orchestrator.advance(fixture.jobId);
        assertThat(steps.findById(fixture.endStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.PENDING);
    }

    @Test void twoMoveVisitsDispatchStrictlySequentiallyAndJobFinishesOnlyAfterBothResults() {
        fixture(StepType.MOVE,null);
        record Second(UUID stepId,UUID endpointId,String operationKey) { }
        Second second=tx.execute(status->{
            JobStep first=steps.findById(fixture.piStepId).orElseThrow(),end=steps.findById(fixture.endStepId).orElseThrow();
            Endpoint endpoint=Endpoint.builder().map(first.getJobTask().getJob().getMap()).endpointCode("SECOND-"+UUID.randomUUID())
                    .endpointName("Second target").endpointType(EndpointType.WAYPOINT).xM(java.math.BigDecimal.TEN)
                    .yM(java.math.BigDecimal.TWO).yawRad(java.math.BigDecimal.ZERO).active(true).accessible(true).build();
            entityManager.persist(endpoint);
            ObjectNode params=json.createObjectNode().put("map_id",fixture.mapId.toString()).put("endpoint_id",endpoint.getEndpointId().toString())
                    .put("mode","NORMAL").put("max_speed_mps",0.05);
            end.getStep().setSequenceNo(3);
            entityManager.flush();
            Step definition=MqttTestFixture.definition(first.getStep().getWorkflow(),first.getStep().getTask(),StepType.MOVE,"MOVE_SECOND",2,params,json);
            entityManager.persist(definition);
            entityManager.createQuery("delete from StepTransition t where t.fromStep.stepId=:from and t.outcome=:outcome")
                    .setParameter("from",first.getStep().getStepId()).setParameter("outcome",TransitionOutcome.SUCCESS).executeUpdate();
            entityManager.persist(StepTransition.builder().workflow(first.getStep().getWorkflow()).fromStep(first.getStep()).toStep(definition)
                    .outcome(TransitionOutcome.SUCCESS).priority(1).defaultTransition(true).build());
            entityManager.persist(StepTransition.builder().workflow(first.getStep().getWorkflow()).fromStep(definition).toStep(end.getStep())
                    .outcome(TransitionOutcome.SUCCESS).priority(1).defaultTransition(true).build());
            for(TransitionOutcome outcome:List.of(TransitionOutcome.FAILURE,TransitionOutcome.TIMEOUT))entityManager.persist(StepTransition.builder()
                    .workflow(first.getStep().getWorkflow()).fromStep(definition).outcome(outcome).priority(1).defaultTransition(true).terminalStatus(TerminalStatus.FAILED).build());
            String operationKey=fixture.jobId+":MOVE_SECOND:1";
            long executionSequence=steps.findByJobId(fixture.jobId).stream().mapToLong(JobStep::getExecutionSequenceNo).max().orElseThrow()+1;
            JobStep visit=JobStep.builder().jobTask(first.getJobTask()).step(definition).visitNo(1).executionSequenceNo(executionSequence)
                    .stepCodeSnapshot("MOVE_SECOND").stepTypeSnapshot("MOVE").resolvedInputsJson(json.createObjectNode())
                    .status(JobStepStatus.PENDING).attemptNo(1).timeoutSecSnapshot(60).operationKey(operationKey).build();
            entityManager.persist(visit);entityManager.flush();return new Second(visit.getJobStepId(),endpoint.getEndpointId(),operationKey);
        });

        dispatch();receive(ack(true));receive(start());receive(result(true));
        assertThat(steps.findById(second.stepId()).orElseThrow().getStatus()).isEqualTo(JobStepStatus.PENDING);
        assertThat(currentJob().getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(commands.findAll()).hasSize(1);

        orchestrator.advance(fixture.jobId);orchestrator.advance(fixture.jobId);
        assertThat(commands.findByJobStepId(second.stepId())).isPresent();
        fixture.piStepId=second.stepId();fixture.endpointId=second.endpointId();fixture.operationKey=second.operationKey();
        RobotCommandOutbox secondCommand=commands.findByJobStepId(second.stepId()).orElseThrow();fixture.commandId=secondCommand.getCommandId();
        publisher.command(secondCommand.getCommandId());receive(ack(true));receive(start());receive(result(true));
        assertThat(currentJob().getStatus()).isEqualTo(JobStatus.RUNNING);
        orchestrator.advance(fixture.jobId);orchestrator.advance(fixture.jobId);orchestrator.advance(fixture.jobId);
        assertThat(currentJob().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(commands.findAll()).hasSize(2);
    }

    @Test void moveLockHealthAndClearanceAcceptTheirOwnResolvedOutputs() {
        for (String variant:List.of("MOVE","LOCK","ROBOT_HEALTH","SIDE_CLEARANCE")) {
            StepType type=variant.equals("MOVE")?StepType.MOVE:variant.equals("LOCK")?StepType.CONTROL_HARDWARE:StepType.EXECUTE_SENSING;
            fixture(type,type==StepType.EXECUTE_SENSING?variant:null);dispatch();receive(ack(true));receive(start());receive(result(true));
            orchestrator.advance(fixture.jobId);orchestrator.advance(fixture.jobId);orchestrator.advance(fixture.jobId);
            assertThat(currentJob().getStatus()).as(variant).isEqualTo(JobStatus.SUCCEEDED);
            tx.executeWithoutResult(status->MqttTestFixture.cleanup(entityManager,fixture));fixture=null;published.clear();
        }
    }

    @Test void heldJobKeepsRobotBusyWhenPiHeartbeatReportsIdle() {
        fixture(StepType.WAIT,null);dispatch();heartbeat();
        assertThat(robots.findById(fixture.robotId).orElseThrow().getStatus()).isEqualTo(RobotStatus.BUSY);
    }

    @Test void sameSessionRetryKeepsCommandIdentityBodyAndExpiry() {
        fixture(StepType.WAIT,null);RobotCommandOutbox c=dispatch();JsonNode snapshot=c.getPayloadJson().deepCopy();
        tx.executeWithoutResult(status->commands.findById(c.getCommandId()).orElseThrow().setLastPublishAt(Instant.now().minusSeconds(10)));
        publisher.command(c.getCommandId());RobotCommandOutbox retried=commands.findById(c.getCommandId()).orElseThrow();
        assertThat(retried.getSendAttempts()).isEqualTo(2);assertThat(retried.getPayloadJson()).isEqualTo(snapshot);assertThat(retried.getExpiresAt()).isEqualTo(c.getExpiresAt());
        properties.setDispatchEnabled(false);tx.executeWithoutResult(status->commands.findById(c.getCommandId()).orElseThrow().setLastPublishAt(Instant.now().minusSeconds(10)));
        publisher.command(c.getCommandId());assertThat(commands.findById(c.getCommandId()).orElseThrow().getSendAttempts()).isEqualTo(2);
    }

    @Test void replayedAcceptedAckIsNotACompletion() {
        fixture(StepType.WAIT,null);dispatch();ObjectNode ack=ack(true);((ObjectNode)ack.path("payload")).put("replayed",true).set("result",json.createObjectNode().put("status","ACCEPTED"));receive(ack);
        assertThat(steps.findById(fixture.piStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.PENDING);
        assertThat(commands.findById(fixture.commandId).orElseThrow().getState()).isEqualTo("ACKNOWLEDGED");
    }

    @Test void oldSessionAndCrossRobotPayloadCannotChangeJob() {
        fixture(StepType.WAIT,null);dispatch();ObjectNode ack=ack(true);ack.put("transportSessionId",UUID.randomUUID().toString());
        assertThatThrownBy(()->receive(ack)).hasMessageContaining("Stale MQTT session");
        ObjectNode foreign=ack(true);foreign.put("robotId",UUID.randomUUID().toString());
        assertThatThrownBy(()->receive(foreign)).hasMessageContaining("topic identity");assertThat(steps.findById(fixture.piStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.PENDING);
    }

    @Test void crashBeforeProcessingRetainsInboxAndRollbackDoesNotSendEventAck() {
        fixture(StepType.WAIT,null);dispatch();ObjectNode ack=ack(true);MqttWire.Envelope e=wire.envelope(ack);store.receive(ack,e);
        int before=published.size();
        tx.executeWithoutResult(status->{processor.process(e.messageId());publisher.acknowledgement(e.messageId(),true);assertThat(published).hasSize(before);status.setRollbackOnly();});
        assertThat(inbox.findById(e.messageId()).orElseThrow().getState()).isEqualTo("PENDING");assertThat(published).hasSize(before);
        processor.process(e.messageId());publisher.acknowledgement(e.messageId(),true);
        assertThat(inbox.findById(e.messageId()).orElseThrow().getState()).isEqualTo("PROCESSED");assertThat(published).hasSize(before+1);
    }

    @Test void reconnectWithoutJournalEvidenceDoesNotRepublishPhysicalCommand() {
        fixture(StepType.CONTROL_HARDWARE,null);RobotCommandOutbox c=dispatch();fixture.transportId=UUID.randomUUID();hello();heartbeat();
        assertThat(currentJob().getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);
        tx.executeWithoutResult(status->commands.findById(c.getCommandId()).orElseThrow().setLastPublishAt(Instant.now().minusSeconds(10)));publisher.command(c.getCommandId());
        assertThat(published).filteredOn(p->p.topic.endsWith("/commands")).hasSize(1);
    }

    @Test void expiredUnpublishedCommandDoesNotExecuteOrWaitForFakePiSuccess() {
        fixture(StepType.WAIT,null);hello();heartbeat();orchestrator.advance(fixture.jobId);RobotCommandOutbox c=commands.findByJobStepId(fixture.piStepId).orElseThrow();
        tx.executeWithoutResult(status->commands.findById(c.getCommandId()).orElseThrow().setExpiresAt(Instant.now().minusSeconds(1)));publisher.command(c.getCommandId());
        assertThat(commands.findById(c.getCommandId()).orElseThrow().getState()).isEqualTo("EXPIRED");
        assertThat(steps.findById(fixture.piStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.TIMED_OUT);assertThat(published).noneMatch(p->p.topic.endsWith("/commands"));
    }

    @Test void reconnectWithJournalRebindsOnlyTransportAndReplaysSameOperation() {
        fixture(StepType.WAIT,null);RobotCommandOutbox c=dispatch();ObjectNode original=(ObjectNode)c.getPayloadJson().deepCopy();
        fixture.transportId=UUID.randomUUID();ObjectNode hello=fixture.hello(json);
        ((com.fasterxml.jackson.databind.node.ArrayNode)hello.path("payload").path("states")).addObject()
                .put("jobId",fixture.jobId.toString()).put("jobStepId",fixture.piStepId.toString()).put("operationKey",fixture.operationKey).put("status","ACCEPTED");
        receive(hello);heartbeat();
        tx.executeWithoutResult(status->commands.findById(c.getCommandId()).orElseThrow().setLastPublishAt(Instant.now().minusSeconds(10)));
        publisher.command(c.getCommandId());ObjectNode resent=(ObjectNode)commands.findById(c.getCommandId()).orElseThrow().getPayloadJson();
        assertThat(resent.path("transportSessionId").asText()).isEqualTo(fixture.transportId.toString());
        original.remove("transportSessionId");ObjectNode business=resent.deepCopy();business.remove("transportSessionId");assertThat(business).isEqualTo(original);
        assertThat(currentJob().getStatus()).isEqualTo(JobStatus.RUNNING);receive(ack(true));receive(start());receive(result(true));
        assertThat(steps.findById(fixture.piStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.SUCCEEDED);
    }

    @Test void replayedSuccessRequiresOutputAndPreviouslyCommittedStart() {
        fixture(StepType.WAIT,null);dispatch();receive(ack(true));receive(start());
        ObjectNode replay=ack(true);ObjectNode payload=(ObjectNode)replay.path("payload");payload.put("replayed",true);
        payload.set("result",result(true).path("payload"));receive(replay);
        assertThat(steps.findById(fixture.piStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.SUCCEEDED);
        tx.executeWithoutResult(status->MqttTestFixture.cleanup(entityManager,fixture));fixture=null;published.clear();
        fixture(StepType.WAIT,null);dispatch();replay=ack(true);payload=(ObjectNode)replay.path("payload");payload.put("replayed",true);payload.set("result",result(true).path("payload"));receive(replay);
        assertThat(currentJob().getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);
        assertThat(steps.findById(fixture.piStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.PENDING);
    }

    @Test void concurrentDuplicateResultsCommitOneEventAndOneWakeup() throws Exception {
        fixture(StepType.WAIT,null);dispatch();receive(ack(true));receive(start());ObjectNode completion=result(true);
        long before=events.count(),generation=work.findById(fixture.jobId).orElseThrow().getRequestedGeneration();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var gate=new java.util.concurrent.CountDownLatch(1);
        try {
            var first=pool.submit(()->{gate.await();receive(completion.deepCopy());return true;});
            var second=pool.submit(()->{gate.await();receive(completion.deepCopy());return true;});gate.countDown();
            assertThat(first.get(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(second.get(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {pool.shutdownNow();}
        assertThat(events.count()).isEqualTo(before+1);
        assertThat(work.findById(fixture.jobId).orElseThrow().getRequestedGeneration()).isEqualTo(generation+1);
        assertThat(inbox.findById(UUID.fromString(completion.path("messageId").asText())).orElseThrow().getAccepted()).isTrue();
    }

    @Test void publishedExpiryRequiresRecoveryAndDoesNotReleaseRobot() {
        fixture(StepType.CONTROL_HARDWARE,null);RobotCommandOutbox c=dispatch();
        tx.executeWithoutResult(status->commands.findById(c.getCommandId()).orElseThrow().setExpiresAt(Instant.now().minusSeconds(1)));publisher.command(c.getCommandId());
        assertThat(currentJob().getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);
        assertThat(commands.findById(c.getCommandId()).orElseThrow().getState()).isEqualTo("RECOVERY_REQUIRED");
        assertThat(robots.findById(fixture.robotId).orElseThrow().getStatus()).isEqualTo(RobotStatus.ERROR);
    }

    @Test void recoveryAndCancelledStatusesArePreservedRatherThanRetried() {
        fixture(StepType.WAIT,null);dispatch();receive(error("CANCELLED",true));
        assertThat(currentJob().getStatus()).isEqualTo(JobStatus.CANCELLED);assertThat(steps.findById(fixture.piStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.CANCELLED);
        tx.executeWithoutResult(status->MqttTestFixture.cleanup(entityManager,fixture));fixture=null;
        fixture(StepType.CONTROL_HARDWARE,null);dispatch();receive(error("RECOVERY_REQUIRED",true));
        assertThat(currentJob().getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(steps.findById(fixture.piStepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.RECOVERY_REQUIRED);
    }

    @Test void allocationSeparatesMqttPhysicalWorkflowFromLegacyAndKeepsServerOnlyAvailable() {
        fixture(StepType.WAIT,null);
        tx.executeWithoutResult(status->{var job=jobs.findById(fixture.jobId).orElseThrow();var robot=job.getRobot();var workflow=job.getWorkflow();
            assertThat(readiness.acceptsRobot(workflow,robot)).isTrue();properties.getRobotIds().clear();assertThat(readiness.acceptsRobot(workflow,robot)).isFalse();
            steps.findById(fixture.piStepId).orElseThrow().getStep().setEnabled(false);assertThat(readiness.acceptsRobot(workflow,robot)).isTrue();
            properties.getRobotIds().add(fixture.robotId);workflow.setSchemaVersion(2);assertThat(readiness.acceptsRobot(workflow,robot)).isFalse();
        });
    }

    @Test void committedHelloReplayReestablishesSessionAfterBackendConnectionRestart() {
        fixture(StepType.WAIT,null);ObjectNode hello=fixture.hello(json);receive(hello);heartbeat();
        var prior=sessionRows.findById(fixture.robotId).orElseThrow();assertThat(sessions.fresh(prior)).isTrue();
        connection.disconnected();connection.connectedNow();assertThat(sessions.fresh(prior)).isFalse();
        receive(hello);assertThat(sessionRows.findById(fixture.robotId).orElseThrow().getConnectionId()).isEqualTo(connection.connectionId());
        assertThat(sessionRows.findById(fixture.robotId).orElseThrow().getLastHeartbeatAt()).isNull();
        heartbeat();assertThat(sessions.fresh(sessionRows.findById(fixture.robotId).orElseThrow())).isTrue();
        assertThat(inbox.findById(UUID.fromString(hello.path("messageId").asText())).orElseThrow().getAccepted()).isTrue();
    }

    @Test void restartedPiCannotResumeAnAttemptedPhysicalOperationAutomatically() {
        fixture(StepType.CONTROL_HARDWARE,null);dispatch();fixture.producerId=UUID.randomUUID();fixture.transportId=UUID.randomUUID();ObjectNode hello=fixture.hello(json);
        ((com.fasterxml.jackson.databind.node.ArrayNode)hello.path("payload").path("states")).addObject()
                .put("jobId",fixture.jobId.toString()).put("jobStepId",fixture.piStepId.toString()).put("operationKey",fixture.operationKey).put("status","ACCEPTED");receive(hello);
        assertThat(currentJob().getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);
        assertThat(sessionRows.findById(fixture.robotId).orElseThrow().isDispatchReady()).isFalse();
        assertThat(published).filteredOn(p->p.topic.endsWith("/commands")).hasSize(1);
    }

    @Test void genericRecoveryRequiresANewHelloInsteadOfKeepingTheOldDispatchBlockForever() {
        fixture(StepType.MOVE,null);hello();heartbeat();
        var principal=tx.execute(status->com.mediassistrover.backend.security.AccountPrincipal.fromAccount(entityManager.find(Account.class,fixture.accountId)));
        tx.executeWithoutResult(status->{
            var job=jobs.findById(fixture.jobId).orElseThrow();job.setStatus(JobStatus.RECOVERY_REQUIRED);
            job.getRobot().setStatus(RobotStatus.ERROR);
            var session=sessionRows.findById(fixture.robotId).orElseThrow();session.setDispatchReady(false);
            session.setFailureReason("Robot is held by a legacy or recovery Job");
        });
        var request=new com.mediassistrover.backend.dto.job.request.JobRequests.ReconcileJobRequest("Close unissued operation",
                com.mediassistrover.backend.dto.job.request.JobRequests.ReconcileOutcome.CONFIRM_CANCELLED);
        jobService.reconcile(fixture.jobId,request,principal,new RequestMetadata("127.0.0.1","test","generic-recovery"));
        assertThat(currentJob().getStatus()).isEqualTo(JobStatus.CANCELLED);
        var closed=sessionRows.findById(fixture.robotId).orElseThrow();
        assertThat(closed.isClosed()).isTrue();assertThat(sessions.owned(closed)).isFalse();
        assertThat(closed.isDispatchReady()).isFalse();
        assertThat(commands.findByJobIdOrderByCreatedAt(fixture.jobId)).isEmpty();
        fixture.transportId=UUID.randomUUID();hello();heartbeat();
        var refreshed=sessionRows.findById(fixture.robotId).orElseThrow();
        assertThat(sessions.fresh(refreshed)).isTrue();assertThat(refreshed.getFailureReason()).isNull();
        assertThat(currentJob().getStatus()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test void adminAssignmentAndGenericReconcileCannotBypassMqttTransportOrStopChecks() {
        fixture(StepType.MOVE,null);
        var principal=tx.execute(status->com.mediassistrover.backend.security.AccountPrincipal.fromAccount(entityManager.find(Account.class,fixture.accountId)));
        tx.executeWithoutResult(status->{var job=jobs.findById(fixture.jobId).orElseThrow();job.setStatus(JobStatus.QUEUED);job.setAcceptedAt(null);job.getRobot().setStatus(RobotStatus.IDLE);});
        properties.getRobotIds().clear();
        assertThatThrownBy(()->jobService.assignRobot(fixture.jobId,fixture.robotId,principal,null)).hasMessageContaining("workflow transport");
        properties.getRobotIds().add(fixture.robotId);tx.executeWithoutResult(status->{var job=jobs.findById(fixture.jobId).orElseThrow();job.setStatus(JobStatus.RUNNING);job.getRobot().setStatus(RobotStatus.BUSY);});
        dispatch();receive(ack(true));receive(start());receive(result(false));
        var response=new com.mediassistrover.backend.dto.job.request.JobRequests.ReconcileJobRequest("Staff reviewed",com.mediassistrover.backend.dto.job.request.JobRequests.ReconcileOutcome.CONFIRM_CANCELLED);
        assertThatThrownBy(()->jobService.reconcile(fixture.jobId,response,principal,null)).hasMessageContaining("operation/stop reconciliation");
        assertThat(currentJob().getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(robots.findById(fixture.robotId).orElseThrow().getStatus()).isEqualTo(RobotStatus.ERROR);
    }
}
