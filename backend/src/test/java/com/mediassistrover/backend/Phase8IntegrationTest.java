package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.dto.setting.request.SystemSettingRequests;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.scheduler.*;
import com.mediassistrover.backend.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class Phase8IntegrationTest extends MySqlIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MockMvc mockMvc;
    @Autowired AccountRepository accountRepository;
    @Autowired RobotRepository robotRepository;
    @Autowired ServiceRequestRepository requestRepository;
    @Autowired JobRepository jobRepository;
    @Autowired WorkflowRepository workflowRepository;
    @Autowired SystemSettingService settingService;
    @Autowired RuntimeSettingService runtimeSettingService;
    @Autowired AuditQueryService auditQueryService;
    @Autowired ReportService reportService;
    @Autowired RobotService robotService;
    @Autowired OperationsMaintenanceService maintenanceService;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired RevokedTokenRepository revokedTokenRepository;
    @Autowired SystemSettingRepository settingRepository;
    @Autowired ApplicationContext applicationContext;

    private Account admin;

    @BeforeEach
    void setup() {
        cleanRuntimeData();
        admin = accountRepository.save(Account.builder().accountCode("ADMIN-P8").username("phase8.admin")
                .fullName("Phase 8 Admin").email("phase8.admin@example.test").role(Role.ADMIN)
                .authProvider(AuthProvider.LOCAL).passwordHash("unused").status(AccountStatus.ACTIVE)
                .passwordChangedAt(Instant.now()).build());
    }

    @AfterEach void cleanup() { cleanRuntimeData(); }

    @Test
    void systemSettingOverridesPropertyAndEveryMutationIsAudited() {
        var created = settingService.create(new SystemSettingRequests.Create(
                        "operations.phase8_test_threshold_seconds", Map.of("value", 45),
                        SystemSettingDataType.NUMBER, "operations", "Runtime threshold", false),
                admin.getAccountId(), metadata());
        assertThat(runtimeSettingService.getLong(created.settingKey(), 30, 10, 3600)).isEqualTo(45);

        settingService.update(created.systemSettingId(), new SystemSettingRequests.Update(
                        Map.of("value", 60), SystemSettingDataType.NUMBER, "OPERATIONS", "Updated", false),
                admin.getAccountId(), metadata());
        assertThat(runtimeSettingService.getLong(created.settingKey(), 30, 10, 3600)).isEqualTo(60);
        assertThat(auditQueryService.search(null, "SystemSetting", admin.getAccountId(), null, null,
                PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);

        settingService.delete(created.systemSettingId(), admin.getAccountId(), metadata());
        assertThat(runtimeSettingService.getLong(created.settingKey(), 30, 10, 3600)).isEqualTo(30);
        assertThat(auditQueryService.search("SYSTEM_SETTING_DELETED", null, null, null, null,
                PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
    }

    @Test
    void overviewAggregatesStatusesAndUsesTerminalJobsAsCompletionRateDenominator() {
        Robot robot = robotRepository.save(Robot.builder().robotCode("P8-R1").robotName("Robot 1").model("Test")
                .protocolVersion(1).capabilitiesJson(objectMapper.createArrayNode()).status(RobotStatus.IDLE)
                .batteryUnit(BatteryUnit.UNKNOWN).deviceTokenVersion(0).build());
        ServiceRequest request = requestRepository.save(ServiceRequest.builder().requestCode("P8-REQ")
                .requestType(ServiceRequestType.INFORMATION).sourceType(RequestSourceType.SYSTEM).priority(10)
                .status(ServiceRequestStatus.COMPLETED).requestDataJson(objectMapper.createObjectNode())
                .idempotencyKey("phase8-request").requestedAt(Instant.now()).build());
        Workflow workflow = workflowRepository.findAll().getFirst();
        jobRepository.save(Job.builder().workflow(workflow).serviceRequest(request).robot(robot).jobCode("P8-JOB")
                .status(JobStatus.SUCCEEDED).priority(10).inputJson(objectMapper.createObjectNode())
                .contextJson(objectMapper.createObjectNode()).workflowHash("a".repeat(64)).protocolVersion(1)
                .schemaVersion(1).graphVersion(1).queuedAt(Instant.now()).endedAt(Instant.now()).build());

        var overview = reportService.getOperationsOverview();
        assertThat(overview.jobsByStatus().get(JobStatus.SUCCEEDED)).isEqualTo(1);
        assertThat(overview.robotsByStatus().get(RobotStatus.IDLE)).isEqualTo(1);
        assertThat(overview.requestsByType().get(ServiceRequestType.INFORMATION)).isEqualTo(1);
        assertThat(overview.jobCompletionRatePercent()).isEqualTo(100.0);
        assertThat(overview.jobsByStatus()).containsKeys(JobStatus.QUEUED, JobStatus.RECOVERY_REQUIRED);
    }

    @Test
    void robotOfflineSchedulerBusinessRuleUsesRuntimeSettingBeforePropertyFallback() {
        var setting = settingRepository.findBySettingKey("operations.robot_offline_threshold_seconds").orElseThrow();
        settingService.update(setting.getSystemSettingId(), new SystemSettingRequests.Update(
                        Map.of("value", 60), SystemSettingDataType.NUMBER, "OPERATIONS", "Runtime threshold", false),
                admin.getAccountId(), metadata());
        Robot robot = robotRepository.saveAndFlush(Robot.builder().robotCode("P8-OFFLINE").robotName("Offline test")
                .model("Test").protocolVersion(1).capabilitiesJson(objectMapper.createArrayNode())
                .status(RobotStatus.IDLE).batteryUnit(BatteryUnit.UNKNOWN).deviceTokenVersion(0)
                .lastHeartbeatAt(Instant.now().minusSeconds(45)).build());

        assertThat(robotService.markHeartbeatTimeoutsOffline()).isZero();
        settingService.update(setting.getSystemSettingId(), new SystemSettingRequests.Update(
                        Map.of("value", 20), SystemSettingDataType.NUMBER, "OPERATIONS", "Runtime threshold", false),
                admin.getAccountId(), metadata());
        assertThat(robotService.markHeartbeatTimeoutsOffline()).isEqualTo(1);
        assertThat(robotRepository.findById(robot.getRobotId()).orElseThrow().getStatus()).isEqualTo(RobotStatus.OFFLINE);
    }

    @Test
    void expiredTokenCleanupIncludesExactBoundaryAndKeepsFutureTokens() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        refreshTokenRepository.saveAll(List.of(
                refresh("a".repeat(64), now),
                refresh("b".repeat(64), now.plusSeconds(1))));
        revokedTokenRepository.saveAll(List.of(
                revoked("phase8-expired", now.minusSeconds(1), now),
                revoked("phase8-future", now.plusSeconds(1), now)));

        assertThat(maintenanceService.cleanupExpiredTokens(now)).isEqualTo(2);
        assertThat(refreshTokenRepository.findAll()).extracting(RefreshToken::getTokenHash)
                .containsExactly("b".repeat(64));
        assertThat(revokedTokenRepository.findAll()).extracting(RevokedToken::getJti)
                .containsExactly("phase8-future");
    }

    @Test
    void migrationSeedsRetentionMetadataWithoutEnablingAutomaticDeletion() {
        assertThat(runtimeSettingService.getLong("retention.audit_log_days", -1, 1, 5000)).isEqualTo(1095);
        assertThat(runtimeSettingService.getLong("retention.robot_status_history_days", -1, 1, 5000)).isEqualTo(365);
        assertThat(runtimeSettingService.getLong("retention.robot_location_history_days", -1, 1, 5000)).isEqualTo(183);
    }

    @Test
    void testProfileDisablesEveryOperationsScheduler() {
        assertThat(applicationContext.getBeansOfType(ExpiredTokenCleanupScheduler.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(RobotOfflineScheduler.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(DueScheduleRunner.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(OverdueJobScheduler.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(JobStepTimeoutScheduler.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(QueuedJobDispatchScheduler.class)).isEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanReadOverview() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobsByStatus.QUEUED").exists());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void staffCannotReadAdminOverview() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports/overview"))
                .andExpect(status().isForbidden());
    }

    private RequestMetadata metadata() { return new RequestMetadata("127.0.0.1", "test", "phase8-test"); }

    private RefreshToken refresh(String hash, Instant expiresAt) {
        Instant issuedAt = expiresAt.minusSeconds(60);
        return RefreshToken.builder().account(admin).tokenHash(hash).tokenFamily(UUID.randomUUID())
                .issuedAt(issuedAt).expiresAt(expiresAt).createdAt(issuedAt).build();
    }

    private RevokedToken revoked(String jti, Instant expiresAt, Instant revokedAt) {
        return RevokedToken.builder().jti(jti).account(admin).expiresAt(expiresAt).revokedAt(revokedAt)
                .revocationReason(RevocationReason.EXPIRED).createdAt(revokedAt).build();
    }

    private void cleanRuntimeData() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : new String[]{"AlertRecipient", "Alert", "AuthVerification", "DeliveryItem",
                "DeliveryRequest", "EmergencyIncident", "JobEvent", "JobStep", "JobTask", "Job",
                "Schedule", "NavigationRequest", "EscortRequest", "ServiceRequest", "RobotLocationHistory",
                "RobotStatusHistory", "Robot", "AuditLog", "RevokedToken", "RefreshToken", "StaffProfile",
                "Account"}) jdbc.execute("TRUNCATE TABLE `" + table + "`");
        jdbc.update("DELETE FROM `SystemSetting` WHERE setting_key = ?", "operations.phase8_test_threshold_seconds");
        jdbc.update("UPDATE `SystemSetting` SET setting_value_json=JSON_OBJECT('value', 30), updated_by_account_id=NULL WHERE setting_key=?",
                "operations.robot_offline_threshold_seconds");
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
    }
}
