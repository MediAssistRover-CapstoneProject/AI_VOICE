package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.dto.pi.request.*;
import com.mediassistrover.backend.dto.pi.request.JobRuntimePayloads.*;
import com.mediassistrover.backend.dto.verification.request.AuthVerificationRequests;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.*;
import com.mediassistrover.backend.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

class RuntimeAuthorizationIntegrationTest extends MySqlIntegrationTest {
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JobRepository jobs;
    @Autowired JobTaskRepository tasks;
    @Autowired JobStepRepository steps;
    @Autowired StepRepository definitions;
    @Autowired WorkflowRepository workflows;
    @Autowired RobotRepository robots;
    @Autowired AccountRepository accounts;
    @Autowired AuthVerificationRepository grants;
    @Autowired PiJobRuntimeService runtime;
    @Autowired AuthVerificationService auth;
    UUID jobId, robotId, previousId, targetId;
    AccountPrincipal actor;
    DevicePrincipal device;

    @BeforeEach void setup() {
        clean();
        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            Account owner=accounts.save(Account.builder().accountCode("P15-STAFF").username("p15.staff")
                    .fullName("Staff").email("p15@example.test").role(Role.STAFF).status(AccountStatus.ACTIVE)
                    .authProvider(AuthProvider.LOCAL).passwordHash("unused").build());
            actor=AccountPrincipal.fromAccount(owner);
            Robot robot=robots.save(Robot.builder().robotCode("P15-ROBOT").robotName("Robot").model("Test")
                    .protocolVersion(1).capabilitiesJson(json.createArrayNode()).status(RobotStatus.BUSY)
                    .batteryUnit(BatteryUnit.UNKNOWN).build());
            robotId=robot.getRobotId();
            device=new DevicePrincipal(robotId,"P15-ROBOT",0,"device",Instant.now().plusSeconds(3600));
            Workflow workflow=workflows.findAll().stream().filter(w->w.getSchemaVersion()==2&&w.getWorkflowCode().equals("WF_PATIENT_ESCORT")).findFirst().orElseThrow();
            Job job=jobs.save(Job.builder().workflow(workflow).robot(robot).assignedStaffAccount(owner)
                    .jobCode("P15-JOB").status(JobStatus.RUNNING).priority(1).inputJson(json.createObjectNode())
                    .contextJson(json.createObjectNode()).workflowHash("a".repeat(64)).protocolVersion(1).schemaVersion(2)
                    .graphVersion(3).queuedAt(Instant.now()).build());
            jobId=job.getJobId();
            Step previous=definitions.findAll().stream().filter(s->s.getWorkflow().getWorkflowId().equals(workflow.getWorkflowId())&&s.getStepCode().equals("CONFIRM")).findFirst().orElseThrow();
            targetId=definitions.findAll().stream().filter(s->s.getWorkflow().getWorkflowId().equals(workflow.getWorkflowId())&&s.getStepCode().equals("DECIDE")).findFirst().orElseThrow().getStepId();
            JobTask task=tasks.save(JobTask.builder().job(job).task(previous.getTask()).sequenceNo(1)
                    .taskNameSnapshot("MAIN").configSnapshotJson(json.createObjectNode()).status(JobTaskStatus.RUNNING).build());
            previousId=steps.save(JobStep.builder().jobTask(task).step(previous).visitNo(1).executionSequenceNo(1)
                    .stepCodeSnapshot(previous.getStepCode()).stepTypeSnapshot(previous.getStepType().name())
                    .resolvedInputsJson(previous.getParamsJson()).resultJson(json.createObjectNode().put("confirmed",true))
                    .status(JobStepStatus.SUCCEEDED).attemptNo(1).operationKey("p15-previous").timeoutSecSnapshot(60).endedAt(Instant.now()).build()).getJobStepId();
        });
    }
    @AfterEach void cleanup(){clean();}

    @Test void concurrentDuplicateVisitCreatesOneStepAndOneEvent() throws Exception {
        var ids=race(() -> runtime.createStepVisit(jobId,targetId,visit("p15-visit"),device).jobStepId());
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(steps.countByJobTaskJobJobId(jobId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM JobEvent WHERE event_type='JOB_STEP_VISIT_CREATED'",Long.class)).isEqualTo(1);
    }

    @Test void differentKeysCannotCreateTwoExecutableVisits() throws Exception {
        var outcomes=race(() -> {
            try {runtime.createStepVisit(jobId,targetId,visit(UUID.randomUUID().toString()),device);return "created";}
            catch(BusinessRuleException ex){return "rejected";}
        });
        assertThat(outcomes).containsExactlyInAnyOrder("created","rejected");
        assertThat(steps.countByJobTaskJobJobId(jobId)).isEqualTo(2);
    }

    @Test void terminalJobRejectsNewVisitButExistingVisitCanBeReadIdempotently() {
        var first=runtime.createStepVisit(jobId,targetId,visit("p15-visit"),device);
        new TransactionTemplate(txManager).executeWithoutResult(tx -> jobs.findById(jobId).orElseThrow().setStatus(JobStatus.CANCELLED));
        assertThat(runtime.createStepVisit(jobId,targetId,visit("p15-visit"),device).jobStepId()).isEqualTo(first.jobStepId());
        assertThatThrownBy(()->runtime.createStepVisit(jobId,targetId,visit("fresh"),device)).isInstanceOf(BusinessRuleException.class);
    }

    @Test void concurrentGrantRequestsReturnOneGrantAndAuditedActor() throws Exception {
        prepareAuthentication();
        var ids=race(()->auth.create(jobId,grant(),actor,metadata()).verificationId());
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(grants.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM JobEvent WHERE event_type='AUTH_VERIFICATION_ISSUED' AND actor_account_id IS NOT NULL",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AuditLog WHERE action_code='AUTH_VERIFICATION_ISSUED'",Long.class)).isEqualTo(1);
    }

    @Test void deviceCannotForgeAuthenticationAndFailureCannotBypassIt() {
        prepareAuthentication();
        assertThatThrownBy(()->runtime.stepResult(previousId,envelope("STEP_RESULT","p15-previous",new StepResultPayload(json.createObjectNode().put("verified",true))),device)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->runtime.stepError(previousId,envelope("STEP_ERROR","p15-previous",new StepErrorPayload("BYPASS",json.createObjectNode(),false)),device)).isInstanceOf(AccessDeniedException.class);
        assertThat(steps.findById(previousId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.RUNNING);
        assertThat(grants.count()).isZero();
    }

    @Test void patientInteractionCallbackRemainsValidAndIdempotent() {
        new TransactionTemplate(txManager).executeWithoutResult(tx -> {JobStep s=steps.findById(previousId).orElseThrow();s.setStatus(JobStepStatus.RUNNING);s.setResultJson(null);s.setEndedAt(null);});
        com.fasterxml.jackson.databind.node.ObjectNode result=json.createObjectNode().put("completed",true).putNull("confirmed");result.set("input_data",json.createObjectNode());result.putNull("actor_user_id");
        var message=envelope("STEP_RESULT","p15-previous",new StepResultPayload(result));
        assertThat(runtime.stepResult(previousId,message,device).duplicate()).isFalse();
        assertThat(runtime.stepResult(previousId,message,device).duplicate()).isTrue();
        assertThat(steps.findById(previousId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.SUCCEEDED);
    }

    private void prepareAuthentication(){
        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            Step authStep=definitions.findAll().stream().filter(s->s.getStepCode().equals("AUTH_SENDER")).findFirst().orElseThrow();
            JobStep step=steps.findById(previousId).orElseThrow();step.setStep(authStep);step.setStepCodeSnapshot("AUTH_SENDER");
            step.getJobTask().setTask(authStep.getTask());step.getJobTask().getJob().setWorkflow(authStep.getWorkflow());
            step.setStepTypeSnapshot("AUTHENTICATE");step.setResolvedInputsJson(authStep.getParamsJson());
            step.setStatus(JobStepStatus.RUNNING);step.setResultJson(null);step.setEndedAt(null);
            // Isolated authorization fixture, not a full delivery acceptance test.
        });
    }
    private AuthVerificationRequests.Create grant(){return new AuthVerificationRequests.Create(previousId,AuthVerificationMethod.STAFF_CONFIRMATION,AuthVerificationPurpose.DELIVERY_SENDER,300);}
    private RequestMetadata metadata(){return new RequestMetadata("127.0.0.1","test","phase15");}
    private PiEnvelope<CreateStepVisitPayload> visit(String key){return envelope("STEP_VISIT_CREATE",key,new CreateStepVisitPayload(previousId));}
    private <T> PiEnvelope<T> envelope(String type,String key,T payload){return new PiEnvelope<>("1.0",UUID.randomUUID(),key,UUID.randomUUID(),robotId,jobId,previousId,type,Instant.now(),UUID.randomUUID(),1,payload);}
    private <T> List<T> race(Supplier<T> action) throws Exception {
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<T> call=()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Barrier timeout");return action.get();};
            Future<T> a=pool.submit(call),b=pool.submit(call);
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
        }
    }
    private void clean(){jdbc.execute("SET FOREIGN_KEY_CHECKS=0");try{for(String t:List.of("AuthVerification","JobEvent","JobStep","JobTask","Job","Robot","AuditLog","Account"))jdbc.execute("TRUNCATE TABLE `"+t+"`");}finally{jdbc.execute("SET FOREIGN_KEY_CHECKS=1");}}
}
