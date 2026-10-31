package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.JwtTokenService;
import com.mediassistrover.backend.mqtt.MqttProperties;
import com.mediassistrover.backend.mapping.MappingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RobotDemoTemplateIntegrationTest extends MySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    @Autowired RobotRepository robots;
    @Autowired JwtTokenService tokens;
    @Autowired MqttProperties mqtt;
    @Autowired MappingProperties mapping;

    @Test @Transactional void templatesPublishAndSpeechApiCreatesRealJob() throws Exception {
        boolean enabled=mqtt.isEnabled(),dispatch=mqtt.isDispatchEnabled(),maps=mapping.isEnabled();
        var oldTypes=new HashSet<>(mqtt.getEnabledStepTypes());var oldRobots=new HashSet<>(mqtt.getRobotIds());
        try {
            mqtt.setEnabled(true);mqtt.setDispatchEnabled(true);mapping.setEnabled(true);
            mqtt.setEnabledStepTypes(new HashSet<>(List.of("INTERACT","WAIT","EXECUTE_SENSING")));
            var id=UUID.randomUUID().toString();
            var account=accounts.save(Account.builder().accountCode("DEMO-"+id).username("demo."+id).fullName("Demo test")
                .role(Role.ADMIN).authProvider(AuthProvider.LOCAL).status(AccountStatus.ACTIVE).passwordHash("unused").passwordChangedAt(Instant.now()).build());
            String bearer="Bearer "+tokens.issueAccessToken(account).value();
            var robot=robots.save(Robot.builder().robotCode("DEMO-"+id).robotName("Test").model("Test").protocolVersion(1).capabilitiesJson(json.createArrayNode()).status(RobotStatus.IDLE).build());
            mqtt.getRobotIds().add(robot.getRobotId());
            String speechId=null;
            for(String file:List.of("speech-workflow.json","mapping-local-workflow.json","mapping-workflow.json")) {
                String body=new String(getClass().getResourceAsStream("/static/"+file).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                if(file.equals("mapping-workflow.json")) {
                    var definition=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(body);
                    ((com.fasterxml.jackson.databind.node.ObjectNode)definition.path("workflow")).put("workflowCode","WF_MAPPING_ACCEPTANCE");
                    body=json.writeValueAsString(definition);
                }
                var created=mvc.perform(post("/api/v1/admin/workflows/graph").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated()).andReturn();
                String wf=json.readTree(created.getResponse().getContentAsString()).path("workflow").path("workflowId").asText();
                mvc.perform(post("/api/v1/admin/workflows/{id}/validate",wf).header("Authorization",bearer)).andExpect(status().isOk());
                mvc.perform(post("/api/v1/admin/workflows/{id}/publish",wf).header("Authorization",bearer)).andExpect(status().isOk());
                if(file.startsWith("speech"))speechId=wf;
            }
            var request=json.createObjectNode().put("workflowId",speechId).put("robotId",robot.getRobotId().toString()).put("text","Xin chào").put("languageCode","vi");
            mvc.perform(post("/api/v1/speech-jobs").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.jobId").isNotEmpty());
        } finally {
            mqtt.setEnabled(enabled);mqtt.setDispatchEnabled(dispatch);mapping.setEnabled(maps);
            mqtt.setEnabledStepTypes(oldTypes);mqtt.getRobotIds().clear();mqtt.getRobotIds().addAll(oldRobots);
        }
    }
}
