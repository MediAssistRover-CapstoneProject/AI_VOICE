package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.dto.request.request.ServiceRequestRequests.NavigationCreateRequest;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.mqtt.MqttProperties;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.*;
import com.mediassistrover.backend.validation.WorkflowExecutionReadiness;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class SingleEndpointMoveWorkflowIntegrationTest extends MySqlIntegrationTest {
    static final UUID WORKFLOW_ID=UUID.fromString("9d7d8f73-3f47-4a19-8e24-932f0b739a61");
    @Autowired WorkflowRepository workflows; @Autowired StepRepository definitions;
    @Autowired WorkflowValidationService validation; @Autowired WorkflowExecutionReadiness readiness;
    @Autowired ServiceRequestService requests; @Autowired JobRepository jobs; @Autowired JobStepRepository jobSteps;
    @Autowired RobotCommandOutboxRepository commands; @Autowired WorkflowOrchestratorService orchestrator;
    @Autowired AccountRepository accounts; @Autowired HospitalInformationRepository hospitals;
    @Autowired FloorRepository floors; @Autowired FacilityMapRepository maps; @Autowired EndpointRepository endpoints;
    @Autowired RobotRepository robots; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired MqttProperties mqtt;
    private Account account; private FacilityMap map; private Endpoint endpoint;

    @BeforeEach void setUp(){
        cleanRuntime();mqtt.setEnabled(true);mqtt.setDispatchEnabled(true);mqtt.setEnabledStepTypes(new HashSet<>(Set.of("MOVE")));mqtt.getRobotIds().clear();
        account=accounts.save(Account.builder().accountCode("MOVE-ONLY-ADMIN").username("move-only-admin").fullName("Move Only Admin")
                .email("move-only@example.test").role(Role.ADMIN).authProvider(AuthProvider.LOCAL).passwordHash("unused")
                .status(AccountStatus.ACTIVE).passwordChangedAt(Instant.now()).build());
        HospitalInformation hospital=hospitals.save(HospitalInformation.builder().updatedBy(account).infoCategory("PROFILE").title("Hospital")
                .content("Test").languageCode("vi-VN").keywordsJson(json.createArrayNode()).status(HospitalInformationStatus.PUBLISHED).publicVisible(true).build());
        Floor floor=floors.save(Floor.builder().hospitalInformation(hospital).floorCode("MOVE-F1").floorName("Floor 1").displayOrder(1).active(true).build());
        map=maps.save(FacilityMap.builder().floor(floor).mapCode("MOVE-MAP").versionNo(1).mapName("Move map").widthM(BigDecimal.TEN)
                .heightM(BigDecimal.TEN).resolutionMPerPixel(new BigDecimal("0.05")).originXM(BigDecimal.ZERO).originYM(BigDecimal.ZERO)
                .originYawRad(BigDecimal.ZERO).frameId("map").mapFileUri("https://example.test/map.yaml").assetHash("a".repeat(64)).status(MapStatus.ACTIVE).build());
        endpoint=endpoints.save(Endpoint.builder().map(map).endpointCode("MOVE-ENDPOINT").endpointName("Real persisted endpoint")
                .endpointType(EndpointType.WAYPOINT).xM(BigDecimal.ONE).yM(BigDecimal.ONE).yawRad(BigDecimal.ZERO)
                .active(true).accessible(true).publicVisible(true).build());
    }
    @AfterEach void tearDown(){mqtt.getRobotIds().clear();mqtt.setEnabled(false);mqtt.setDispatchEnabled(false);cleanRuntime();}

    @Test @Transactional void migrationPublishesCompilerExactMoveOnlyWorkflow() {
        Workflow workflow=workflows.findById(WORKFLOW_ID).orElseThrow();
        var compiled=validation.validateAndCompile(workflow);
        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.PUBLISHED);
        assertThat(workflow.isAcceptsNewJobs()).isTrue();
        assertThat(workflow.getDefinitionHash()).isEqualTo(compiled.definitionHash());
        assertThat(workflow.getCompiledDefinitionJson()).isEqualTo(compiled.compiledDefinition());
        assertThat(definitions.findByWorkflowWorkflowIdOrderBySequenceNoAsc(WORKFLOW_ID))
                .extracting(Step::getStepCode,Step::getStepType).containsExactly(tuple("MOVE_SINGLE_ENDPOINT",StepType.MOVE));
    }

    @Test void selectedWorkflowAndRealEndpointAreSnapshottedWithoutSpeakAndNoCommandBeforeHello() {
        Robot robot=robot(json.createArrayNode().add("MOVE"));mqtt.getRobotIds().add(robot.getRobotId());
        var body=new NavigationCreateRequest(map.getMapId(),WORKFLOW_ID,null,null,endpoint.getEndpointId(),null,
                TargetMode.ENDPOINT,NavigationMode.STANDARD,null,1,null,50,"MOVE-only",json.createObjectNode());
        var created=requests.createNavigation(body,"move-only-request",AccountPrincipal.fromAccount(account),metadata());
        requests.submit(created.request().serviceRequestId(),AccountPrincipal.fromAccount(account),metadata());
        Job job=jobs.findByServiceRequestServiceRequestId(created.request().serviceRequestId()).getFirst();
        assertThat(job.getWorkflow().getWorkflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(job.getInputJson().path("endpoint_id").asText()).isEqualTo(endpoint.getEndpointId().toString());
        assertThat(jobSteps.findByJobId(job.getJobId())).extracting(JobStep::getStepCodeSnapshot)
                .containsExactly("MOVE_SINGLE_ENDPOINT").doesNotContain("SPEAK_SINGLE");
        orchestrator.advance(job.getJobId());
        assertThat(jobs.findById(job.getJobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(commands.count()).isZero();
    }

    @Test void disabledMoveCapabilityIsRejectedBeforeJobCreationWithClearError() {
        Robot robot=robot(json.createArrayNode());mqtt.getRobotIds().add(robot.getRobotId());
        mqtt.setEnabledStepTypes(new HashSet<>(Set.of("INTERACT")));
        var body=new NavigationCreateRequest(map.getMapId(),WORKFLOW_ID,null,null,endpoint.getEndpointId(),null,
                TargetMode.ENDPOINT,NavigationMode.STANDARD,null,1,null,50,"unsupported",json.createObjectNode());
        var created=requests.createNavigation(body,"unsupported-move",AccountPrincipal.fromAccount(account),metadata());
        assertThatThrownBy(()->requests.submit(created.request().serviceRequestId(),AccountPrincipal.fromAccount(account),metadata()))
                .hasMessageContaining("awaits configured MQTT dispatch");
        assertThat(jobs.count()).isZero();assertThat(commands.count()).isZero();
    }

    private Robot robot(com.fasterxml.jackson.databind.JsonNode capabilities){return robots.save(Robot.builder().currentMap(map).mapAssetHash(map.getAssetHash())
            .robotCode("MOVE-ROBOT-"+UUID.randomUUID()).robotName("Move robot").model("Test").protocolVersion(1)
            .capabilitiesJson(capabilities).status(RobotStatus.IDLE).batteryUnit(BatteryUnit.UNKNOWN).deviceTokenVersion(0).build());}
    private RequestMetadata metadata(){return new RequestMetadata("127.0.0.1","test","move-only-test");}
    private void cleanRuntime(){jdbc.execute("SET FOREIGN_KEY_CHECKS=0");for(String table:new String[]{"RobotCommandOutbox","WorkflowOrchestrationWork","JobEvent","JobStep","JobTask","Job","NavigationRequest","ServiceRequest","RobotMqttSession","Robot","Endpoint","Map","Floor","HospitalInformation","AuditLog","Account"})jdbc.execute("TRUNCATE TABLE `"+table+"`");jdbc.execute("SET FOREIGN_KEY_CHECKS=1");}
}
