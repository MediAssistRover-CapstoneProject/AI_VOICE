package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mediassistrover.backend.entity.Account;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.JwtTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkflowGraphIntegrationTest extends MySqlIntegrationTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired AccountRepository accounts; @Autowired WorkflowRepository workflows;
    @Autowired JwtTokenService jwt;
    private UUID createdWorkflowId;
    private UUID adminAccountId;
    private String token(){String key=UUID.randomUUID().toString();Account admin=accounts.save(Account.builder().accountCode(key).username(key).fullName("Graph Admin").role(Role.ADMIN).authProvider(AuthProvider.LOCAL).status(AccountStatus.ACTIVE).passwordHash("unused").build());adminAccountId=admin.getAccountId();return jwt.issueAccessToken(admin).value();}

    @AfterEach void cleanCreatedGraphAndAccount(){
        if(createdWorkflowId==null&&adminAccountId==null)return;
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        try{
            if(adminAccountId!=null)jdbc.update("DELETE FROM AuditLog WHERE account_id=UNHEX(REPLACE(?,'-',''))",adminAccountId.toString());
            if(createdWorkflowId!=null){
                String workflowId=createdWorkflowId.toString();
                jdbc.update("DELETE FROM StepTransition WHERE workflow_id=UNHEX(REPLACE(?,'-',''))",workflowId);
                jdbc.update("DELETE FROM Step WHERE workflow_id=UNHEX(REPLACE(?,'-',''))",workflowId);
                jdbc.update("DELETE FROM Task WHERE workflow_id=UNHEX(REPLACE(?,'-',''))",workflowId);
                jdbc.update("DELETE FROM Workflow WHERE workflow_id=UNHEX(REPLACE(?,'-',''))",workflowId);
            }
            if(adminAccountId!=null)jdbc.update("DELETE FROM Account WHERE account_id=UNHEX(REPLACE(?,'-',''))",adminAccountId.toString());
        }finally{jdbc.execute("SET FOREIGN_KEY_CHECKS=1");}
    }

    @Test void graphReplacementIsAtomicAndWorkflowCodeIsImmutable()throws Exception{
        String token=token();ObjectNode graph=graph();String code="AGG_"+UUID.randomUUID().toString().replace("-","").toUpperCase();
        ObjectNode definition=definition(code);ObjectNode request=json.createObjectNode();request.set("workflow",definition);request.set("graph",graph);
        JsonNode created=response(post("/api/v1/admin/workflows/graph").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(request.toString()),201);
        String id=created.path("workflow").path("workflowId").asText();createdWorkflowId=UUID.fromString(id);String originalStep=created.path("steps").get(0).path("stepId").asText();
        ObjectNode invalid=graph.deepCopy();((ObjectNode)invalid.path("transitions").get(0)).put("toStepCode","UNKNOWN").remove("terminalStatus");
        mvc.perform(put("/api/v1/admin/workflows/{id}/graph",id).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(invalid.toString())).andExpect(status().isUnprocessableEntity());
        JsonNode unchanged=response(get("/api/v1/admin/workflows/{id}",id).header("Authorization","Bearer "+token),200);
        assertThat(unchanged.path("steps").get(0).path("stepId").asText()).isEqualTo(originalStep);
        ObjectNode zero=graph.deepCopy();((ObjectNode)zero.path("transitions").get(0)).put("priority",0);
        mvc.perform(put("/api/v1/admin/workflows/{id}/graph",id).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(zero.toString())).andExpect(status().isBadRequest());
        ObjectNode badType=graph.deepCopy();((ObjectNode)badType.path("steps").get(0)).put("stepType","CALL_WORKFLOW");
        mvc.perform(put("/api/v1/admin/workflows/{id}/graph",id).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(badType.toString())).andExpect(status().isBadRequest());
        ObjectNode jsonPath=graph.deepCopy();((ObjectNode)jsonPath.path("steps").get(0).path("inputMappingJson")).set("target",json.createObjectNode().put("$ref","$.request.endpointId"));
        mvc.perform(put("/api/v1/admin/workflows/{id}/graph",id).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(jsonPath.toString())).andExpect(status().isUnprocessableEntity());
        JsonNode replaced=response(put("/api/v1/admin/workflows/{id}/graph",id).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(graph.toString()),200);
        assertThat(replaced.path("steps").get(0).path("stepId").asText()).isNotEqualTo(originalStep);
        definition.put("workflowCode",code+"_CHANGED");
        mvc.perform(put("/api/v1/admin/workflows/{id}",id).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(definition.toString())).andExpect(status().isConflict());
        response(post("/api/v1/admin/workflows/{id}/validate",id).header("Authorization","Bearer "+token),200);
        response(post("/api/v1/admin/workflows/{id}/publish",id).header("Authorization","Bearer "+token),200);
        mvc.perform(put("/api/v1/admin/workflows/{id}/graph",id).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(graph.toString())).andExpect(status().isUnprocessableEntity());
    }

    @Test @Transactional void allEightSeedGraphsValidateWithoutStepTypeTable()throws Exception{
        String token=token();Set<String> codes=Set.of("WF_PATIENT_ESCORT","WF_INFORMATION_ASSISTANCE","WF_SECURE_MEDICAL_DELIVERY","WF_EMERGENCY_SOS_RESPONSE","WF_CROWD_MONITORING","WF_FALL_RESPONSE","WF_RESTRICTED_MONITORING","WF_NAVIGATION");
        var seeded=workflows.findAll().stream().filter(w->w.getSchemaVersion()==2&&codes.contains(w.getWorkflowCode())).toList();assertThat(seeded).hasSize(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='StepType'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='Account' AND column_name='account_id'",String.class)).isEqualTo("binary");
        for(var workflow:seeded)response(post("/api/v1/admin/workflows/{id}/validate",workflow.getWorkflowId()).header("Authorization","Bearer "+token),200);
    }
    @Test @Transactional void semanticContractBlocksUnsupportedHandlersAndUndeclaredInputs()throws Exception{
        String token=token();
        ObjectNode unsupported=graph();ObjectNode unsupportedStep=(ObjectNode)unsupported.path("steps").get(0);
        unsupportedStep.put("stepType","DATA_SERVICE");unsupportedStep.set("paramsJson",json.createObjectNode().put("service_key","NOT_IMPLEMENTED"));
        mvc.perform(post("/api/v1/admin/workflows/graph").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content(graphRequest("CONTRACT_BAD_HANDLER_"+UUID.randomUUID().toString().replace("-","").toUpperCase(),unsupported).toString()))
                .andExpect(status().isUnprocessableEntity());

        ObjectNode undeclared=graph();((ObjectNode)undeclared.path("steps").get(0).path("inputMappingJson")).set("event_id",json.createObjectNode().put("$ref","/inputs/event_id"));
        mvc.perform(post("/api/v1/admin/workflows/graph").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content(graphRequest("CONTRACT_BAD_INPUT_"+UUID.randomUUID().toString().replace("-","").toUpperCase(),undeclared).toString()))
                .andExpect(status().isUnprocessableEntity());
    }
    private ObjectNode definition(String code){ObjectNode n=json.createObjectNode();n.put("workflowCode",code).put("versionNo",1).put("workflowName","Aggregate test").put("serviceType","TEST").put("maxDurationSec",600).put("maxTotalVisits",10).put("graphVersion",3).put("schemaVersion",2);n.set("inputSchemaJson",json.createObjectNode().put("type","object"));for(String f:List.of("inputUiJson","defaultInputsJson","outputMappingJson"))n.set(f,json.createObjectNode());n.set("requiredCapabilitiesJson",json.createArrayNode());return n;}
    private ObjectNode graph(){ObjectNode n=json.createObjectNode().put("startStepCode","WAIT_ONCE");n.putArray("tasks").addObject().put("taskCode","MAIN").put("taskName","Main").put("sequenceNo",1).set("configJson",json.createObjectNode());ObjectNode s=n.putArray("steps").addObject().put("taskCode","MAIN").put("stepType","WAIT").put("stepCode","WAIT_ONCE").put("stepName","Wait once").put("sequenceNo",1).put("timeoutSec",30).put("maxAttempts",1).put("retryDelaySec",0).put("maxVisits",1).put("auditLevel","BASIC");s.set("paramsJson",json.createObjectNode().put("wait_type","DURATION").put("duration_sec",1));s.set("inputMappingJson",json.createObjectNode());s.set("retryCodesJson",json.createArrayNode());ArrayNode transitions=n.putArray("transitions");for(String outcome:List.of("SUCCESS","FAILURE","TIMEOUT"))transitions.addObject().put("fromStepCode","WAIT_ONCE").put("outcome",outcome).put("priority",1).put("defaultTransition",true).put("terminalStatus",outcome.equals("SUCCESS")?"SUCCEEDED":"FAILED");return n;}
    private ObjectNode graphRequest(String code,ObjectNode graph){ObjectNode request=json.createObjectNode();request.set("workflow",definition(code));request.set("graph",graph);return request;}
    private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,int expected)throws Exception{return json.readTree(mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());}
}
