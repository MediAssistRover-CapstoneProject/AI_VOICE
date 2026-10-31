package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.dto.job.request.WorkflowEventRequest;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.*;
import com.mediassistrover.backend.service.*;
import com.mediassistrover.backend.validation.WorkflowExecutionReadiness;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Transactional
class BeOrchestrationIntegrationTest extends MySqlIntegrationTest {
    @Autowired ObjectMapper json;
    @Autowired WorkflowRepository workflows;
    @Autowired TaskRepository tasks;
    @Autowired StepRepository definitions;
    @Autowired StepTransitionRepository routes;
    @Autowired JobRepository jobs;
    @Autowired JobTaskRepository jobTasks;
    @Autowired JobStepRepository steps;
    @Autowired JobEventRepository events;
    @Autowired RobotRepository robots;
    @Autowired AccountRepository accounts;
    @Autowired ServiceRequestRepository requests;
    @Autowired WorkflowOrchestrationWorkRepository work;
    @Autowired WorkflowOrchestratorService orchestrator;
    @Autowired OrchestrationWorkService wakeups;
    @Autowired JobStepResponseService responses;
    @Autowired WorkflowEventService workflowEvents;
    @Autowired JobExecutionService execution;
    @Autowired WorkflowExecutionReadiness readiness;
    @Autowired PiJobRuntimeService pi;
    @Autowired JobService jobService;
    @Autowired WorkflowService workflowService;
    @Autowired JobStepResponseRepository responseRecords;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired JwtTokenService jwt;
    @Autowired JobUiEventStream uiEvents;
    @Autowired AuthVerificationService auth;
    @Autowired QrVerificationService qr;

