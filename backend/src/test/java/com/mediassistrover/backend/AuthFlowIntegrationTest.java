package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.mediassistrover.backend.entity.Account;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthFlowIntegrationTest extends MySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    @Autowired StaffProfileRepository profiles;
    @Autowired PasswordEncoder encoder;

    @Test void adminCreatesStaffWhoMustChangePasswordAndCanBeDeactivated() throws Exception {
        Account admin=account(Role.ADMIN);String adminToken=login(admin.getUsername(),"Original123!").path("accessToken").asText();
        String suffix=UUID.randomUUID().toString();
        String body=json.writeValueAsString(Map.of("username","staff."+suffix,"temporaryPassword","Temporary123!","fullName","Staff One","email",suffix+"@example.test","staffCode","EMP-"+suffix));
        JsonNode created=response(post("/api/v1/admin/staff").header("Authorization","Bearer "+adminToken).contentType(MediaType.APPLICATION_JSON).content(body),201);
        UUID staffId=UUID.fromString(created.path("staffProfileId").asText());
        assertThat(created.path("account").path("mustChangePassword").asBoolean()).isTrue();
        assertThat(created.toString()).doesNotContain("Temporary123!","passwordHash","password_hash");
        Account staff=accounts.findByUsernameIgnoreCase("staff."+suffix).orElseThrow();
        assertThat(encoder.matches("Temporary123!",staff.getPasswordHash())).isTrue();
        JsonNode tokens=login(staff.getUsername(),"Temporary123!");String token=tokens.path("accessToken").asText();
        assertThat(tokens.path("mustChangePassword").asBoolean()).isTrue();
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/refresh").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("refreshToken",tokens.path("refreshToken").asText())))).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/auth/change-password").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("currentPassword","Temporary123!","newPassword","Changed123!")))).andExpect(status().isNoContent());
        JsonNode active=login(staff.getUsername(),"Changed123!");assertThat(active.path("mustChangePassword").asBoolean()).isFalse();
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+active.path("accessToken").asText())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/staff").header("Authorization","Bearer "+active.path("accessToken").asText())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/staff").header("Authorization","Bearer "+adminToken).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        for(int i=0;i<2;i++)mvc.perform(delete("/api/v1/admin/staff/{id}",staffId).header("Authorization","Bearer "+adminToken)).andExpect(status().isNoContent());
        assertThat(profiles.findById(staffId).orElseThrow().isActive()).isFalse();
        assertThat(accounts.findById(staff.getAccountId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.INACTIVE);
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+active.path("accessToken").asText())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("refreshToken",active.path("refreshToken").asText())))).andExpect(status().isUnauthorized());
    }
    @Test void refreshRotationDetectsReuseAndLogoutIsIdempotent() throws Exception {
        Account staff=account(Role.STAFF);JsonNode initial=login(staff.getUsername(),"Original123!");
        String old=initial.path("refreshToken").asText();
        JsonNode rotated=response(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("refreshToken",old))),200);
        assertThat(rotated.path("refreshToken").asText()).isNotEqualTo(old);
        for(String refresh:List.of(old,rotated.path("refreshToken").asText()))mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("refreshToken",refresh)))).andExpect(status().isUnauthorized());
        String token=rotated.path("accessToken").asText();String body=json.writeValueAsString(Map.of("refreshToken",rotated.path("refreshToken").asText()));
        for(int i=0;i<2;i++)mvc.perform(post("/api/v1/auth/logout").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void registrationAndApprovalEndpointsAreRemoved() throws Exception {
        Account admin=account(Role.ADMIN);String token=login(admin.getUsername(),"Original123!").path("accessToken").asText();
        mvc.perform(post("/api/v1/auth/register/patient").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/accounts/pending").header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/admin/accounts/{id}/approve",admin.getAccountId()).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
    }
    private Account account(Role role){String key=UUID.randomUUID().toString();return accounts.save(Account.builder().accountCode(key).username(key).fullName("Test").role(role).authProvider(AuthProvider.LOCAL).passwordHash(encoder.encode("Original123!")).status(AccountStatus.ACTIVE).build());}
    private JsonNode login(String username,String password)throws Exception{return response(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",username,"password",password))),200);}
    private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,int expected)throws Exception{MvcResult result=mvc.perform(request).andExpect(status().is(expected)).andReturn();return json.readTree(result.getResponse().getContentAsString());}
}
