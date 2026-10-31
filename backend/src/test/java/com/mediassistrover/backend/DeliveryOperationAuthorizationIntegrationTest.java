package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.exception.LegacyExecutionRetiredException;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.*;
import com.mediassistrover.backend.dto.verification.request.AuthVerificationRequests.Create;
import com.mediassistrover.backend.dto.verification.request.QrVerificationRequests.Issue;
import com.mediassistrover.backend.mqtt.MqttStepDispatchService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** BE component tests. Future STAFF_VERIFIED evidence below is seeded, not claimed to be accepted from current Pi. */
@Transactional
class DeliveryOperationAuthorizationIntegrationTest extends MySqlIntegrationTest {
    @Autowired EntityManager em;
    @Autowired ObjectMapper json;
    @Autowired JobStepInputResolver resolver;
    @Autowired OperationAuthorizationService authorization;
    @Autowired RobotOperationAuthorizationRepository bindings;
    @Autowired RobotCommandOutboxRepository commands;
    @Autowired AuthVerificationService verification;
    @Autowired AuthVerificationRepository verifications;
    @Autowired QrVerificationService qr;
    @Autowired JobStepResponseService responses;
    @Autowired JobStepResponseRepository receipts;
    @Autowired JobStepRepository steps;
    @Autowired DeliveryService deliveryApi;
    @Autowired MqttStepDispatchService dispatch;
    @Autowired com.mediassistrover.backend.validation.StepResultContractValidator outputs;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired WorkflowOrchestrationWorkRepository work;
    MqttTestFixture mqttFixture;
    Job job;JobTask task;DeliveryRequest delivery;Account account;AccountPrincipal actor;long sequence;
    final RequestMetadata metadata=new RequestMetadata("127.0.0.1","test","phase10");