    @Test void choosesStartAndFalseBranchWithoutRunningOtherPendingSteps() {
        Fixture f=fixture();
        Step unused=define(f,"UNUSED",StepType.SET_CONTEXT,1,set("unexpected",1));
        Step start=define(f,"START",StepType.CHECK_CONDITION,9,check("/inputs/flag","EQ",true));
        Step end=define(f,"END",StepType.SET_CONTEXT,4,set("done",true));
        f.workflow.setStartStep(start);
        route(f,start,unused,condition("/result/value","EQ",true),1);
        route(f,start,end,null,2); terminal(f,end,TransitionOutcome.SUCCESS,TerminalStatus.SUCCEEDED);
        terminal(f,unused,TransitionOutcome.SUCCESS,TerminalStatus.FAILED);
        Job job=runtime(f); drain(job,12);
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.getContextJson().path("done").asBoolean()).isTrue();
        assertThat(job.getContextJson().has("unexpected")).isFalse();
        assertThat(step(job,"START").getResultJson().path("value").asBoolean()).isFalse();
        assertThat(step(job,"UNUSED").getStatus()).isEqualTo(JobStepStatus.PENDING);
        assertThat(step(job,"UNUSED").getStartedAt()).isNull();
        assertThat(jobTasks.findByJobJobIdOrderBySequenceNoAsc(job.getJobId()).getFirst().getStatus()).isEqualTo(JobTaskStatus.SUCCEEDED);
        assertThat(job.getRobot().getStatus()).isEqualTo(RobotStatus.IDLE);
    }

    @Test void loopsCreateVisitsAndDuplicateWakeupsCannotIncrementTwice() {
        Fixture f=fixture(); Step init=define(f,"INIT",StepType.SET_CONTEXT,1,set("loops",0));
        Step check=define(f,"CHECK",StepType.CHECK_CONDITION,2,check("/vars/loops","LT",2));
        Step increment=define(f,"INC",StepType.SET_CONTEXT,3,increment("loops",1)); f.workflow.setStartStep(init);
        route(f,init,check,null,1); route(f,check,increment,condition("/result/value","EQ",true),1);
        terminal(f,check,TransitionOutcome.SUCCESS,TerminalStatus.SUCCEEDED,2); route(f,increment,check,null,1);
        f.workflow.setOutputMappingJson(json.createObjectNode().set("loop_count",json.createObjectNode().put("$ref","/vars/loops")));
        Job job=runtime(f); wakeups.request(job); wakeups.request(job); drain(job,32);
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED); assertThat(job.getContextJson().path("loops").asInt()).isEqualTo(2);
        assertThat(job.getResultJson().path("loop_count").asInt()).isEqualTo(2);
        var increments=steps.findByJobId(job.getJobId()).stream().filter(s->s.getStepCodeSnapshot().equals("INC")).toList();
        assertThat(increments).hasSize(2); assertThat(increments).extracting(JobStep::getOperationKey).doesNotHaveDuplicates();
        long eventCount=events.count(); orchestrator.advance(job.getJobId()); assertThat(events.count()).isEqualTo(eventCount);
        assertThat(work.findById(job.getJobId()).orElseThrow().getRequestedGeneration()).isEqualTo(work.findById(job.getJobId()).orElseThrow().getProcessedGeneration());
    }

    @Test void maxVisitsCountsExecutedVisitsAndStopsInfiniteLoops() {
        Fixture f=fixture(); Step init=define(f,"INIT",StepType.SET_CONTEXT,1,set("loops",0));
        Step loop=define(f,"LOOP",StepType.SET_CONTEXT,2,increment("loops",1)); loop.setMaxVisits(2); f.workflow.setStartStep(init);
        route(f,init,loop,null,1); route(f,loop,loop,null,1); Job job=runtime(f); drain(job,20);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED); assertThat(job.getErrorCode()).isEqualTo("WORKFLOW_VISIT_LIMIT");
        assertThat(job.getContextJson().path("loops").asInt()).isEqualTo(2);
    }

    @Test void totalVisitLimitStopsBeforeSelectingAnotherAction() {
        Fixture f=fixture(); Step loop=define(f,"LOOP",StepType.SET_CONTEXT,1,set("ok",true)); f.workflow.setStartStep(loop);
        f.workflow.setMaxTotalVisits(2); route(f,loop,loop,null,1); Job job=runtime(f); drain(job,20);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(steps.findByJobId(job.getJobId()).stream().filter(s->s.getStartedAt()!=null)).hasSize(2);
    }

    @Test void displayCallbackIsIdempotentScopedAndResumesAfterCommitWork() {
        Fixture f=fixture(); Step display=define(f,"DISPLAY",StepType.INTERACT,1,interact("HELLO","DISPLAY"));
        f.workflow.setStartStep(display); terminal(f,display,TransitionOutcome.SUCCESS,TerminalStatus.SUCCEEDED);
        Job job=runtime(f); drain(job,4); JobStep selected=job.getSelectedJobStep();
        assertThat(selected.getStatus()).isEqualTo(JobStepStatus.RUNNING); assertThat(job.getRobot().getStatus()).isEqualTo(RobotStatus.BUSY);
        ObjectNode body=body("UI_DISPLAYED",json.createObjectNode().put("prompt_key","HELLO"));
        var accepted=responses.respond(selected.getJobStepId(),"display-1",body,f.actor);
        assertThat(accepted.result().path("actor_account_id").asText()).isEqualTo(f.account.getAccountId().toString());
        assertThat(responses.respond(selected.getJobStepId(),"display-1",body,f.actor).duplicate()).isTrue();
        assertThatThrownBy(()->responses.respond(selected.getJobStepId(),"display-1",body("UI_DISPLAYED",json.createObjectNode().put("prompt_key","OTHER")),f.actor)).isInstanceOf(ConflictException.class);
        drain(job,4); assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(responses.respond(selected.getJobStepId(),"display-1",body,f.actor).responseId()).isEqualTo(accepted.responseId());
        assertThatThrownBy(()->responses.respond(selected.getJobStepId(),"new-key",body,f.actor)).isInstanceOf(BusinessRuleException.class);
    }

    @Test void rejectsWrongTypeUnknownAuthorityAndDifferentStaff() {
        Fixture f=fixture(); Step close=define(f,"CLOSE",StepType.INTERACT,1,interact("CONFIRM_COMPARTMENT_CLOSED","CONFIRM"));
        f.workflow.setStartStep(close); terminal(f,close,TransitionOutcome.SUCCESS,TerminalStatus.SUCCEEDED);
        Job job=runtime(f); drain(job,4); UUID id=job.getSelectedJobStep().getJobStepId();
        assertThatThrownBy(()->responses.respond(id,"wrong",body("LOCK_CHECKED",json.createObjectNode().put("locked",true)),f.actor)).isInstanceOf(BusinessRuleException.class);
        var forged=body("DOOR_CLOSED",json.createObjectNode().put("closed",true).put("verified",true));
        assertThatThrownBy(()->responses.respond(id,"forged",forged,f.actor)).isInstanceOf(BusinessRuleException.class);
        var other=new AccountPrincipal(UUID.randomUUID(),"other","unused",Role.STAFF,AccountStatus.ACTIVE,0,null,null,null);
        assertThatThrownBy(()->responses.respond(id,"other",body("DOOR_CLOSED",json.createObjectNode().put("closed",true)),other)).isInstanceOf(AccessDeniedException.class);
        assertThat(job.getSelectedJobStep().getStatus()).isEqualTo(JobStepStatus.RUNNING);
    }

    @Test void negativeCloseConfirmationCompletesAndGraphChoosesFailure() {
        Fixture f=fixture(); Step close=define(f,"CLOSE",StepType.INTERACT,1,interact("CLOSE_SENDER","CONFIRM"));
        f.workflow.setStartStep(close);
        routes.save(StepTransition.builder().workflow(f.workflow).fromStep(close).outcome(TransitionOutcome.SUCCESS).priority(1)
                .conditionJson(condition("/result/closed","EQ",true)).terminalStatus(TerminalStatus.SUCCEEDED).build());
        terminal(f,close,TransitionOutcome.SUCCESS,TerminalStatus.FAILED,2);
        Job job=runtime(f); drain(job,4);
        var result=responses.respond(job.getSelectedJobStep().getJobStepId(),"negative",body("DOOR_CLOSED",json.createObjectNode().put("closed",false)),f.actor);
        assertThat(result.result().path("confirmed").asBoolean()).isFalse(); drain(job,4);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED); assertThat(job.getSelectedJobStep().getStatus()).isEqualTo(JobStepStatus.SUCCEEDED);
    }

    @Test void refusesLateCallbackThenResolvesConfiguredTimeoutRoute() {
        Fixture f=fixture(); Step display=define(f,"DISPLAY",StepType.INTERACT,1,interact("HELLO","DISPLAY"));
        f.workflow.setStartStep(display); terminal(f,display,TransitionOutcome.TIMEOUT,TerminalStatus.FAILED);
        Job job=runtime(f); drain(job,4); job.getSelectedJobStep().setStartedAt(Instant.now().minusSeconds(120));
        assertThatThrownBy(()->responses.respond(job.getSelectedJobStep().getJobStepId(),"late",body("UI_DISPLAYED",json.createObjectNode().put("prompt_key","HELLO")),f.actor)).isInstanceOf(BusinessRuleException.class);
        wakeups.request(job); drain(job,4); assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getSelectedJobStep().getStatus()).isEqualTo(JobStepStatus.TIMED_OUT);
    }

    @Test void eventArrivingBeforeWaitIsConsumedOnceAndResumesWorkflow() {
        Fixture f=fixture(); Step display=define(f,"DISPLAY",StepType.INTERACT,1,interact("HELLO","DISPLAY"));
        Step wait=define(f,"WAIT",StepType.WAIT,2,json.createObjectNode().put("wait_type","EVENT").put("event_type","READY").put("correlation_key","ready-1"));
        f.workflow.setStartStep(display); route(f,display,wait,null,1); terminal(f,wait,TransitionOutcome.SUCCESS,TerminalStatus.SUCCEEDED);
        Job job=runtime(f); drain(job,4); UUID eventId=UUID.randomUUID();
        var request=new WorkflowEventRequest(eventId,"READY","ready-1",Instant.now(),json.createObjectNode().put("note","ready"));
        assertThat(workflowEvents.publish(job.getJobId(),request,f.actor,metadata()).consumedByJobStepId()).isNull();
        responses.respond(job.getSelectedJobStep().getJobStepId(),"shown",body("UI_DISPLAYED",json.createObjectNode().put("prompt_key","HELLO")),f.actor);
        drain(job,12); assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(step(job,"WAIT").getResultJson().path("event").path("event_type").asText()).isEqualTo("READY");
        assertThat(events.findById(eventId).orElseThrow().getConsumedByJobStep().getJobStepId()).isEqualTo(step(job,"WAIT").getJobStepId());
        assertThat(workflowEvents.publish(job.getJobId(),request,f.actor,metadata()).duplicate()).isTrue();
    }

    @Test void durationPiSnapshotRemainsPendingAndNeverUsesLegacyDispatch() {
        Fixture f=fixture(); Step wait=define(f,"WAIT",StepType.WAIT,1,json.createObjectNode().put("wait_type","DURATION").put("duration_sec",5));
        f.workflow.setStartStep(wait); terminal(f,wait,TransitionOutcome.SUCCESS,TerminalStatus.SUCCEEDED);
        assertThatThrownBy(()->readiness.requireReady(f.workflow)).hasMessageContaining("MQTT dispatch");
        Job job=runtime(f); drain(job,4);
        assertThat(job.getSelectedJobStep().getStatus()).isEqualTo(JobStepStatus.PENDING);
        assertThat(job.getSelectedJobStep().getResolvedInputsJson().path("duration_sec").asInt()).isEqualTo(5);
        f.workflow.setDefaultInputsJson(json.createObjectNode().put("duration_sec",999)); wakeups.request(job); drain(job,4);
        assertThat(job.getSelectedJobStep().getResolvedInputsJson().path("duration_sec").asInt()).isEqualTo(5);
        assertThat(pi.nextCommand(new DevicePrincipal(f.robot.getRobotId(),f.robot.getRobotCode(),0,"test",Instant.now().plusSeconds(600)))).isEmpty();
    }

    @Test void workflowDurationDeadlineFailsLocallyWhenNoPiCommandWasPublished() {
        Fixture f=fixture(); Step wait=define(f,"WAIT",StepType.WAIT,1,json.createObjectNode().put("wait_type","DURATION").put("duration_sec",5));
        f.workflow.setStartStep(wait); Job job=runtime(f); drain(job,3); job.setStartedAt(Instant.now().minusSeconds(1000));
        wakeups.request(job); drain(job,3); assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getSelectedJobStep().getStatus()).isEqualTo(JobStepStatus.TIMED_OUT);
        assertThat(job.getRobot().getStatus()).isEqualTo(RobotStatus.IDLE);
    }

    @Test void requestCreationRegistersDurableWorkAndServerSubsetCanBePublished() {
        Fixture f=fixture(); Step init=define(f,"INIT",StepType.SET_CONTEXT,1,set("ready",true)); f.workflow.setStartStep(init);
        terminal(f,init,TransitionOutcome.SUCCESS,TerminalStatus.SUCCEEDED);
        terminal(f,init,TransitionOutcome.FAILURE,TerminalStatus.FAILED); terminal(f,init,TransitionOutcome.TIMEOUT,TerminalStatus.FAILED);
        f.workflow.setServiceType("INFORMATION"); f.workflow.setVersionNo(1000);
        workflowService.validate(f.workflow.getWorkflowId(),f.account.getAccountId(),metadata());
        workflowService.publish(f.workflow.getWorkflowId(),f.account.getAccountId(),metadata());
        readiness.requireReady(f.workflow);
        ServiceRequest request=requests.saveAndFlush(ServiceRequest.builder().requestedByAccount(f.account).requestedRobot(f.robot)
                .requestCode("REQ-"+UUID.randomUUID()).requestType(ServiceRequestType.INFORMATION).sourceType(RequestSourceType.STAFF_UI)
                .status(ServiceRequestStatus.QUEUED).priority(50).requestDataJson(json.createObjectNode()).idempotencyKey(UUID.randomUUID().toString()).requestedAt(Instant.now()).build());
        Job job=execution.createForRequest(request);
        assertThat(job.isBeOrchestrated()).isTrue(); assertThat(job.getDispatchCommandId()).isNull(); assertThat(work.existsById(job.getJobId())).isTrue();
        drain(job,8); assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED); assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.COMPLETED);
    }

    @Test void refusesInvalidWorkflowOutputReferencesBeforePublish() {
        Fixture f=fixture();Step init=define(f,"INIT",StepType.SET_CONTEXT,1,set("ready",true));f.workflow.setStartStep(init);
        terminal(f,init,TransitionOutcome.SUCCESS,TerminalStatus.SUCCEEDED);terminal(f,init,TransitionOutcome.FAILURE,TerminalStatus.FAILED);terminal(f,init,TransitionOutcome.TIMEOUT,TerminalStatus.FAILED);
        f.workflow.setOutputMappingJson(json.createObjectNode().set("output",json.createObjectNode().put("$ref","/outputs/UNKNOWN/value")));
        assertThatThrownBy(()->workflowService.validate(f.workflow.getWorkflowId(),f.account.getAccountId(),metadata())).hasMessageContaining("unknown step output reference");
        f.workflow.setOutputMappingJson(json.createObjectNode().set("output",json.createObjectNode().put("$ref","vars/ready")));
        assertThatThrownBy(()->workflowService.validate(f.workflow.getWorkflowId(),f.account.getAccountId(),metadata())).hasMessageContaining("$ref");
    }

    @Test void recoveryRequiredBlocksAllNextStepsAndKeepsRobot() {
        Fixture f=fixture(); Step display=define(f,"DISPLAY",StepType.INTERACT,1,interact("HELLO","DISPLAY"));
        f.workflow.setStartStep(display); Job job=runtime(f); drain(job,4);
        job.getSelectedJobStep().setStatus(JobStepStatus.RECOVERY_REQUIRED); wakeups.request(job); drain(job,4);
        assertThat(job.getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED); assertThat(job.getRobot().getStatus()).isEqualTo(RobotStatus.ERROR);
    }

    @Test void verificationResumesPayloadButCannotReplaceAuthorizedHardwareHandover() {
        Fixture f=fixture(); ServiceRequest request=deliveryRequest(f);
        Step authenticate=define(f,"AUTH",StepType.AUTHENTICATE,1,json.createObjectNode().put("purpose","DELIVERY_SENDER").put("service_request_id",request.getServiceRequestId().toString()));
        var items=json.createArrayNode().add(json.createObjectNode().put("item_code","MED-1").put("quantity",2));
        Step payload=define(f,"LOAD",StepType.HANDLE_PAYLOAD,2,json.createObjectNode().put("action","LOAD").put("service_request_id",request.getServiceRequestId().toString()).put("compartment_id","CART_MAIN").set("items",items));
        Step close=define(f,"CLOSE",StepType.INTERACT,3,interact("CLOSE_SENDER","CONFIRM"));
        Step verify=define(f,"VERIFY",StepType.INTERACT,4,interact("VERIFY_SENDER","CONFIRM"));
        f.workflow.setStartStep(authenticate); route(f,authenticate,payload,null,1);route(f,payload,close,null,1);route(f,close,verify,null,1);terminal(f,verify,TransitionOutcome.SUCCESS,TerminalStatus.SUCCEEDED);
        Job job=runtime(f); job.setServiceRequest(request); job.setInputJson(json.createObjectNode().put("service_request_id",request.getServiceRequestId().toString()).set("items",items));drain(job,4);
        UUID authId=job.getSelectedJobStep().getJobStepId();
        assertThatThrownBy(()->responses.respond(authId,"forge",body("INTERACTION_CONFIRMED",json.createObjectNode().put("confirmed",true)),f.actor)).hasMessageContaining("verification APIs");
        auth.create(job.getJobId(),new com.mediassistrover.backend.dto.verification.request.AuthVerificationRequests.Create(authId,AuthVerificationMethod.STAFF_CONFIRMATION,AuthVerificationPurpose.DELIVERY_SENDER,300),f.actor,metadata());
        assertThat(step(job,"AUTH").getResultJson().path("actor_account_id").asText()).isEqualTo(f.account.getAccountId().toString());drain(job,4);
        assertThat(job.getSelectedJobStep().getStepCodeSnapshot()).isEqualTo("LOAD");
        var wrong=json.createArrayNode().add(json.createObjectNode().put("item_code","MED-1").put("quantity",1));
        assertThatThrownBy(()->responses.respond(job.getSelectedJobStep().getJobStepId(),"wrong-cargo",body("PAYLOAD_CONFIRMED",json.createObjectNode().put("confirmed",true).set("items",wrong)),f.actor)).hasMessageContaining("cargo snapshot");
        assertThatThrownBy(()->responses.respond(job.getSelectedJobStep().getJobStepId(),"cargo",body("PAYLOAD_CONFIRMED",json.createObjectNode().put("confirmed",true).set("items",items)),f.actor))
                .hasMessageContaining("DeliveryRequest");
        assertThat(job.getSelectedJobStep().getStatus()).isEqualTo(JobStepStatus.RUNNING);
        assertThat(step(job,"CLOSE").getSelectedAt()).isNull();
    }

    @Test void qrProofCompletesSchema3AuthenticationAndResumesTheJob() {
        Fixture f=fixture();ServiceRequest request=deliveryRequest(f);
        Step authenticate=define(f,"AUTH",StepType.AUTHENTICATE,1,json.createObjectNode().put("purpose","DELIVERY_RECEIVER").put("service_request_id",request.getServiceRequestId().toString()));
        f.workflow.setStartStep(authenticate);terminal(f,authenticate,TransitionOutcome.SUCCESS,TerminalStatus.SUCCEEDED);
        Job job=runtime(f);job.setServiceRequest(request);drain(job,4);
        var challenge=qr.issue(job.getJobId(),new com.mediassistrover.backend.dto.verification.request.QrVerificationRequests.Issue(job.getSelectedJobStep().getJobStepId(),AuthVerificationPurpose.DELIVERY_RECEIVER,60),f.actor,metadata());
        qr.verify(job.getJobId(),challenge.proof(),f.actor,metadata());drain(job,4);
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);assertThat(job.getSelectedJobStep().getResultJson().path("service_request_id").asText()).isEqualTo(request.getServiceRequestId().toString());
    }

    @Test void cancellationOfServerWaitStopsGraphWithoutAcceptingAnotherCallback() {
        Fixture f=fixture(); Step display=define(f,"DISPLAY",StepType.INTERACT,1,interact("HELLO","DISPLAY"));
        f.workflow.setStartStep(display); Job job=runtime(f); drain(job,4);
        jobService.cancel(job.getJobId(),new com.mediassistrover.backend.dto.job.request.JobRequests.CancelJobRequest("stop"),f.actor,metadata());
        drain(job,4); assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThatThrownBy(()->responses.respond(job.getSelectedJobStep().getJobStepId(),"late",body("UI_DISPLAYED",json.createObjectNode().put("prompt_key","HELLO")),f.actor)).isInstanceOf(BusinessRuleException.class);
    }

    @Test void committedWorkSurvivesRollbackAndHttpAndSseUsePersistedState() throws Exception {
        Fixture f=fixture(); Step rejected=define(f,"REJECTED",StepType.SET_CONTEXT,1,increment("missing",1));
        Step display=define(f,"DISPLAY",StepType.INTERACT,2,interact("HELLO","DISPLAY"));
        f.workflow.setStartStep(rejected);terminal(f,display,TransitionOutcome.SUCCESS,TerminalStatus.SUCCEEDED);
        routes.save(StepTransition.builder().workflow(f.workflow).fromStep(rejected).toStep(display).outcome(TransitionOutcome.FAILURE).priority(1).defaultTransition(true).build());
        Job job=runtime(f); UUID id=job.getJobId();
        String token=jwt.issueAccessToken(f.account).value();
        org.springframework.test.context.transaction.TestTransaction.flagForCommit();
        org.springframework.test.context.transaction.TestTransaction.end();
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try {
            // A later worker invocation reads committed work and freezes the instruction before execution.
            orchestrator.advance(id);
            assertThatThrownBy(()->orchestrator.advance(id)).hasMessageContaining("INCREMENT requires numeric");
            UUID rejectedId=jobs.findById(id).orElseThrow().getSelectedJobStep().getJobStepId();
            assertThat(steps.findById(rejectedId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.PENDING);
            assertThat(steps.findById(rejectedId).orElseThrow().getStartedAt()).isNull();
            orchestrator.reject(id,"INCREMENT requires numeric existing and assigned values");
            orchestrator.advance(id);orchestrator.advance(id);
            UUID stepId=jobs.findById(id).orElseThrow().getSelectedJobStep().getJobStepId();
            var stream=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/jobs/"+id+"/stream")
                    .header("Authorization","Bearer "+token))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
            assertThat(stream.getResponse().getContentAsString()).contains("event:job-state","HELLO","selectedJobStepId");
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/jobs/"+id+"/stream"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
            var body=body("UI_DISPLAYED",json.createObjectNode().put("prompt_key","HELLO"));
            long requested=work.findById(id).orElseThrow().getRequestedGeneration();
            tx.executeWithoutResult(status->{responses.respond(stepId,"durable-key",body,f.actor);status.setRollbackOnly();});
            assertThat(steps.findById(stepId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.RUNNING);
            assertThat(work.findById(id).orElseThrow().getRequestedGeneration()).isEqualTo(requested);
            Boolean recorded=tx.execute(status->responseRecords.findByJobStepJobStepIdAndIdempotencyKey(stepId,"durable-key").isPresent());
            assertThat(recorded).isFalse();
            try (var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var start=new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Callable<JobStepResponseService.Receipt> callback=()->{start.await();return responses.respond(stepId,"durable-key",body,f.actor);};
                var first=pool.submit(callback);var second=pool.submit(callback);start.countDown();
                var receipts=List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS));
                assertThat(receipts).extracting(JobStepResponseService.Receipt::responseId).containsOnly(receipts.getFirst().responseId());
                assertThat(receipts).extracting(JobStepResponseService.Receipt::duplicate).containsExactlyInAnyOrder(false,true);
            }
            assertThat(work.findById(id).orElseThrow().getRequestedGeneration()).isEqualTo(requested+1);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/job-steps/"+stepId+"/responses")
                    .header("Authorization","Bearer "+token).header("Idempotency-Key","durable-key")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.duplicate").value(true));
            orchestrator.advance(id);
            assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
            assertThat(stream.getResponse().getContentAsString()).contains("SUCCEEDED");
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/job-steps/"+stepId+"/responses")
                    .header("Authorization","Bearer "+token).header("Idempotency-Key","durable-key")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.duplicate").value(true));
            tx.executeWithoutResult(status->accounts.findById(f.account.getAccountId()).orElseThrow().setStatus(AccountStatus.INACTIVE));
            uiEvents.heartbeat();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(stream))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        } finally {
            tx.executeWithoutResult(status->{
                Job stored=jobs.findById(id).orElseThrow(); stored.setSelectedJobStep(null); jobs.flush();
                var runtimeSteps=steps.findByJobId(id);
                responseRecords.deleteAll(responseRecords.findAll().stream().filter(r->r.getJobStep().getJobTask().getJob().getJobId().equals(id)).toList());responseRecords.flush();
                events.deleteAll(events.findByJobJobId(id,org.springframework.data.domain.PageRequest.of(0,1000)).getContent());events.flush();
                work.deleteById(id);work.flush();steps.deleteAll(runtimeSteps);steps.flush();
                jobTasks.deleteAll(jobTasks.findByJobJobIdOrderBySequenceNoAsc(id));jobTasks.flush();jobs.deleteById(id);jobs.flush();
                Workflow w=workflows.findById(f.workflow.getWorkflowId()).orElseThrow();w.setStartStep(null);workflows.flush();
                routes.deleteAll(routes.findByWorkflowWorkflowIdOrderByFromStepSequenceNoAscOutcomeAscPriorityAsc(w.getWorkflowId()));routes.flush();
                definitions.deleteAll(definitions.findByWorkflowWorkflowIdOrderBySequenceNoAsc(w.getWorkflowId()));definitions.flush();
                tasks.deleteAll(tasks.findByWorkflowWorkflowIdOrderBySequenceNoAsc(w.getWorkflowId()));tasks.flush();workflows.delete(w);workflows.flush();
                robots.deleteById(f.robot.getRobotId());accounts.deleteById(f.account.getAccountId());
            });
        }
    }

    private Fixture fixture() {
        String code=UUID.randomUUID().toString();
        Account account=accounts.saveAndFlush(Account.builder().accountCode("A-"+code).username("u-"+code).fullName("Test Staff").email(code+"@example.test")
                .role(Role.STAFF).authProvider(AuthProvider.LOCAL).passwordHash("unused").status(AccountStatus.ACTIVE).passwordChangedAt(Instant.now()).build());
        Robot robot=robots.saveAndFlush(Robot.builder().robotCode("R-"+code).robotName("Test").model("Test").status(RobotStatus.IDLE).protocolVersion(1).capabilitiesJson(json.createArrayNode()).build());
        Workflow workflow=workflows.saveAndFlush(Workflow.builder().workflowCode("W-"+code).workflowName("Test").versionNo(1).serviceType("TEST").status(WorkflowStatus.DRAFT)
                .schemaVersion(3).graphVersion(3).maxDurationSec(600).maxTotalVisits(100).definitionHash("a".repeat(64)).compiledDefinitionJson(json.createObjectNode())
                .inputSchemaJson(json.createObjectNode().put("type","object")).inputUiJson(json.createObjectNode()).defaultInputsJson(json.createObjectNode())
                .outputMappingJson(json.createObjectNode()).requiredCapabilitiesJson(json.createArrayNode()).build());
        Task task=tasks.saveAndFlush(Task.builder().workflow(workflow).taskCode("TASK").taskName("Task").sequenceNo(1).configJson(json.createObjectNode()).build());
        return new Fixture(workflow,task,account,AccountPrincipal.fromAccount(account),robot);
    }
    private ServiceRequest deliveryRequest(Fixture f) {
        return requests.saveAndFlush(ServiceRequest.builder().requestedByAccount(f.account).requestedRobot(f.robot).requestCode("REQ-"+UUID.randomUUID())
                .requestType(ServiceRequestType.DELIVERY).sourceType(RequestSourceType.STAFF_UI).status(ServiceRequestStatus.QUEUED).priority(50)
                .requestDataJson(json.createObjectNode()).idempotencyKey(UUID.randomUUID().toString()).requestedAt(Instant.now()).build());
    }
    private Step define(Fixture f,String code,StepType type,int sequence,JsonNode params) {
        return definitions.saveAndFlush(Step.builder().workflow(f.workflow).task(f.task).stepCode(code).stepName(code).stepType(type).sequenceNo(sequence).enabled(true)
                .paramsJson(params).inputMappingJson(json.createObjectNode()).timeoutSec(60).maxAttempts(1).retryDelaySec(0).retryCodesJson(json.createArrayNode()).maxVisits(10).auditLevel(StepAuditLevel.FULL).build());
    }
    private Job runtime(Fixture f) {
        Job job=jobs.saveAndFlush(Job.builder().workflow(f.workflow).robot(f.robot).assignedStaffAccount(f.account).jobCode("J-"+UUID.randomUUID())
                .status(JobStatus.QUEUED).priority(50).schemaVersion(3).graphVersion(3).protocolVersion(1).beOrchestrated(true).workflowHash("a".repeat(64))
                .inputJson(json.createObjectNode()).contextJson(json.createObjectNode()).queuedAt(Instant.now()).build());
        JobTask task=jobTasks.saveAndFlush(JobTask.builder().job(job).task(f.task).sequenceNo(1).taskNameSnapshot("Task").configSnapshotJson(json.createObjectNode()).status(JobTaskStatus.PENDING).build());
        long sequence=0;
        for (Step def:definitions.findByWorkflowWorkflowIdOrderBySequenceNoAsc(f.workflow.getWorkflowId()))
            steps.save(JobStep.builder().jobTask(task).step(def).visitNo(1).executionSequenceNo(++sequence).stepCodeSnapshot(def.getStepCode()).stepTypeSnapshot(def.getStepType().name())
                    .status(JobStepStatus.PENDING).attemptNo(1).timeoutSecSnapshot(def.getTimeoutSec()).resolvedInputsJson(json.createObjectNode()).operationKey(job.getJobId()+":"+def.getStepCode()+":1").build());
        steps.flush(); wakeups.request(job); return job;
    }
    private void drain(Job job,int limit) { for(int i=0;i<limit;i++) orchestrator.advance(job.getJobId()); }
    private JobStep step(Job job,String code) { return steps.findByJobId(job.getJobId()).stream().filter(s->s.getStepCodeSnapshot().equals(code)).findFirst().orElseThrow(); }
    private ObjectNode set(String key,Object value) { return operation(key,"SET",value); }
    private ObjectNode increment(String key,Object value) { return operation(key,"INCREMENT",value); }
    private ObjectNode operation(String key,String op,Object value) { ObjectNode result=json.createObjectNode();result.putArray("operations").addObject().put("path","/vars/"+key).put("operation",op).set("value",json.valueToTree(value));return result; }
    private ObjectNode condition(String path,String op,Object value) { return json.createObjectNode().put("path",path).put("operator",op).set("value",json.valueToTree(value)); }
    private ObjectNode check(String path,String op,Object value) { return json.createObjectNode().set("expression",condition(path,op,value)); }
    private ObjectNode interact(String prompt,String action) { return json.createObjectNode().put("prompt_key",prompt).put("action",action).set("data",json.createObjectNode()); }
    private ObjectNode body(String type,JsonNode data) { return json.createObjectNode().put("responseType",type).set("data",data); }
    private void route(Fixture f,Step from,Step to,JsonNode condition,int priority) { routes.save(StepTransition.builder().workflow(f.workflow).fromStep(from).toStep(to).outcome(TransitionOutcome.SUCCESS).priority(priority).conditionJson(condition).defaultTransition(condition==null).build()); }
    private void terminal(Fixture f,Step from,TransitionOutcome outcome,TerminalStatus status) { terminal(f,from,outcome,status,1); }
    private void terminal(Fixture f,Step from,TransitionOutcome outcome,TerminalStatus status,int priority) { routes.save(StepTransition.builder().workflow(f.workflow).fromStep(from).outcome(outcome).priority(priority).defaultTransition(true).terminalStatus(status).build()); }
    private RequestMetadata metadata() { return new RequestMetadata("127.0.0.1","test","be-orchestrator"); }
    private record Fixture(Workflow workflow,Task task,Account account,AccountPrincipal actor,Robot robot) { }
}
