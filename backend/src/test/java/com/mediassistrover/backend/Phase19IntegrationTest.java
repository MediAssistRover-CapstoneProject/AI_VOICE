package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.JwtTokenService;
import com.mediassistrover.backend.service.HospitalInformationQueryService;
import com.mediassistrover.backend.service.impl.DataServiceStepHandler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class Phase19IntegrationTest extends MySqlIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountRepository accountRepository;
    @Autowired HospitalInformationRepository hospitalRepository;
    @Autowired HospitalInformationArticleRepository articleRepository;
    @Autowired HospitalInformationQueryService queryService;
    @Autowired DataServiceStepHandler dataServiceStepHandler;
    @Autowired JwtTokenService jwtTokenService;

    private Account admin;
    private Account staff;
    private HospitalInformation hospital;
    private String adminToken;
    private String staffToken;

    @BeforeEach void setup() {
        clean();
        admin = accountRepository.save(account("P19-ADMIN", "p19.admin", Role.ADMIN));
        staff = accountRepository.save(account("P19-STAFF", "p19.staff", Role.STAFF));
        hospital = hospitalRepository.save(HospitalInformation.builder().updatedBy(admin)
                .infoCategory("HOSPITAL_PROFILE").title("MediAssist Hospital").content("Profile")
                .languageCode("vi-VN").keywordsJson(objectMapper.createArrayNode().add("hospital"))
                .status(HospitalInformationStatus.PUBLISHED).publicVisible(true).build());
        adminToken = jwtTokenService.issueAccessToken(admin).value();
        staffToken = jwtTokenService.issueAccessToken(staff).value();
    }

    @AfterEach void teardown() { clean(); }

    @Test void adminCrudApprovalAndStaffReadAreEnforced() throws Exception {
        JsonNode created = json(mockMvc.perform(post(path()).header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content(articleJson("PAYMENT", true, null, null)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT")).andReturn());
        UUID articleId = UUID.fromString(created.path("articleId").asText());

        mockMvc.perform(post(path()).header("Authorization", bearer(staffToken))
                        .contentType(MediaType.APPLICATION_JSON).content(articleJson("OTHER", true, null, null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(path()).header("Authorization", bearer(staffToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(post(path()+"/{id}/publish", articleId).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
        assertThat(queryService.query("Quầy thanh toán ở đâu?", "vi-VN").found()).isTrue();

        mockMvc.perform(put(path()+"/{id}", articleId).header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content(articleJson("PAYMENT", true, null, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
        assertThat(queryService.query("Quầy thanh toán ở đâu?", "vi-VN").found()).isFalse();
    }

    @Test void queryUsesOnlyApprovedPublicEffectiveContentAndReturnsFallback() throws Exception {
        UUID articleId = createAndPublish("PHARMACY", true, null, null,
                "Nhà thuốc ở đâu?", "Nhà thuốc ở tầng 1 cạnh quầy thanh toán.");
        createAndPublish("INTERNAL", false, null, null,
                "Mã nội bộ là gì?", "SECRET");
        createAndPublish("EXPIRED", true, Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600),
                "Nội dung cũ?", "OLD");

        var answer = queryService.query("Cho tôi hỏi nhà thuốc nằm ở đâu", "vi_VN");
        assertThat(answer.found()).isTrue();
        assertThat(answer.answer()).contains("tầng 1");
        assertThat(answer.source().articleId()).isEqualTo(articleId);
        assertThat(queryService.query("Mã nội bộ là gì?", "vi-VN").found()).isFalse();
        assertThat(queryService.query("Nội dung cũ?", "vi-VN").found()).isFalse();
        assertThat(queryService.query("Câu hỏi hoàn toàn không liên quan", "vi-VN").fallbackCode())
                .isEqualTo("INFORMATION_UNAVAILABLE");
    }

    @Test void dataServiceReturnsAnswerSourceAndCompatibilityItems() throws Exception {
        createAndPublish("LAB", true, null, null, "Phòng xét nghiệm ở đâu?", "Phòng xét nghiệm ở tầng 2.");
        JobStep step = mock(JobStep.class);
        when(step.getOperationKey()).thenReturn("job-step:test");
        when(step.getStepCodeSnapshot()).thenReturn("QUERY");
        JsonNode inputs = objectMapper.readTree("""
                {"service_key":"QUERY_HOSPITAL_INFORMATION","question":"Phòng xét nghiệm ở đâu?","language_code":"vi-VN"}
                """);
        var execution = dataServiceStepHandler.execute(step, inputs);
        assertThat(execution.completed()).isTrue();
        assertThat(execution.result().path("data").path("found").asBoolean()).isTrue();
        assertThat(execution.result().path("data").path("answer").asText()).contains("tầng 2");
        assertThat(execution.result().path("data").path("source").path("article_code").asText()).isEqualTo("LAB");
        assertThat(execution.result().path("data").path("items")).hasSize(1);
    }

    @Test void schemaUsesBinaryUuidAndPreservesHospitalFloorBoundary() {
        String articleType = jdbc.queryForObject("""
                select column_type from information_schema.columns
                where table_schema=database() and table_name='HospitalInformationArticle' and column_name='article_id'
                """, String.class);
        String hospitalFkType = jdbc.queryForObject("""
                select column_type from information_schema.columns
                where table_schema=database() and table_name='HospitalInformationArticle' and column_name='hospital_information_id'
                """, String.class);
        assertThat(articleType.toLowerCase()).isEqualTo("binary(16)");
        assertThat(hospitalFkType.toLowerCase()).isEqualTo("binary(16)");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name='Floor' and column_name='hospital_information_id'", Integer.class)).isEqualTo(1);
    }

    private UUID createAndPublish(String code, boolean visible, Instant from, Instant until,
                                  String question, String answer) throws Exception {
        JsonNode created = json(mockMvc.perform(post(path()).header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleJson(code, visible, from, until, question, answer)))
                .andExpect(status().isCreated()).andReturn());
        UUID id = UUID.fromString(created.path("articleId").asText());
        mockMvc.perform(post(path()+"/{id}/publish", id).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
        return id;
    }
    private String articleJson(String code, boolean visible, Instant from, Instant until) throws Exception {
        return articleJson(code, visible, from, until, "Quầy thanh toán ở đâu?", "Quầy thanh toán ở tầng 1.");
    }
    private String articleJson(String code, boolean visible, Instant from, Instant until,
                               String question, String answer) throws Exception {
        var node=objectMapper.createObjectNode().put("articleCode",code).put("category","FACILITY_LOCATION")
                .put("canonicalQuestion",question).put("title",question).put("answer",answer)
                .put("languageCode","vi-VN").put("publicVisible",visible);
        node.putArray("keywordsJson").add(code.toLowerCase()).add("vị trí");
        if(from!=null)node.put("effectiveFrom",from.toString());if(until!=null)node.put("effectiveUntil",until.toString());
        return objectMapper.writeValueAsString(node);
    }
    private String path(){return "/api/v1/hospital-information/"+hospital.getHospitalInformationId()+"/articles";}
    private JsonNode json(org.springframework.test.web.servlet.MvcResult result) throws Exception {return objectMapper.readTree(result.getResponse().getContentAsString());}
    private String bearer(String token){return "Bearer "+token;}
    private Account account(String code,String username,Role role){return Account.builder().accountCode(code).username(username).fullName(username).email(username+"@example.test").role(role).authProvider(AuthProvider.LOCAL).passwordHash("unused").status(AccountStatus.ACTIVE).passwordChangedAt(Instant.now()).build();}
    private void clean(){jdbc.execute("SET FOREIGN_KEY_CHECKS=0");for(String table:new String[]{"HospitalInformationArticle","Floor","HospitalInformation","AuditLog","RevokedToken","RefreshToken","StaffProfile","Account"})jdbc.execute("TRUNCATE TABLE `"+table+"`");jdbc.execute("SET FOREIGN_KEY_CHECKS=1");}
}
