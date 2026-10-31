package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.dto.request.request.ServiceRequestRequests.OperationalCreateRequest;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class Phase20WorkflowCatalogIntegrationTest extends MySqlIntegrationTest {
    private static final Set<String> CANONICAL=Set.of(
            "WF_PATIENT_ESCORT","WF_INFORMATION_ASSISTANCE","WF_SECURE_MEDICAL_DELIVERY",
            "WF_CROWD_MONITORING","WF_EMERGENCY_SOS_RESPONSE","WF_FALL_DETECTION_RESPONSE",
            "WF_RESTRICTED_AREA_MONITORING","WF_AUTO_CHARGING","WF_NAVIGATION_RECOVERY",
            "WF_ROBOT_STARTUP_STANDBY");
    @Autowired WorkflowRepository workflows; @Autowired WorkflowService workflowService;
    @Autowired ServiceRequestService requestService; @Autowired ServiceRequestRepository requests;
    @Autowired JobRepository jobs; @Autowired RobotRepository robots; @Autowired AccountRepository accounts;
    @Autowired DetectionEventRepository detections; @Autowired HospitalInformationRepository hospitals;
    @Autowired FloorRepository floors; @Autowired FacilityMapRepository maps;
    @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    private Account admin; private AccountPrincipal principal;

    @BeforeEach void setup(){
        cleanRuntime();
        admin=accounts.save(Account.builder().accountCode("P20-ADMIN").username("p20-admin").fullName("Phase 20 Admin")
                .email("p20@example.test").role(Role.ADMIN).authProvider(AuthProvider.LOCAL).passwordHash("unused")
                .status(AccountStatus.ACTIVE).passwordChangedAt(Instant.now()).build());
        principal=AccountPrincipal.fromAccount(admin);
    }
    @AfterEach void cleanup(){cleanRuntime();}

    @Test void canonicalCatalogContainsTenWorkflowsAndEveryNewGraphCompiles(){
        Map<String,Workflow> latest=new HashMap<>();
        workflows.findAll().stream().filter(w->CANONICAL.contains(w.getWorkflowCode())).forEach(w->
                latest.merge(w.getWorkflowCode(),w,(a,b)->a.getVersionNo()>b.getVersionNo()?a:b));
        assertThat(latest.keySet()).containsExactlyInAnyOrderElementsOf(CANONICAL);
        for(String code:List.of("WF_FALL_DETECTION_RESPONSE","WF_RESTRICTED_AREA_MONITORING",
                "WF_AUTO_CHARGING","WF_NAVIGATION_RECOVERY","WF_ROBOT_STARTUP_STANDBY")){
            var result=workflowService.validate(latest.get(code).getWorkflowId(),admin.getAccountId(),metadata());
            assertThat(result.status()).as(code).isEqualTo(WorkflowStatus.VALIDATED);
            assertThat(result.definitionHash()).as(code).hasSize(64);
        }
        assertThat(workflows.findTopByWorkflowCodeIgnoreCaseOrderByVersionNoDesc("WF_NAVIGATION")).isPresent();
    }

    @Test void manualChargingEntryPointCreatesOneServerJobAndIsIdempotent(){
        Workflow charging=legacy("WF_AUTO_CHARGING");
        workflowService.validate(charging.getWorkflowId(),admin.getAccountId(),metadata());
        workflowService.publish(charging.getWorkflowId(),admin.getAccountId(),metadata());
        Robot robot=robots.save(Robot.builder().robotCode("P20-ROBOT").robotName("Phase 20 Robot").model("Test")
                .protocolVersion(1).capabilitiesJson(json.createArrayNode().add("NOTIFICATION").add("EVENT_WAIT").add("SENSORS"))
                .status(RobotStatus.IDLE).batteryUnit(BatteryUnit.UNKNOWN).deviceTokenVersion(0).build());
        var body=new OperationalCreateRequest(ServiceRequestType.AUTO_CHARGING,robot.getRobotId(),70,"Charge requested",json.createObjectNode());
        var first=requestService.createOperational(body,"p20-charge",principal,metadata());
        var duplicate=requestService.createOperational(body,"p20-charge",principal,metadata());
        assertThat(duplicate.serviceRequestId()).isEqualTo(first.serviceRequestId());
        assertThat(first.status()).isEqualTo(ServiceRequestStatus.QUEUED);
        assertThat(first.sourceType()).isEqualTo(RequestSourceType.STAFF_UI);
        assertThat(first.requestDataJson().path("automatic_charging").isMissingNode()).isTrue();
        assertThat(first.requestDataJson().path("charge_request_id").asText()).isNotBlank();
        assertThat(jobs.findByServiceRequestServiceRequestId(first.serviceRequestId())).hasSize(1);
    }

    @Test void startupReadinessIsAdminOnlyAndRejectsUserSuppliedInput(){
        Account staff=accounts.save(Account.builder().accountCode("P20-STAFF").username("p20-staff").fullName("Staff")
                .role(Role.STAFF).authProvider(AuthProvider.LOCAL).passwordHash("unused").status(AccountStatus.ACTIVE).build());
        Robot robot=robots.save(Robot.builder().robotCode("P20-START").robotName("Startup Robot").model("Test")
                .protocolVersion(1).capabilitiesJson(json.createArrayNode()).status(RobotStatus.IDLE)
                .batteryUnit(BatteryUnit.UNKNOWN).deviceTokenVersion(0).build());
        var empty=new OperationalCreateRequest(ServiceRequestType.ROBOT_STARTUP_STANDBY,robot.getRobotId(),50,null,json.createObjectNode());
        assertThatThrownBy(()->requestService.createOperational(empty,"p20-start-staff",AccountPrincipal.fromAccount(staff),metadata()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var injected=new OperationalCreateRequest(ServiceRequestType.ROBOT_STARTUP_STANDBY,robot.getRobotId(),50,null,json.createObjectNode().put("ready",true));
        assertThatThrownBy(()->requestService.createOperational(injected,"p20-start-admin",principal,metadata()))
                .isInstanceOf(com.mediassistrover.backend.exception.BusinessRuleException.class)
                .hasMessageContaining("empty object");
    }

    @Test void fallResponseRequiresARealSuspectedFallFromTheRequestedRobot(){
        Workflow fall=legacy("WF_FALL_DETECTION_RESPONSE");
        workflowService.validate(fall.getWorkflowId(),admin.getAccountId(),metadata());
        workflowService.publish(fall.getWorkflowId(),admin.getAccountId(),metadata());
        HospitalInformation hospital=hospitals.save(HospitalInformation.builder().updatedBy(admin).infoCategory("PROFILE")
                .title("Hospital").content("Info").languageCode("vi-VN").keywordsJson(json.createArrayNode())
                .status(HospitalInformationStatus.PUBLISHED).publicVisible(true).build());
        Floor floor=floors.save(Floor.builder().hospitalInformation(hospital).floorCode("P20-F1").floorName("Floor")
                .displayOrder(1).active(true).build());
        FacilityMap map=maps.save(FacilityMap.builder().floor(floor).mapCode("P20-MAP").versionNo(1).mapName("Map")
                .widthM(BigDecimal.TEN).heightM(BigDecimal.TEN).resolutionMPerPixel(new BigDecimal("0.05"))
                .originXM(BigDecimal.ZERO).originYM(BigDecimal.ZERO).originYawRad(BigDecimal.ZERO).frameId("map")
                .mapFileUri("https://example.test/p20.yaml").assetHash("b".repeat(64)).status(MapStatus.ACTIVE).build());
        Robot source=robots.save(robot("P20-FALL-SOURCE",map));
        Robot other=robots.save(robot("P20-FALL-OTHER",map));
        DetectionEvent event=detections.save(DetectionEvent.builder().detectionEventId(UUID.randomUUID()).robot(source).map(map)
                .eventType(DetectionEventType.SUSPECTED_FALL).confidenceScore(new BigDecimal("0.9100"))
                .locationBasis(DetectionLocationBasis.ROBOT).eventPayloadJson(json.createObjectNode())
                .detectedAt(Instant.now()).receivedAt(Instant.now()).build());
        var untrusted=json.createObjectNode().put("detection_event_id",event.getDetectionEventId().toString()).put("confidence",0.01);
        var wrongRobot=new OperationalCreateRequest(ServiceRequestType.FALL_DETECTION_RESPONSE,other.getRobotId(),90,null,untrusted);
        assertThatThrownBy(()->requestService.createOperational(wrongRobot,"p20-fall-wrong",principal,metadata()))
                .isInstanceOf(com.mediassistrover.backend.exception.BusinessRuleException.class).hasMessageContaining("another robot");
        var valid=new OperationalCreateRequest(ServiceRequestType.FALL_DETECTION_RESPONSE,source.getRobotId(),90,null,untrusted);
        var response=requestService.createOperational(valid,"p20-fall-valid",principal,metadata());
        assertThat(response.requestDataJson().path("confidence").decimalValue()).isEqualByComparingTo("0.9100");
        assertThat(jobs.findByServiceRequestServiceRequestId(response.serviceRequestId())).hasSize(1);
    }

    private Workflow legacy(String code){return workflows.findAll().stream().filter(w->w.getSchemaVersion()==2&&w.getWorkflowCode().equals(code)).max(Comparator.comparingInt(Workflow::getVersionNo)).orElseThrow();}

    private Robot robot(String code,FacilityMap map){return Robot.builder().currentMap(map).robotCode(code).robotName(code).model("Test")
            .protocolVersion(1).capabilitiesJson(json.createArrayNode()).status(RobotStatus.IDLE)
            .batteryUnit(BatteryUnit.UNKNOWN).deviceTokenVersion(0).build();}

    private RequestMetadata metadata(){return new RequestMetadata("127.0.0.1","test","phase20-test");}
    private void cleanRuntime(){
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for(String table:List.of("JobEvent","JobStep","JobTask","Job","ServiceRequest","DetectionEvent","Robot","Endpoint","Zone","Map","Floor","HospitalInformation","AuditLog","StaffProfile","Account"))
            jdbc.execute("TRUNCATE TABLE `"+table+"`");
        jdbc.update("UPDATE `Workflow` SET status='DRAFT',accepts_new_jobs=FALSE,compiled_definition_json=NULL,definition_hash=NULL,published_at=NULL,published_by_account_id=NULL,row_version=0 WHERE workflow_code IN ('WF_FALL_DETECTION_RESPONSE','WF_RESTRICTED_AREA_MONITORING','WF_AUTO_CHARGING','WF_NAVIGATION_RECOVERY','WF_ROBOT_STARTUP_STANDBY')");
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
    }
}