    void fixture() {
        MqttTestFixture f=MqttTestFixture.create(em,json,StepType.MOVE,null);
        mqttFixture=f;
        job=em.find(Job.class,f.jobId);task=em.find(JobStep.class,f.piStepId).getJobTask();account=em.find(Account.class,f.accountId);
        actor=AccountPrincipal.fromAccount(account);job.setAssignedStaffAccount(account);job.setStatus(JobStatus.RUNNING);
        job.setStartedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));job.getRobot().setStatus(RobotStatus.BUSY);
        ServiceRequest request=ServiceRequest.builder().requestCode("D-"+UUID.randomUUID()).requestedByAccount(account)
                .requestType(ServiceRequestType.DELIVERY).sourceType(RequestSourceType.STAFF_UI).priority(50).status(ServiceRequestStatus.IN_PROGRESS)
                .requestDataJson(json.createObjectNode()).idempotencyKey(UUID.randomUUID().toString()).requestedAt(Instant.now()).build();em.persist(request);
        job.setServiceRequest(request);
        delivery=DeliveryRequest.builder().serviceRequest(request).map(job.getMap()).pickupEndpoint(em.find(Endpoint.class,f.endpointId))
                .deliveryEndpoint(em.find(Endpoint.class,f.endpointId)).targetMode(TargetMode.ENDPOINT).recipientName("Recipient").requiresConfirmation(true).build();em.persist(delivery);
        job.setInputJson(json.createObjectNode().put("service_request_id",request.getServiceRequestId().toString())
                .set("items",json.createArrayNode().add(json.createObjectNode().put("item_code","MED-1").put("quantity",2))));
        sequence=10;em.flush();
    }
    JobStep select(StepType type,ObjectNode params) {
        String code="S"+(++sequence);
        Step definition=Step.builder().workflow(job.getWorkflow()).task(task.getTask()).stepCode(code).stepName(code).stepType(type).sequenceNo((int)sequence)
                .enabled(true).paramsJson(params.deepCopy()).inputMappingJson(json.createObjectNode()).timeoutSec(120).maxAttempts(1).maxVisits(3)
                .retryDelaySec(0).retryCodesJson(json.createArrayNode()).auditLevel(StepAuditLevel.FULL).build();em.persist(definition);
        JobStep step=JobStep.builder().step(definition).jobTask(task).visitNo(1).executionSequenceNo(sequence).stepCodeSnapshot(code).stepTypeSnapshot(type.name())
                .status(JobStepStatus.PENDING).attemptNo(1).timeoutSecSnapshot(120).operationKey(job.getJobId()+":"+code+":1")
                .selectedAt(Instant.now()).resolvedInputsJson(params.deepCopy()).build();em.persist(step);job.setSelectedJobStep(step);em.flush();return step;
    }
    AuthVerification authenticate(AuthVerificationPurpose purpose) {
        JobStep auth=select(StepType.AUTHENTICATE,json.createObjectNode().put("purpose",purpose.name()).put("service_request_id",delivery.getServiceRequestId().toString()));
        running(auth);
        var result=verification.create(job.getJobId(),new Create(auth.getJobStepId(),AuthVerificationMethod.STAFF_CONFIRMATION,purpose,300),actor,metadata);
        return verifications.findById(result.verificationId()).orElseThrow();
    }
    ObjectNode grant(AuthVerification v) {return json.createObjectNode().put("mode","STAFF_VERIFIED").put("verification_id",v.getVerificationId().toString())
            .put("actor_account_id",account.getAccountId().toString()).put("purpose",v.getPurpose().name()).put("valid_until",v.getValidUntil().toString());}
    JobStep unlock(AuthVerification v) {
        JobStep step=select(StepType.CONTROL_HARDWARE,json.createObjectNode().put("action","UNLOCK_COMPARTMENT").put("compartment_id","CART_MAIN").set("authorization",grant(v)));
        step.setResolvedInputsJson(resolver.resolve(step));return step;
    }
    void running(JobStep step) {step.setStatus(JobStepStatus.RUNNING);step.setStartedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));}
    void physical(JobStep step,String mode,boolean stopped) {
        // Proposed future Pi evidence: the current wire validator deliberately still rejects STAFF_VERIFIED output.
        boolean unlock="UNLOCK_COMPARTMENT".equals(step.getResolvedInputsJson().path("action").asText());
        step.setStatus(JobStepStatus.SUCCEEDED);step.setStartedAt(Instant.now());step.setEndedAt(Instant.now());
        step.setResultJson(json.createObjectNode().put("action",unlock?"UNLOCK_COMPARTMENT":"LOCK_COMPARTMENT").put("compartment_id","CART_MAIN")
                .put("commanded_state",unlock?"UNLOCK":"LOCK").put("published",true).put("controller_ack",false).put("verified",false)
                .put("ros_servo_id",4).put("position_raw",unlock?2400:1500).put("authorization_mode",mode));
        em.persist(RobotCommandOutbox.builder().commandId(UUID.randomUUID()).robotId(job.getRobot().getRobotId()).jobId(job.getJobId())
                .jobStepId(step.getJobStepId()).operationKey(step.getOperationKey()).payloadJson(json.createObjectNode().set("step",json.createObjectNode().set("params",step.getResolvedInputsJson().deepCopy())))
                .state("COMPLETED").physicalStopConfirmed(stopped).sendAttempts(1).createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(30)).build());em.flush();
    }
    JobStep payload(boolean sender) {
        JobStep step=select(StepType.HANDLE_PAYLOAD,json.createObjectNode().put("action",sender?"LOAD":"UNLOAD")
                .put("compartment_id","CART_MAIN").put("service_request_id",delivery.getServiceRequestId().toString()).set("items",job.getInputJson().get("items")));
        running(step);return step;
    }
    JobStep instruction(String prompt) {JobStep s=select(StepType.INTERACT,json.createObjectNode().put("prompt_key",prompt).put("action","CONFIRM").set("data",json.createObjectNode()));running(s);return s;}
    JobStepResponseService.Receipt respond(JobStep step,String type,ObjectNode data,String key) {
        return responses.respond(step.getJobStepId(),key,json.createObjectNode().put("responseType",type).set("data",data),actor);
    }
    void cargo(JobStep step,boolean confirmed) {respond(step,"PAYLOAD_CONFIRMED",json.createObjectNode().put("confirmed",confirmed).set("items",job.getInputJson().get("items")),"cargo");}
    JobStep lock() {JobStep s=select(StepType.CONTROL_HARDWARE,json.createObjectNode().put("action","LOCK_COMPARTMENT").put("compartment_id","CART_MAIN"));physical(s,"DEVICE_COMMAND",true);return s;}
    void stage(boolean sender) {
        physical(unlock(authenticate(sender?AuthVerificationPurpose.DELIVERY_SENDER:AuthVerificationPurpose.DELIVERY_RECEIVER)),"STAFF_VERIFIED",true);
        cargo(payload(sender),true);
        assertThat(sender?delivery.getPickedUpAt():delivery.getDeliveredAt()).isNull();
        respond(instruction(sender?"CLOSE_SENDER":"CLOSE_RECEIVER"),"DOOR_CLOSED",json.createObjectNode().put("closed",true),"door");
        JobStep lock=lock();assertThat(sender?delivery.getPickedUpAt():delivery.getDeliveredAt()).isNull();
        JobStep check=instruction(sender?"VERIFY_SENDER":"VERIFY_RECEIVER");
        respond(check,"LOCK_CHECKED",json.createObjectNode().put("locked",true),"check");
        assertThat(lock.getResultJson().path("verified").asBoolean()).isFalse();
        assertThat(lock.getResultJson().path("controller_ack").asBoolean()).isFalse();
        outputs.validate(lock,lock.getResultJson());
        var repeated=respond(check,"LOCK_CHECKED",json.createObjectNode().put("locked",true),"check");assertThat(repeated.duplicate()).isTrue();
    }

    @Test void bindsOneVerificationToRobotStepAndOperationAndKeepsSnapshotStableAfterReload() {
        fixture();AuthVerification v=authenticate(AuthVerificationPurpose.DELIVERY_SENDER);JobStep step=unlock(v);
        UUID id=step.getJobStepId();JsonNode frozen=step.getResolvedInputsJson().deepCopy();
        assertThat(v.getUsedByJobStep().getJobStepId()).isEqualTo(id);
        ((ObjectNode)job.getContextJson()).put("changed",true);em.flush();em.clear();
        JobStep reloaded=steps.findById(id).orElseThrow();authorization.requireBound(reloaded,frozen.path("authorization"));
        assertThat(bindings.findByJobStepJobStepId(id).orElseThrow().getAuthorizationJson()).isEqualTo(frozen.path("authorization"));
    }
    @Test void rejectsExpiredRevokedAndForgedActorGrants() {
        fixture();AuthVerification v=authenticate(AuthVerificationPurpose.DELIVERY_SENDER);
        JobStep s=select(StepType.CONTROL_HARDWARE,json.createObjectNode().put("action","UNLOCK_COMPARTMENT").put("compartment_id","CART_MAIN").set("authorization",grant(v)));
        ObjectNode forged=grant(v).put("actor_account_id",UUID.randomUUID().toString());
        assertThatThrownBy(()->authorization.bind(s,forged)).hasMessageContaining("differs");
        v.setRevokedAt(Instant.now());assertThatThrownBy(()->authorization.bind(s,grant(v))).hasMessageContaining("revoked");
        v.setRevokedAt(null);v.setValidUntil(Instant.now().minusSeconds(1));assertThatThrownBy(()->authorization.bind(s,grant(v))).hasMessageContaining("expired");
        assertThat(bindings.findByJobStepJobStepId(s.getJobStepId())).isEmpty();
    }
    @Test void rejectsReuseOnAnotherOperationAndWrongPurpose() {
        fixture();AuthVerification v=authenticate(AuthVerificationPurpose.DELIVERY_SENDER);unlock(v);
        JobStep other=select(StepType.CONTROL_HARDWARE,json.createObjectNode().put("action","UNLOCK_COMPARTMENT").put("compartment_id","CART_MAIN").set("authorization",grant(v)));
        assertThatThrownBy(()->authorization.bind(other,grant(v))).hasMessageContaining("already");
        ((ObjectNode)other.getStep().getParamsJson().path("authorization")).put("purpose","DELIVERY_RECEIVER");
        assertThatThrownBy(()->authorization.bind(other,grant(v))).hasMessageContaining("purpose");
    }
    @Test void rejectsRobotReassignmentAndOperationKeyMutationAtDispatch() {
        fixture();JobStep step=unlock(authenticate(AuthVerificationPurpose.DELIVERY_SENDER));
        step.setOperationKey(step.getOperationKey()+":changed");assertThatThrownBy(()->authorization.requireBound(step,step.getResolvedInputsJson().path("authorization"))).hasMessageContaining("binding");
        step.setOperationKey(bindings.findByJobStepJobStepId(step.getJobStepId()).orElseThrow().getOperationKey());
        Robot other=Robot.builder().robotCode("R-"+UUID.randomUUID()).robotName("other").model("test").status(RobotStatus.IDLE).protocolVersion(1).capabilitiesJson(json.createArrayNode()).build();em.persist(other);
        job.setRobot(other);assertThatThrownBy(()->authorization.requireBound(step,step.getResolvedInputsJson().path("authorization"))).hasMessageContaining("binding");
    }
    @Test void invalidatesTheBoundGrantWhenTheVerificationSessionIsRevoked() {
        fixture();JobStep step=unlock(authenticate(AuthVerificationPurpose.DELIVERY_SENDER));account.setTokenVersion(account.getTokenVersion()+1);
        assertThatThrownBy(()->authorization.requireBound(step,step.getResolvedInputsJson().path("authorization"))).hasMessageContaining("no longer authorized");
    }
    @Test void receiverCannotUnlockBeforeThePickupMilestone() {
        fixture();AuthVerification receiver=authenticate(AuthVerificationPurpose.DELIVERY_RECEIVER);
        assertThatThrownBy(()->unlock(receiver)).hasMessageContaining("delivery milestone");
    }
    @Test void rejectsSupersededAuthenticationAndAnotherJobGrant() {
        fixture();AuthVerification old=authenticate(AuthVerificationPurpose.DELIVERY_SENDER);authenticate(AuthVerificationPurpose.DELIVERY_RECEIVER);
        JobStep step=select(StepType.CONTROL_HARDWARE,json.createObjectNode().put("action","UNLOCK_COMPARTMENT").put("compartment_id","CART_MAIN").set("authorization",grant(old)));
        assertThatThrownBy(()->authorization.bind(step,grant(old))).hasMessageContaining("superseded");
        old.setJob(em.find(Job.class,MqttTestFixture.create(em,json,StepType.WAIT,null).jobId));
        assertThatThrownBy(()->authorization.bind(step,grant(old))).hasMessageContaining("outside");
    }
    @Test void staffAndQrVerificationRejectStaleSessionsAndQrRejectsExpiredSelectedStep() {
        fixture();JobStep auth=select(StepType.AUTHENTICATE,json.createObjectNode().put("purpose","DELIVERY_SENDER").put("service_request_id",delivery.getServiceRequestId().toString()));running(auth);
        account.setTokenVersion(account.getTokenVersion()+1);
        assertThatThrownBy(()->verification.create(job.getJobId(),new Create(auth.getJobStepId(),AuthVerificationMethod.STAFF_CONFIRMATION,AuthVerificationPurpose.DELIVERY_SENDER,60),actor,metadata)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->qr.issue(job.getJobId(),new Issue(auth.getJobStepId(),AuthVerificationPurpose.DELIVERY_SENDER,60),actor,metadata)).isInstanceOf(AccessDeniedException.class);
        actor=AccountPrincipal.fromAccount(account);auth.setStartedAt(Instant.now().minusSeconds(121));
        assertThatThrownBy(()->qr.issue(job.getJobId(),new Issue(auth.getJobStepId(),AuthVerificationPurpose.DELIVERY_SENDER,60),actor,metadata)).hasMessageContaining("unexpired");
    }
    @Test void fullSenderAndReceiverMilestonesRequireAllSeparateStages() {
        fixture();stage(true);assertThat(delivery.getPickedUpAt()).isNotNull();assertThat(delivery.getPickupPayloadConfirmedAt()).isNotNull();
        stage(false);assertThat(delivery.getDeliveredAt()).isNotNull();assertThat(delivery.getDeliveryPayloadConfirmedAt()).isNotNull();
        assertThat(delivery.getDeliveryCompartmentConfirmedAt()).isEqualTo(delivery.getDeliveredAt());
    }
    @Test void cargoCannotCompleteWithCommissioningUnlockOrMissingStop() {
        fixture();JobStep unlock=unlock(authenticate(AuthVerificationPurpose.DELIVERY_SENDER));physical(unlock,"COMMISSIONING_ONLY",true);JobStep cargo=payload(true);
        assertThatThrownBy(()->cargo(cargo,true)).hasMessageContaining("Commissioning");
        assertThat(delivery.getPickupPayloadConfirmedAt()).isNull();
    }
    @Test void rejectsMissingPhysicalStopEvidence() {
        fixture();physical(unlock(authenticate(AuthVerificationPurpose.DELIVERY_SENDER)),"STAFF_VERIFIED",false);JobStep cargo=payload(true);
        assertThatThrownBy(()->cargo(cargo,true)).hasMessageContaining("stopped hardware");
    }
    @Test void commandExpiryCannotExtendTheAuthorizationWindow() {
        fixture();AuthVerification auth=authenticate(AuthVerificationPurpose.DELIVERY_SENDER);JobStep unlock=unlock(auth);physical(unlock,"STAFF_VERIFIED",true);
        commands.findByJobStepId(unlock.getJobStepId()).orElseThrow().setExpiresAt(auth.getValidUntil().plusSeconds(1));
        JobStep cargo=payload(true);assertThatThrownBy(()->cargo(cargo,true)).hasMessageContaining("authorization window");
        assertThat(delivery.getPickupPayloadConfirmedAt()).isNull();
    }
    @Test void anExpiredFrozenGrantCannotBeRenewedAtDispatch() {
        fixture();AuthVerification auth=authenticate(AuthVerificationPurpose.DELIVERY_SENDER);JobStep step=unlock(auth);
        JsonNode frozen=bindings.findByJobStepJobStepId(step.getJobStepId()).orElseThrow().getAuthorizationJson().deepCopy();
        auth.setValidUntil(Instant.now().minusSeconds(1));
        assertThatThrownBy(()->authorization.requireBound(step,frozen)).hasMessageContaining("expired");
        assertThat(bindings.findByJobStepJobStepId(step.getJobStepId()).orElseThrow().getAuthorizationJson()).isEqualTo(frozen);
    }
    @Test void staffLockCheckCannotReplaceTheLockCommand() {
        fixture();physical(unlock(authenticate(AuthVerificationPurpose.DELIVERY_SENDER)),"STAFF_VERIFIED",true);cargo(payload(true),true);
        respond(instruction("CLOSE_SENDER"),"DOOR_CLOSED",json.createObjectNode().put("closed",true),"door");
        JobStep verify=instruction("VERIFY_SENDER");
        assertThatThrownBy(()->respond(verify,"LOCK_CHECKED",json.createObjectNode().put("locked",true),"check")).hasMessageContaining("followed by LOCK");
        assertThat(delivery.getPickedUpAt()).isNull();
    }
    @Test void negativeCargoAndLockCheckRemainResultsWithoutDeliveryMilestones() {
        fixture();physical(unlock(authenticate(AuthVerificationPurpose.DELIVERY_SENDER)),"STAFF_VERIFIED",true);
        JobStep cargo=payload(true);cargo(cargo,false);assertThat(cargo.getResultJson().path("confirmed").asBoolean()).isFalse();
        assertThat(delivery.getPickupPayloadConfirmedAt()).isNull();
        respond(instruction("CLOSE_SENDER"),"DOOR_CLOSED",json.createObjectNode().put("closed",false),"door-no");
        assertThat(delivery.getPickedUpAt()).isNull();
    }
    @Test void negativeLockCheckAfterValidCargoAndLockDoesNotRecordPickup() {
        fixture();physical(unlock(authenticate(AuthVerificationPurpose.DELIVERY_SENDER)),"STAFF_VERIFIED",true);cargo(payload(true),true);
        respond(instruction("CLOSE_SENDER"),"DOOR_CLOSED",json.createObjectNode().put("closed",true),"door");lock();
        JobStep verify=instruction("VERIFY_SENDER");respond(verify,"LOCK_CHECKED",json.createObjectNode().put("locked",false),"check");
        assertThat(verify.getResultJson().path("locked").asBoolean()).isFalse();assertThat(delivery.getPickedUpAt()).isNull();
    }
    @Test void aNewSelectedCargoVisitCanRetryANegativeAnswerBeforeHandover() {
        fixture();physical(unlock(authenticate(AuthVerificationPurpose.DELIVERY_SENDER)),"STAFF_VERIFIED",true);
        cargo(payload(true),false);assertThat(delivery.getPickupPayloadConfirmedAt()).isNull();
        cargo(payload(true),true);assertThat(delivery.getPickupPayloadConfirmedAt()).isNotNull();
        respond(instruction("CLOSE_SENDER"),"DOOR_CLOSED",json.createObjectNode().put("closed",true),"door");lock();
        respond(instruction("VERIFY_SENDER"),"LOCK_CHECKED",json.createObjectNode().put("locked",true),"check");
        assertThat(delivery.getPickedUpAt()).isNotNull();
    }
    @Test void legacyDeliveryApisCannotBypassSchema3EvenWithoutAJobStepId() {
        fixture();AuthVerification v=authenticate(AuthVerificationPurpose.DELIVERY_SENDER);
        var request=new com.mediassistrover.backend.dto.delivery.request.DeliveryRequests.Confirm(v.getVerificationId(),null,null,true,true,null);
        assertThatThrownBy(()->deliveryApi.confirmPickup(delivery.getServiceRequestId(),request,actor,metadata))
                .isInstanceOf(LegacyExecutionRetiredException.class).hasMessageContaining("Schema 3");
        assertThat(delivery.getPickedUpAt()).isNull();
    }
    @Test void officialUnlockRemainsBlockedByTheUnconfirmedPiContract() {
        fixture();JobStep unlock=unlock(authenticate(AuthVerificationPurpose.DELIVERY_SENDER));
        var settings=new com.mediassistrover.backend.mqtt.MqttProperties();settings.setEnabled(true);settings.setDispatchEnabled(true);
        settings.setEnabledStepTypes(Set.of("CONTROL_HARDWARE"));settings.setRobotIds(Set.of(job.getRobot().getRobotId()));
        var gate=new MqttStepDispatchService(settings,
                new com.mediassistrover.backend.validation.StepExecutionPolicy(settings),
                null,null,null,null,null,null,null,null,Clock.systemUTC(),authorization,null,null,null,null,null);
        assertThatThrownBy(()->gate.requireActionSupported(unlock.getStep())).hasMessageContaining("Pi STAFF_VERIFIED");
        physical(unlock,"STAFF_VERIFIED",true);
        assertThatThrownBy(()->outputs.validate(unlock,unlock.getResultJson())).isInstanceOf(BusinessRuleException.class);
    }

    @Test void milestoneReceiptAndWakeupRollbackTogetherAndConcurrentDuplicatesCompleteOnce() throws Exception {
        fixture();physical(unlock(authenticate(AuthVerificationPurpose.DELIVERY_SENDER)),"STAFF_VERIFIED",true);cargo(payload(true),true);
        respond(instruction("CLOSE_SENDER"),"DOOR_CLOSED",json.createObjectNode().put("closed",true),"door");lock();
        JobStep check=instruction("VERIFY_SENDER");UUID checkId=check.getJobStepId(),requestId=delivery.getServiceRequestId(),jobId=job.getJobId();
        ObjectNode body=json.createObjectNode().put("responseType","LOCK_CHECKED").set("data",json.createObjectNode().put("locked",true));
        em.flush();org.springframework.test.context.transaction.TestTransaction.flagForCommit();org.springframework.test.context.transaction.TestTransaction.end();
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try {
            long before=work.findById(jobId).orElseThrow().getRequestedGeneration();
            tx.executeWithoutResult(status->{responses.respond(checkId,"atomic",body,actor);status.setRollbackOnly();});
            Instant rolledBackMilestone=tx.execute(status->em.find(DeliveryRequest.class,requestId).getPickedUpAt());
            assertThat(rolledBackMilestone).isNull();
            assertThat(steps.findById(checkId).orElseThrow().getStatus()).isEqualTo(JobStepStatus.RUNNING);
            Boolean rolledBackReceipt=tx.execute(status->receipts.findByJobStepJobStepIdAndIdempotencyKey(checkId,"atomic").isPresent());
            assertThat(rolledBackReceipt).isFalse();
            assertThat(work.findById(jobId).orElseThrow().getRequestedGeneration()).isEqualTo(before);
            try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var start=new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Callable<JobStepResponseService.Receipt> callback=()->{start.await();return responses.respond(checkId,"atomic",body,actor);};
                var first=pool.submit(callback);var second=pool.submit(callback);start.countDown();
                var results=List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS));
                assertThat(results).extracting(JobStepResponseService.Receipt::duplicate).containsExactlyInAnyOrder(false,true);
                assertThat(results).extracting(JobStepResponseService.Receipt::responseId).containsOnly(results.getFirst().responseId());
            }
            Instant committedMilestone=tx.execute(status->em.find(DeliveryRequest.class,requestId).getPickedUpAt());
            assertThat(committedMilestone).isNotNull();
            assertThat(work.findById(jobId).orElseThrow().getRequestedGeneration()).isEqualTo(before+1);
        } finally {
            tx.executeWithoutResult(status->{
                for(String sql:List.of("DELETE FROM JobStepResponse WHERE job_step_id IN (SELECT s.job_step_id FROM JobStep s JOIN JobTask t ON t.job_task_id=s.job_task_id WHERE t.job_id=:id)",
                        "DELETE FROM RobotOperationAuthorization WHERE job_step_id IN (SELECT s.job_step_id FROM JobStep s JOIN JobTask t ON t.job_task_id=s.job_task_id WHERE t.job_id=:id)",
                        "DELETE FROM AuthVerification WHERE job_id=:id","UPDATE Job SET service_request_id=NULL WHERE job_id=:id"))
                    em.createNativeQuery(sql).setParameter("id",jobId).executeUpdate();
                em.createNativeQuery("DELETE FROM AuditLog WHERE account_id=:id").setParameter("id",mqttFixture.accountId).executeUpdate();
                em.createNativeQuery("DELETE FROM DeliveryRequest WHERE service_request_id=:id").setParameter("id",requestId).executeUpdate();
                em.createNativeQuery("DELETE FROM ServiceRequest WHERE service_request_id=:id").setParameter("id",requestId).executeUpdate();
                MqttTestFixture.cleanup(em,mqttFixture);
            });
        }
    }
}
