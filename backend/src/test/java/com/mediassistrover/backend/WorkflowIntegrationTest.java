package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkflowIntegrationTest extends MySqlIntegrationTest {
    @Autowired MockMvc mockMvc; @Autowired ObjectMapper objectMapper; @Autowired JdbcTemplate jdbc;
    @Autowired AccountRepository accountRepository; @Autowired RobotRepository robotRepository;
    @Autowired WorkflowRepository workflowRepository;
    @Autowired JwtTokenService jwtTokenService; @Autowired DeviceJwtTokenService deviceJwtTokenService;
    private Account admin; private String adminToken; private String staffToken; private String deviceToken;
    private final List<UUID> createdWorkflows=new ArrayList<>(); private UUID robotId;

    @BeforeEach void setup(){
        admin=accountRepository.save(Account.builder().accountCode("ADM-WF-"+UUID.randomUUID()).username("wf.admin."+UUID.randomUUID()).fullName("Workflow Admin").email("wf.admin."+UUID.randomUUID()+"@example.test").role(Role.ADMIN).authProvider(AuthProvider.LOCAL).passwordHash("unused").status(AccountStatus.ACTIVE).passwordChangedAt(Instant.now()).build());adminToken=jwtTokenService.issueAccessToken(admin).value();
        Account staff=accountRepository.save(Account.builder().accountCode("STF-WF-"+UUID.randomUUID()).username("wf.staff."+UUID.randomUUID()).fullName("Workflow Staff").email("wf.staff."+UUID.randomUUID()+"@example.test").role(Role.STAFF).authProvider(AuthProvider.LOCAL).passwordHash("unused").status(AccountStatus.ACTIVE).passwordChangedAt(Instant.now()).build());staffToken=jwtTokenService.issueAccessToken(staff).value();
        Robot robot=robotRepository.save(Robot.builder().robotCode("WF-ROBOT-"+UUID.randomUUID()).robotName("Workflow Sync Robot").model("Test").protocolVersion(1).capabilitiesJson(objectMapper.createObjectNode()).status(RobotStatus.IDLE).batteryUnit(BatteryUnit.UNKNOWN).deviceSecretHash("provisioned-for-test").deviceTokenVersion(0).build());robotId=robot.getRobotId();deviceToken=deviceJwtTokenService.issue(robot).value();
    }
    @AfterEach void cleanup(){jdbc.execute("SET FOREIGN_KEY_CHECKS=0");for(UUID id:createdWorkflows)jdbc.update("DELETE FROM `Workflow` WHERE workflow_id=UNHEX(REPLACE(?,'-',''))",id.toString());jdbc.update("UPDATE `Workflow` SET status='DRAFT', accepts_new_jobs=FALSE, compiled_definition_json=NULL, definition_hash=NULL, published_at=NULL, published_by_account_id=NULL WHERE workflow_code IN ('WF_PATIENT_ESCORT','WF_INFORMATION_ASSISTANCE','WF_SECURE_MEDICAL_DELIVERY','WF_EMERGENCY_SOS_RESPONSE')");jdbc.update("DELETE FROM `Robot` WHERE robot_id=UNHEX(REPLACE(?,'-',''))",robotId.toString());jdbc.update("DELETE FROM `AuditLog` WHERE account_id=UNHEX(REPLACE(?,'-',''))",admin.getAccountId().toString());jdbc.update("DELETE FROM `Account` WHERE username LIKE 'wf.admin.%' OR username LIKE 'wf.staff.%'");jdbc.execute("SET FOREIGN_KEY_CHECKS=1");}

    @Test void catalogContainsExactlyElevenApprovedStepTypesAndFourMvpTemplates() throws Exception {
        assertThat(Arrays.asList(StepType.values())).extracting(StepType::getStepTypeCode).containsExactlyInAnyOrder("MOVE","INTERACT","DATA_SERVICE","AUTHENTICATE","CONTROL_HARDWARE","HANDLE_PAYLOAD","EXECUTE_SENSING","NOTIFY","WAIT","CHECK_CONDITION","SET_CONTEXT");
        assertThat(Arrays.stream(StepType.values()).map(Enum::name)).doesNotContain("CALL_WORKFLOW");
        assertThat(workflowRepository.findAll()).extracting(Workflow::getWorkflowCode).contains("WF_PATIENT_ESCORT","WF_INFORMATION_ASSISTANCE","WF_SECURE_MEDICAL_DELIVERY","WF_EMERGENCY_SOS_RESPONSE");
        Map<String,Integer> expectedSteps=Map.of("WF_PATIENT_ESCORT",5,"WF_INFORMATION_ASSISTANCE",3,"WF_SECURE_MEDICAL_DELIVERY",25,"WF_EMERGENCY_SOS_RESPONSE",7);
        for(Workflow workflow:workflowRepository.findAll().stream().filter(w->expectedSteps.containsKey(w.getWorkflowCode())).toList()) {
            int count=expectedSteps.get(workflow.getWorkflowCode());
            if(workflow.getSchemaVersion()==3 && "WF_SECURE_MEDICAL_DELIVERY".equals(workflow.getWorkflowCode())) count+=7; // V25 group subgraph; schema 2 retains its 25 steps.
            mockMvc.perform(post("/api/v1/admin/workflows/{id}/validate",workflow.getWorkflowId()).header("Authorization",bearer(adminToken)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.stepCount").value(count));
        }
        mockMvc.perform(get("/api/v1/admin/workflows/step-types").header("Authorization",bearer(staffToken))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(11));
        mockMvc.perform(get("/api/v1/workflows/step-types").header("Authorization",bearer(staffToken))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(11));
        mockMvc.perform(patch("/api/v1/admin/workflows/step-types/MOVE/enabled").header("Authorization",bearer(adminToken)).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isNotFound());
    }

    @Test void fullDefinitionLifecycleIsValidatedImmutablePublishedAndCloneable() throws Exception {
        UUID workflowId=createWorkflow();createdWorkflows.add(workflowId);
        UUID taskId=UUID.fromString(response(post("/api/v1/admin/workflows/{id}/tasks",workflowId).header("Authorization",bearer(adminToken)).contentType(MediaType.APPLICATION_JSON).content("{\"taskCode\":\"MAIN\",\"taskName\":\"Main\",\"sequenceNo\":1,\"configJson\":{}}"),201).get("taskId").asText());
        String stepBody="""
            {"taskId":"%s","stepTypeCode":"NOTIFY","stepCode":"DONE","stepName":"Done","sequenceNo":1,
             "enabled":true,"paramsJson":{"notification_type":"TEST_DONE","recipients":[]},"inputMappingJson":{},"timeoutSec":60,"maxAttempts":1,
             "retryDelaySec":0,"retryCodesJson":[],"maxVisits":1,"auditLevel":"BASIC"}
            """.formatted(taskId);
        UUID stepId=UUID.fromString(response(post("/api/v1/admin/workflows/{id}/steps",workflowId).header("Authorization",bearer(adminToken)).contentType(MediaType.APPLICATION_JSON).content(stepBody),201).get("stepId").asText());
        response(put("/api/v1/admin/workflows/{id}/start-step",workflowId).header("Authorization",bearer(adminToken)).contentType(MediaType.APPLICATION_JSON).content("{\"startStepId\":\"%s\"}".formatted(stepId)),200);
        mockMvc.perform(post("/api/v1/admin/workflows/{id}/validate",workflowId).header("Authorization",bearer(adminToken))).andExpect(status().isUnprocessableEntity());
        for(String outcome:List.of("SUCCESS","FAILURE","TIMEOUT")){String terminal=outcome.equals("SUCCESS")?"SUCCEEDED":"FAILED";response(post("/api/v1/admin/workflows/{id}/transitions",workflowId).header("Authorization",bearer(adminToken)).contentType(MediaType.APPLICATION_JSON).content("{\"fromStepId\":\"%s\",\"outcome\":\"%s\",\"priority\":1,\"defaultTransition\":true,\"terminalStatus\":\"%s\"}".formatted(stepId,outcome,terminal)),201);}
        JsonNode first=response(post("/api/v1/admin/workflows/{id}/validate",workflowId).header("Authorization",bearer(adminToken)),200);String hash=first.get("definitionHash").asText();assertThat(hash).hasSize(64);
        assertThat(response(post("/api/v1/admin/workflows/{id}/validate",workflowId).header("Authorization",bearer(adminToken)),200).get("definitionHash").asText()).isEqualTo(hash);
        response(post("/api/v1/admin/workflows/{id}/publish",workflowId).header("Authorization",bearer(adminToken)),200);
        mockMvc.perform(put("/api/v1/admin/workflows/{id}",workflowId).header("Authorization",bearer(adminToken)).contentType(MediaType.APPLICATION_JSON).content(workflowBody())).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/v1/admin/workflows/{id}",workflowId).header("Authorization",bearer(staffToken))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/workflows/published/{id}",workflowId).header("Authorization",bearer(staffToken))).andExpect(status().isOk()).andExpect(jsonPath("$.workflow.status").value("PUBLISHED"));
        mockMvc.perform(get("/api/v1/workflows/published").param("serviceType","TEST").header("Authorization",bearer(staffToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].workflowId").value(workflowId.toString()))
                .andExpect(jsonPath("$.content[0].definitionHash").doesNotExist())
                .andExpect(jsonPath("$.content[0].publishedByAccountId").doesNotExist());
        mockMvc.perform(get("/api/v1/workflows/published/{id}",workflowId).header("Authorization",bearer(staffToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.workflow.status").value("PUBLISHED"));
        mockMvc.perform(get("/api/v1/pi/workflows/{id}",workflowId).header("Authorization",bearer(deviceToken))).andExpect(status().isOk()).andExpect(jsonPath("$.workflow.definitionHash").value(hash));
        JsonNode clone=response(post("/api/v1/admin/workflows/{id}/clone",workflowId).header("Authorization",bearer(adminToken)).contentType(MediaType.APPLICATION_JSON).content("{\"workflowName\":\"Cloned Workflow\"}"),201);UUID cloneId=UUID.fromString(clone.path("workflow").path("workflowId").asText());createdWorkflows.add(cloneId);assertThat(clone.path("workflow").path("versionNo").asInt()).isEqualTo(2);assertThat(clone.path("workflow").path("status").asText()).isEqualTo("DRAFT");assertThat(clone.path("steps").size()).isEqualTo(1);
        response(post("/api/v1/admin/workflows/{id}/archive",workflowId).header("Authorization",bearer(adminToken)),200);
        mockMvc.perform(get("/api/v1/workflows/published/{id}",workflowId).header("Authorization",bearer(staffToken))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/pi/workflows/{id}",workflowId).header("Authorization",bearer(deviceToken))).andExpect(status().isNotFound());
    }

    @Test void hardwareStepAutomaticRetryIsRejected() throws Exception {
        UUID workflowId=createWorkflow();createdWorkflows.add(workflowId);UUID taskId=UUID.fromString(response(post("/api/v1/admin/workflows/{id}/tasks",workflowId).header("Authorization",bearer(adminToken)).contentType(MediaType.APPLICATION_JSON).content("{\"taskCode\":\"MAIN\",\"taskName\":\"Main\",\"sequenceNo\":1,\"configJson\":{}}"),201).get("taskId").asText());
        String body="{\"taskId\":\"%s\",\"stepTypeCode\":\"CONTROL_HARDWARE\",\"stepCode\":\"LOCK\",\"stepName\":\"Lock\",\"sequenceNo\":1,\"enabled\":true,\"paramsJson\":{\"action\":\"LOCK\"},\"inputMappingJson\":{},\"timeoutSec\":30,\"maxAttempts\":2,\"retryDelaySec\":0,\"retryCodesJson\":[],\"maxVisits\":1,\"auditLevel\":\"FULL\"}".formatted(taskId);UUID stepId=UUID.fromString(response(post("/api/v1/admin/workflows/{id}/steps",workflowId).header("Authorization",bearer(adminToken)).contentType(MediaType.APPLICATION_JSON).content(body),201).get("stepId").asText());response(put("/api/v1/admin/workflows/{id}/start-step",workflowId).header("Authorization",bearer(adminToken)).contentType(MediaType.APPLICATION_JSON).content("{\"startStepId\":\"%s\"}".formatted(stepId)),200);for(String outcome:List.of("SUCCESS","FAILURE","TIMEOUT"))response(post("/api/v1/admin/workflows/{id}/transitions",workflowId).header("Authorization",bearer(adminToken)).contentType(MediaType.APPLICATION_JSON).content("{\"fromStepId\":\"%s\",\"outcome\":\"%s\",\"priority\":1,\"defaultTransition\":true,\"terminalStatus\":\"FAILED\"}".formatted(stepId,outcome)),201);mockMvc.perform(post("/api/v1/admin/workflows/{id}/validate",workflowId).header("Authorization",bearer(adminToken))).andExpect(status().isUnprocessableEntity());
    }

    private UUID createWorkflow() throws Exception {JsonNode n=response(post("/api/v1/admin/workflows").header("Authorization",bearer(adminToken)).contentType(MediaType.APPLICATION_JSON).content(workflowBody()),201);return UUID.fromString(n.get("workflowId").asText());}
    private String workflowBody(){return """
        {"workflowCode":"TEST_DYNAMIC_%s","versionNo":1,"workflowName":"Dynamic Test","serviceType":"TEST",
         "inputSchemaJson":{"type":"object"},"inputUiJson":{},"defaultInputsJson":{},"outputMappingJson":{},
         "requiredCapabilitiesJson":[],"maxDurationSec":600,"maxTotalVisits":10,"graphVersion":3,"schemaVersion":2}
        """.formatted(UUID.randomUUID().toString().replace("-","").toUpperCase());}
    private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,int expected)throws Exception{MvcResult r=mockMvc.perform(req).andExpect(status().is(expected)).andReturn();return objectMapper.readTree(r.getResponse().getContentAsString());}
    private String bearer(String token){return "Bearer "+token;}
}
