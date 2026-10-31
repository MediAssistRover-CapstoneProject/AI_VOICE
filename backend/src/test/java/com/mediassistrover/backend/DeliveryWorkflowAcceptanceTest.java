package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.dto.delivery.request.DeliveryRequests.*;
import com.mediassistrover.backend.dto.verification.request.*;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Transactional
class DeliveryWorkflowAcceptanceTest extends MySqlIntegrationTest {
    @Autowired ObjectMapper json; @Autowired AccountRepository accounts; @Autowired WorkflowRepository workflows;
    @Autowired HospitalInformationRepository hospitals; @Autowired FloorRepository floors; @Autowired FacilityMapRepository maps;
    @Autowired EndpointRepository endpoints; @Autowired RobotRepository robots; @Autowired JobRepository jobs;
    @Autowired JobStepRepository steps; @Autowired DeliveryRequestRepository deliveries; @Autowired AuthVerificationRepository grants;
    @Autowired DeliveryService deliveryService; @Autowired ServiceRequestService requestService; @Autowired AuthVerificationService authService;
    @Autowired QrVerificationService qrService;
    @Autowired JobStepInputResolver inputResolver; @Autowired DeliveryItemRepository deliveryItems;

    @Test void jobAndPayloadKeepPersistedCargoSnapshotWhenRequestOrCatalogDataChanges(){
        Fixture f=fixture();
        assertThat(deliveryService.list(ServiceRequestStatus.IN_PROGRESS,null,null,null,null,null,null,
                Instant.now().minusSeconds(60),Instant.now().plusSeconds(60),f.principal,PageRequest.of(0,20))
                .getTotalElements()).isEqualTo(1);
        assertThat(f.job.getInputJson().path("items").get(0).path("quantity").asInt()).isEqualTo(2);
        var item=deliveryItems.findByDeliveryRequestServiceRequestIdOrderByItemCodeAsc(f.deliveryId).getFirst();
        item.setQuantity(7); deliveryItems.saveAndFlush(item);
        f.job.getServiceRequest().setRequestDataJson(json.createObjectNode().put("service_request_id","forged"));
        var resolved=inputResolver.resolve(step(f.job,"PAYLOAD_SENDER"));
        assertThat(resolved.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
        assertThat(resolved.path("service_request_id").asText()).isEqualTo(f.deliveryId.toString());
        assertThat(resolved.path("compartment_id").asText()).isEqualTo("CART_MAIN");
    }

    @Test void exactCargoAndPhysicalClosureCompleteTheCorrectPayloadStep(){
        Fixture f=fixture();JobStep auth=step(f.job,"AUTH_SENDER");running(auth,json.createObjectNode().put("purpose","DELIVERY_SENDER"));var grant=authService.create(f.job.getJobId(),new AuthVerificationRequests.Create(auth.getJobStepId(),AuthVerificationMethod.STAFF_CONFIRMATION,AuthVerificationPurpose.DELIVERY_SENDER,300),f.principal,metadata());JobStep payload=step(f.job,"PAYLOAD_SENDER");running(payload,json.createObjectNode().put("action","LOAD"));
        Confirm wrong=new Confirm(grant.verificationId(),payload.getJobStepId(),List.of(new ConfirmedItem("MED-1",1)),true,true,"wrong quantity");assertThatThrownBy(()->deliveryService.confirmPickup(f.deliveryId,wrong,f.principal,metadata())).isInstanceOf(BusinessRuleException.class);assertThat(deliveries.findById(f.deliveryId).orElseThrow().getPickedUpAt()).isNull();
        Confirm correct=new Confirm(grant.verificationId(),payload.getJobStepId(),List.of(new ConfirmedItem("MED-1",2)),true,true,"loaded and locked");var response=deliveryService.confirmPickup(f.deliveryId,correct,f.principal,metadata());assertThat(response.pickedUpAt()).isNotNull();assertThat(response.pickupCompartmentConfirmedAt()).isNotNull();assertThat(payload.getStatus()).isEqualTo(JobStepStatus.SUCCEEDED);assertThat(payload.getResultJson().path("items").get(0).path("quantity").asInt()).isEqualTo(2);assertThat(grants.findById(grant.verificationId()).orElseThrow().getUsedByJobStep().getJobStepId()).isEqualTo(payload.getJobStepId());assertThat(requestService.cancel(f.deliveryId,"cancel after loading",f.principal,metadata()).status()).isEqualTo(ServiceRequestStatus.RECOVERY_REQUIRED);assertThat(f.job.getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(f.job.getRobot().getStatus()).isEqualTo(RobotStatus.ERROR);
    }

    @Test void qrProofIsScopedSingleUseAndCompletesAuthentication(){
        Fixture f=fixture();JobStep auth=step(f.job,"AUTH_SENDER");running(auth,json.createObjectNode().put("purpose","DELIVERY_SENDER"));var challenge=qrService.issue(f.job.getJobId(),new QrVerificationRequests.Issue(auth.getJobStepId(),AuthVerificationPurpose.DELIVERY_SENDER,120),f.principal,metadata());assertThatThrownBy(()->qrService.verify(UUID.randomUUID(),challenge.proof(),f.principal,metadata())).isInstanceOf(BusinessRuleException.class);var verified=qrService.verify(f.job.getJobId(),challenge.proof(),f.principal,metadata());assertThat(verified.method()).isEqualTo(AuthVerificationMethod.QR_CODE);assertThat(auth.getStatus()).isEqualTo(JobStepStatus.SUCCEEDED);assertThatThrownBy(()->qrService.verify(f.job.getJobId(),challenge.proof(),f.principal,metadata())).isInstanceOf(ConflictException.class);
    }

    private Fixture fixture(){String suffix=UUID.randomUUID().toString().substring(0,8);Account account=accounts.save(Account.builder().accountCode("P18-"+suffix).username("p18."+suffix).fullName("Phase 18 Staff").email("p18."+suffix+"@example.test").role(Role.STAFF).authProvider(AuthProvider.LOCAL).passwordHash("unused").status(AccountStatus.ACTIVE).passwordChangedAt(Instant.now()).build());Workflow workflow=workflows.findAll().stream().filter(w->w.getSchemaVersion()==2&&w.getServiceType().equals("DELIVERY")).findFirst().orElseThrow();workflow.setStatus(WorkflowStatus.PUBLISHED);workflow.setAcceptsNewJobs(true);workflow.setDefinitionHash("c".repeat(64));workflow.setCompiledDefinitionJson(json.createObjectNode().put("workflow",workflow.getWorkflowCode()));workflow.setPublishedAt(Instant.now());workflows.saveAndFlush(workflow);HospitalInformation hospital=hospitals.save(HospitalInformation.builder().updatedBy(account).infoCategory("PROFILE").title("Hospital").content("Info").languageCode("vi-VN").keywordsJson(json.createArrayNode()).status(HospitalInformationStatus.PUBLISHED).publicVisible(true).build());Floor floor=floors.save(Floor.builder().hospitalInformation(hospital).floorCode("P18-F-"+suffix).floorName("Floor").displayOrder(1).active(true).build());FacilityMap map=maps.save(FacilityMap.builder().floor(floor).mapCode("P18-M-"+suffix).versionNo(1).mapName("Map").widthM(BigDecimal.TEN).heightM(BigDecimal.TEN).resolutionMPerPixel(new BigDecimal("0.05")).originXM(BigDecimal.ZERO).originYM(BigDecimal.ZERO).originYawRad(BigDecimal.ZERO).frameId("map").mapFileUri("https://example.test/map").assetHash("d".repeat(64)).status(MapStatus.ACTIVE).build());Endpoint pickup=endpoint(map,"PICK-"+suffix),destination=endpoint(map,"DROP-"+suffix);robots.save(Robot.builder().currentMap(map).robotCode("R18-"+suffix).robotName("Robot").model("Test").protocolVersion(1).capabilitiesJson(json.createArrayNode()).status(RobotStatus.IDLE).batteryUnit(BatteryUnit.UNKNOWN).deviceTokenVersion(0).build());Create create=new Create(map.getMapId(),null,null,pickup.getEndpointId(),destination.getEndpointId(),null,TargetMode.ENDPOINT,"Receiver",null,true,null,Instant.now().plusSeconds(3600),null,List.of(new Item("MED-1","Medicine","MEDICINE",2,"BOX",true,null,null)),80,"delivery",json.createObjectNode());var made=deliveryService.create(create,"p18-"+suffix,AccountPrincipal.fromAccount(account),metadata());requestService.submit(made.request().serviceRequestId(),AccountPrincipal.fromAccount(account),metadata());Job job=jobs.findByServiceRequestServiceRequestId(made.request().serviceRequestId()).getFirst();job.setStatus(JobStatus.RUNNING);job.setStartedAt(Instant.now());job.getServiceRequest().setStatus(ServiceRequestStatus.IN_PROGRESS);jobs.flush();return new Fixture(AccountPrincipal.fromAccount(account),job,made.request().serviceRequestId());}
    private Endpoint endpoint(FacilityMap map,String code){return endpoints.save(Endpoint.builder().map(map).endpointCode(code).endpointName(code).endpointType(EndpointType.WAYPOINT).xM(BigDecimal.ONE).yM(BigDecimal.ONE).yawRad(BigDecimal.ZERO).active(true).accessible(true).publicVisible(true).build());}
    private JobStep step(Job job,String code){return steps.findByJobId(job.getJobId()).stream().filter(s->s.getStepCodeSnapshot().equals(code)).findFirst().orElseThrow();}
    private void running(JobStep step,com.fasterxml.jackson.databind.JsonNode input){step.setStatus(JobStepStatus.RUNNING);step.setStartedAt(Instant.now());step.setResolvedInputsJson(input);steps.saveAndFlush(step);}
    private RequestMetadata metadata(){return new RequestMetadata("127.0.0.1","test","phase18-test");}
    private record Fixture(AccountPrincipal principal,Job job,UUID deliveryId) { }
}
