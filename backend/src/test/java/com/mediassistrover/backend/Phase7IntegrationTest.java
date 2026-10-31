package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.dto.delivery.request.DeliveryRequests.*;
import com.mediassistrover.backend.dto.delivery.response.DeliveryResponses.DeliveryResponse;
import com.mediassistrover.backend.dto.emergency.request.EmergencyRequests.*;
import com.mediassistrover.backend.dto.emergency.response.EmergencyResponses.SosResponse;
import com.mediassistrover.backend.dto.pi.request.PiEnvelope;
import com.mediassistrover.backend.dto.verification.request.AuthVerificationRequests;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.*;
import com.mediassistrover.backend.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Phase7IntegrationTest extends MySqlIntegrationTest {
    @Autowired ObjectMapper objectMapper; @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc; @Autowired DeviceJwtTokenService deviceTokens;
    @Autowired AccountRepository accountRepository; @Autowired WorkflowRepository workflowRepository;
    @Autowired HospitalInformationRepository hospitalRepository; @Autowired FloorRepository floorRepository;
    @Autowired FacilityMapRepository mapRepository; @Autowired EndpointRepository endpointRepository;
    @Autowired RobotRepository robotRepository; @Autowired ServiceRequestRepository requestRepository;
    @Autowired JobRepository jobRepository; @Autowired JobStepRepository jobStepRepository;
    @Autowired DeliveryRequestRepository deliveryRepository; @Autowired DeliveryItemRepository itemRepository;
    @Autowired EmergencyIncidentRepository incidentRepository; @Autowired AlertRepository alertRepository;
    @Autowired AlertRecipientRepository recipientRepository; @Autowired AuthVerificationRepository verificationRepository;
    @Autowired DeliveryService deliveryService; @Autowired ServiceRequestService requestService;
    @Autowired AuthVerificationService verificationService; @Autowired EmergencyService emergencyService;
    @Autowired AlertService alertService;
    private Account staff; private AccountPrincipal staffPrincipal;

    @BeforeEach void setup(){clean();staff=accountRepository.save(Account.builder().accountCode("STAFF-P7").username("phase7.staff").fullName("Phase 7 Staff").email("phase7.staff@example.test").role(Role.STAFF).authProvider(AuthProvider.LOCAL).passwordHash("unused").status(AccountStatus.ACTIVE).passwordChangedAt(Instant.now()).build());staffPrincipal=AccountPrincipal.fromAccount(staff);}
    @AfterEach void cleanup(){clean();}

    @Test void secureDeliveryCreatesItemsJobAndRequiresPurposeSpecificVerifications(){
        publish("DELIVERY");FacilityMap map=facility();Endpoint pickup=endpoint(map,"P7-PICKUP");Endpoint destination=endpoint(map,"P7-DEST");robot(map,"P7-DELIVERY");
        Create body=new Create(map.getMapId(),null,null,pickup.getEndpointId(),destination.getEndpointId(),null,TargetMode.ENDPOINT,"Lab Receiver","100",true,null,Instant.now().plusSeconds(3600),"Handle carefully",List.of(new Item("DOC-1","Medical document","DOCUMENT",1,"FILE",true,"Keep sealed",null)),90,"Secure delivery",objectMapper.createObjectNode());
        DeliveryResponse created=deliveryService.create(body,"delivery-p7-1",staffPrincipal,metadata());DeliveryResponse duplicate=deliveryService.create(body,"delivery-p7-1",staffPrincipal,metadata());
        assertThat(duplicate.request().serviceRequestId()).isEqualTo(created.request().serviceRequestId());assertThat(created.items()).hasSize(1);assertThat(itemRepository.count()).isEqualTo(1);
        requestService.submit(created.request().serviceRequestId(),staffPrincipal,metadata());Job job=jobRepository.findByServiceRequestServiceRequestId(created.request().serviceRequestId()).getFirst();ServiceRequest parent=requestRepository.findById(created.request().serviceRequestId()).orElseThrow();parent.setStatus(ServiceRequestStatus.IN_PROGRESS);requestRepository.saveAndFlush(parent);job.setStatus(JobStatus.RUNNING);jobRepository.saveAndFlush(job);
        JobStep sender=authStep(job,"AUTH_SENDER");sender.setStatus(JobStepStatus.RUNNING);sender.setStartedAt(Instant.now());jobStepRepository.saveAndFlush(sender);var senderGrant=verificationService.create(job.getJobId(),new AuthVerificationRequests.Create(sender.getJobStepId(),AuthVerificationMethod.STAFF_CONFIRMATION,AuthVerificationPurpose.DELIVERY_SENDER,300),staffPrincipal,metadata());
        DeliveryResponse picked=deliveryService.confirmPickup(parent.getServiceRequestId(),new Confirm(senderGrant.verificationId(),"Loaded"),staffPrincipal,metadata());assertThat(picked.pickedUpAt()).isNotNull();assertThat(picked.senderAccountId()).isEqualTo(staff.getAccountId());
        JobStep receiver=authStep(job,"AUTH_RECEIVER");receiver.setStatus(JobStepStatus.RUNNING);receiver.setStartedAt(Instant.now());jobStepRepository.saveAndFlush(receiver);var receiverGrant=verificationService.create(job.getJobId(),new AuthVerificationRequests.Create(receiver.getJobStepId(),AuthVerificationMethod.STAFF_CONFIRMATION,AuthVerificationPurpose.DELIVERY_RECEIVER,300),staffPrincipal,metadata());
        DeliveryResponse delivered=deliveryService.confirmDelivery(parent.getServiceRequestId(),new Confirm(receiverGrant.verificationId(),"Received"),staffPrincipal,metadata());assertThat(delivered.deliveredAt()).isNotNull();assertThat(delivered.receiverAccountId()).isEqualTo(staff.getAccountId());assertThat(verificationRepository.count()).isEqualTo(2);
    }

    @Test void deviceSosIsIdempotentAndIncidentAlertFollowControlledStateMachines() throws Exception {
        publish("EMERGENCY");FacilityMap map=facility();Endpoint endpoint=endpoint(map,"P7-SOS");Robot robot=robot(map,"P7-SOS-ROBOT");DevicePrincipal device=new DevicePrincipal(robot.getRobotId(),robot.getRobotCode(),0,"device",Instant.now().plusSeconds(3600));
        SosPayload payload=new SosPayload(map.getMapId(),endpoint.getEndpointId(),EmergencyIncidentSeverity.CRITICAL,"Patient requests urgent assistance",BigDecimal.ONE,BigDecimal.TWO);PiEnvelope<SosPayload> first=envelope(robot,"sos-operation-1",payload,UUID.randomUUID());
        SosResponse created=emergencyService.createSos(first,device,metadata());SosResponse duplicate=emergencyService.createSos(envelope(robot,"sos-operation-1",payload,UUID.randomUUID()),device,metadata());
        assertThat(duplicate.incident().incidentId()).isEqualTo(created.incident().incidentId());assertThat(incidentRepository.count()).isEqualTo(1);assertThat(alertRepository.count()).isEqualTo(1);assertThat(jobRepository.count()).isEqualTo(1);assertThat(recipientRepository.count()).isEqualTo(1);assertThat(requestRepository.findById(created.serviceRequestId()).orElseThrow().getStatus()).isEqualTo(ServiceRequestStatus.QUEUED);
        var deviceStatus=emergencyService.getDeviceIncident(created.incident().incidentId(),device);assertThat(deviceStatus.serviceRequestId()).isEqualTo(created.serviceRequestId());assertThat(deviceStatus.alertId()).isEqualTo(created.alertId());assertThat(deviceStatus.jobId()).isEqualTo(created.jobId());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/pi/incidents/{id}",created.incident().incidentId()).header("Authorization","Bearer "+deviceTokens.issue(robot).value()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.acknowledgedByAccountId").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.resolvedByAccountId").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.recipients").doesNotExist());
        assertThat(emergencyService.acknowledge(created.incident().incidentId(),staffPrincipal,metadata()).status()).isEqualTo(EmergencyIncidentStatus.ACKNOWLEDGED);assertThat(alertRepository.findById(created.alertId()).orElseThrow().getStatus()).isEqualTo(AlertStatus.ACKNOWLEDGED);
        assertThatThrownBy(()->alertService.resolve(created.alertId(),staffPrincipal,metadata())).hasMessageContaining("linked incident");
        assertThat(emergencyService.start(created.incident().incidentId(),staffPrincipal,metadata()).status()).isEqualTo(EmergencyIncidentStatus.IN_PROGRESS);assertThat(emergencyService.resolve(created.incident().incidentId(),new Resolution("Staff arrived"),staffPrincipal,metadata()).status()).isEqualTo(EmergencyIncidentStatus.RESOLVED);assertThat(emergencyService.close(created.incident().incidentId(),staffPrincipal,metadata()).status()).isEqualTo(EmergencyIncidentStatus.CLOSED);assertThat(alertRepository.findById(created.alertId()).orElseThrow().getStatus()).isEqualTo(AlertStatus.CLOSED);
        assertThat(emergencyService.getDeviceIncident(created.incident().incidentId(),device).status()).isEqualTo(EmergencyIncidentStatus.CLOSED);
    }

    private JobStep authStep(Job job,String code){return jobStepRepository.findByJobId(job.getJobId()).stream().filter(s->s.getStepCodeSnapshot().equals(code)).findFirst().orElseThrow();}
    private PiEnvelope<SosPayload> envelope(Robot robot,String operation,SosPayload payload,UUID messageId){return new PiEnvelope<>("1.0",messageId,operation,UUID.randomUUID(),robot.getRobotId(),null,null,"EMERGENCY_SOS",Instant.now(),UUID.randomUUID(),1,payload);}
    @Test void sosOnBusyRobotPersistsIncidentAndAlertWithoutStealingRobot(){publish("EMERGENCY");FacilityMap map=facility();Robot robot=robot(map,"P7-BUSY-SOS");robot.setStatus(RobotStatus.BUSY);robotRepository.saveAndFlush(robot);var device=new DevicePrincipal(robot.getRobotId(),robot.getRobotCode(),0,"device",Instant.now().plusSeconds(3600));
        var payload=new SosPayload(map.getMapId(),null,EmergencyIncidentSeverity.CRITICAL,"SOS while another action is running",null,null);var result=emergencyService.createSos(envelope(robot,"busy-sos",payload,UUID.randomUUID()),device,metadata());assertThat(result.jobId()).isNull();assertThat(incidentRepository.count()).isEqualTo(1);assertThat(alertRepository.count()).isEqualTo(1);assertThat(requestRepository.findById(result.serviceRequestId()).orElseThrow().getRequestDataJson().path("dispatch_pending").asBoolean()).isTrue();assertThat(robotRepository.findById(robot.getRobotId()).orElseThrow().getStatus()).isEqualTo(RobotStatus.BUSY);}
    @Test void sosOperationCannotBeReusedWithChangedPayload(){FacilityMap map=facility();Robot robot=robot(map,"P7-SOS-CONFLICT");var device=new DevicePrincipal(robot.getRobotId(),robot.getRobotCode(),0,"device",Instant.now().plusSeconds(3600));var payload=new SosPayload(map.getMapId(),null,EmergencyIncidentSeverity.CRITICAL,"SOS",null,null);emergencyService.createSos(envelope(robot,"conflicting-sos",payload,UUID.randomUUID()),device,metadata());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->emergencyService.createSos(envelope(robot,"conflicting-sos",new SosPayload(map.getMapId(),null,EmergencyIncidentSeverity.WARNING,"Changed",null,null),UUID.randomUUID()),device,metadata())).hasMessageContaining("different payload");assertThat(incidentRepository.count()).isEqualTo(1);}

    @Test void deviceCannotReadAnotherRobotsIncident(){FacilityMap map=facility();Robot owner=robot(map,"P7-SOS-OWNER");Robot other=robot(map,"P7-SOS-OTHER");var ownerDevice=new DevicePrincipal(owner.getRobotId(),owner.getRobotCode(),0,"owner",Instant.now().plusSeconds(3600));var otherDevice=new DevicePrincipal(other.getRobotId(),other.getRobotCode(),0,"other",Instant.now().plusSeconds(3600));var created=emergencyService.createSos(envelope(owner,"owned-sos",new SosPayload(map.getMapId(),null,EmergencyIncidentSeverity.CRITICAL,"SOS",null,null),UUID.randomUUID()),ownerDevice,metadata());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->emergencyService.getDeviceIncident(created.incident().incidentId(),otherDevice)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);}
    @Test void staffAlertAndIncidentListsApplyFiltersScopeAndRecipientRedaction(){
        publish("EMERGENCY");Account second=accountRepository.save(Account.builder().accountCode("STAFF-P8-SECOND").username("phase8.second").fullName("Second Staff").email("phase8.second@example.test").role(Role.STAFF).authProvider(AuthProvider.LOCAL).passwordHash("unused").status(AccountStatus.ACTIVE).passwordChangedAt(Instant.now()).build());
        FacilityMap map=facility();Endpoint target=endpoint(map,"P8-FILTER");Robot robot=robot(map,"P8-FILTER-ROBOT");DevicePrincipal device=new DevicePrincipal(robot.getRobotId(),robot.getRobotCode(),0,"device",Instant.now().plusSeconds(3600));Instant from=Instant.now().minusSeconds(30);var created=emergencyService.createSos(envelope(robot,"phase8-filter",new SosPayload(map.getMapId(),target.getEndpointId(),EmergencyIncidentSeverity.CRITICAL,"Scoped SOS",BigDecimal.ONE,BigDecimal.TWO),UUID.randomUUID()),device,metadata());Instant to=Instant.now().plusSeconds(30);
        var alerts=alertService.list(AlertStatus.OPEN,AlertSeverity.CRITICAL,"SOS",from,to,staffPrincipal,PageRequest.of(0,20));assertThat(alerts.getTotalElements()).isEqualTo(1);assertThat(alerts.getContent().getFirst().recipients()).hasSize(1);assertThat(alerts.getContent().getFirst().recipients().getFirst().accountId()).isEqualTo(staff.getAccountId());assertThat(recipientRepository.count()).isEqualTo(2);
        var incidents=emergencyService.list(EmergencyIncidentStatus.OPEN,EmergencyIncidentSeverity.CRITICAL,"SOS",map.getMapId(),null,from,to,staffPrincipal,PageRequest.of(0,20));assertThat(incidents.getTotalElements()).isEqualTo(1);assertThat(incidents.getContent().getFirst().incidentId()).isEqualTo(created.incident().incidentId());
        Account foreign=accountRepository.save(Account.builder().accountCode("STAFF-P8-FOREIGN").username("phase8.foreign").fullName("Foreign Staff").email("phase8.foreign@example.test").role(Role.STAFF).authProvider(AuthProvider.LOCAL).passwordHash("unused").status(AccountStatus.ACTIVE).passwordChangedAt(Instant.now()).build());AccountPrincipal foreignPrincipal=AccountPrincipal.fromAccount(foreign);
        assertThatThrownBy(()->emergencyService.get(created.incident().incidentId(),foreignPrincipal)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(second.getAccountId()).isNotNull();
    }
    private Workflow publish(String serviceType){Workflow w=workflowRepository.findAll().stream().filter(x->x.getSchemaVersion()==2&&x.getServiceType().equals(serviceType)).findFirst().orElseThrow();w.setStatus(WorkflowStatus.PUBLISHED);w.setAcceptsNewJobs(true);w.setDefinitionHash("c".repeat(64));w.setCompiledDefinitionJson(objectMapper.createObjectNode().put("workflow",w.getWorkflowCode()));w.setPublishedAt(Instant.now());return workflowRepository.saveAndFlush(w);}
    private Robot robot(FacilityMap map,String code){return robotRepository.saveAndFlush(Robot.builder().currentMap(map).robotCode(code).robotName(code).model("Test").protocolVersion(1).capabilitiesJson(objectMapper.createArrayNode()).status(RobotStatus.IDLE).batteryUnit(BatteryUnit.UNKNOWN).deviceSecretHash("configured-for-test").deviceTokenVersion(0).build());}
    private FacilityMap facility(){HospitalInformation h=hospitalRepository.save(HospitalInformation.builder().updatedBy(staff).infoCategory("PROFILE").title("Hospital").content("Info").languageCode("vi-VN").keywordsJson(objectMapper.createArrayNode()).status(HospitalInformationStatus.PUBLISHED).publicVisible(true).build());Floor f=floorRepository.save(Floor.builder().hospitalInformation(h).floorCode("P7-F1").floorName("Floor 1").displayOrder(1).active(true).build());return mapRepository.save(FacilityMap.builder().floor(f).mapCode("P7-MAP").versionNo(1).mapName("Map").widthM(BigDecimal.TEN).heightM(BigDecimal.TEN).resolutionMPerPixel(new BigDecimal("0.05")).originXM(BigDecimal.ZERO).originYM(BigDecimal.ZERO).originYawRad(BigDecimal.ZERO).frameId("map").mapFileUri("https://example.test/map").assetHash("d".repeat(64)).status(MapStatus.ACTIVE).build());}
    private Endpoint endpoint(FacilityMap map,String code){return endpointRepository.save(Endpoint.builder().map(map).endpointCode(code).endpointName(code).endpointType(EndpointType.WAYPOINT).xM(BigDecimal.ONE).yM(BigDecimal.ONE).yawRad(BigDecimal.ZERO).active(true).accessible(true).publicVisible(true).build());}
    private RequestMetadata metadata(){return new RequestMetadata("127.0.0.1","test","phase7-test");}
    private void clean(){jdbc.execute("SET FOREIGN_KEY_CHECKS=0");for(String t:new String[]{"AlertRecipient","Alert","AuthVerification","DeliveryItem","DeliveryRequest","EmergencyIncident","JobEvent","JobStep","JobTask","Job","Schedule","NavigationRequest","EscortRequest","ServiceRequest","RobotLocationHistory","RobotStatusHistory","Robot","EndpointGroupMember","Endpoint","Room","EndpointGroup","Zone","Map","Floor","HospitalInformation","AuditLog","RevokedToken","RefreshToken","StaffProfile","Account"})jdbc.execute("TRUNCATE TABLE `"+t+"`");jdbc.update("UPDATE `Workflow` SET status='DRAFT',accepts_new_jobs=FALSE,compiled_definition_json=NULL,definition_hash=NULL,published_at=NULL,published_by_account_id=NULL,row_version=0 WHERE schema_version=2");jdbc.execute("SET FOREIGN_KEY_CHECKS=1");}
}
