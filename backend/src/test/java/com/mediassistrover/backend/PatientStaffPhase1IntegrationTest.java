package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.DeviceJwtTokenService;
import com.mediassistrover.backend.security.JwtTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PatientStaffPhase1IntegrationTest extends MySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountRepository accountRepository;
    @Autowired StaffProfileRepository staffProfileRepository;
    @Autowired HospitalInformationRepository hospitalRepository;
    @Autowired FloorRepository floorRepository;
    @Autowired RoomRepository roomRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired RobotRepository robotRepository;
    @Autowired JwtTokenService jwtTokenService;
    @Autowired DeviceJwtTokenService deviceJwtTokenService;

    private Account staff;
    private Account admin;
    private StaffProfile profile;
    private Room room;

    @BeforeEach
    void setUp() {
        clean();
        staff = account("P1-STAFF", Role.STAFF);
        admin = account("P1-ADMIN", Role.ADMIN);
        profile = staffProfileRepository.save(StaffProfile.builder()
                .account(staff)
                .staffCode("P1-S001")
                .departmentCode("OPD")
                .jobTitle("Nurse")
                .contactExtension("101")
                .active(true)
                .build());
        HospitalInformation hospital = hospitalRepository.save(HospitalInformation.builder()
                .updatedBy(admin)
                .infoCategory("PROFILE")
                .title("Hospital")
                .content("Information")
                .languageCode("vi-VN")
                .keywordsJson(json.createArrayNode())
                .status(HospitalInformationStatus.PUBLISHED)
                .publicVisible(true)
                .build());
        Floor floor = floorRepository.save(Floor.builder()
                .hospitalInformation(hospital)
                .floorCode("P1-F1")
                .floorName("Floor 1")
                .displayOrder(1)
                .active(true)
                .build());
        room = roomRepository.save(Room.builder()
                .floor(floor)
                .roomCode("P1-R101")
                .roomName("Outpatient Room")
                .roomType("CLINIC")
                .active(true)
                .publicVisible(false)
                .build());
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void staffMeReturnsProfileAndOnlyCurrentlyEffectiveActiveShifts() throws Exception {
        Instant now = Instant.now();
        shift("P1-ACTIVE", ShiftStatus.ACTIVE, now.minusSeconds(600), now.plusSeconds(600));
        shift("P1-FUTURE", ShiftStatus.ACTIVE, now.plusSeconds(600), now.plusSeconds(1200));
        shift("P1-PLANNED", ShiftStatus.PLANNED, now.minusSeconds(600), now.plusSeconds(600));

        mvc.perform(get("/api/v1/staff/me")
                        .header("Authorization", "Bearer " + userToken(staff))
                        .header("X-Correlation-ID", "phase1-correlation"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", "phase1-correlation"))
                .andExpect(jsonPath("$.account.accountId").value(staff.getAccountId().toString()))
                .andExpect(jsonPath("$.account.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.staffProfile.staffProfileId").value(profile.getStaffProfileId().toString()))
                .andExpect(jsonPath("$.staffProfile.staffCode").value("P1-S001"))
                .andExpect(jsonPath("$.activeShifts.length()").value(1))
                .andExpect(jsonPath("$.activeShifts[0].shiftCode").value("P1-ACTIVE"));
    }

    @Test
    void staffMeRejectsMissingProfileWithTheCommonErrorContract() throws Exception {
        Account withoutProfile = account("P1-NO-PROFILE", Role.STAFF);
        mvc.perform(get("/api/v1/staff/me")
                        .header("Authorization", "Bearer " + userToken(withoutProfile))
                        .header("X-Correlation-ID", "phase1-no-profile"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.correlationId").value("phase1-no-profile"))
                .andExpect(jsonPath("$.timestamp").isString());
    }

    @Test
    void staffBoundaryRejectsAnonymousAdminAndDeviceCredentials() throws Exception {
        Robot robot = robotRepository.save(Robot.builder()
                .robotCode("P1-ROBOT")
                .robotName("Robot")
                .model("MAR")
                .serialNumber("P1-SERIAL")
                .protocolVersion(1)
                .capabilitiesJson(json.createArrayNode())
                .status(RobotStatus.IDLE)
                .deviceSecretHash("configured")
                .deviceTokenVersion(0)
                .build());

        mvc.perform(get("/api/v1/staff/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/staff/me")
                        .header("Authorization", "Bearer " + userToken(admin)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/staff/me")
                        .header("Authorization", "Bearer " + deviceJwtTokenService.issue(robot).value()))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/pi/requests/information")
                        .header("Authorization", "Bearer " + userToken(staff))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listPaginationLimitsUseTheCommonValidationError() throws Exception {
        mvc.perform(get("/api/v1/staff/shifts?size=101")
                        .header("Authorization", "Bearer " + userToken(staff))
                        .header("X-Correlation-ID", "phase1-page-limit"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.correlationId").value("phase1-page-limit"));
    }

    @Test
    void openApiPublishesTheStaffMeContractWithUserBearerSecurity() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/staff/me'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/staff/me'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.components.schemas.StaffMeResponse").exists());
    }

    private void shift(String code, ShiftStatus status, Instant startsAt, Instant endsAt) {
        shiftRepository.save(Shift.builder()
                .staffProfile(profile)
                .room(room)
                .shiftCode(code)
                .shiftName(code)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .status(status)
                .build());
    }

    private Account account(String prefix, Role role) {
        String suffix = UUID.randomUUID().toString();
        return accountRepository.save(Account.builder()
                .accountCode(prefix + "-" + suffix)
                .username(prefix.toLowerCase() + "." + suffix)
                .fullName(prefix)
                .role(role)
                .authProvider(AuthProvider.LOCAL)
                .passwordHash("unused")
                .status(AccountStatus.ACTIVE)
                .mustChangePassword(false)
                .build());
    }

    private String userToken(Account account) {
        return jwtTokenService.issueAccessToken(account).value();
    }

    private void clean() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : new String[]{"Slot", "Shift", "Room", "Floor", "HospitalInformation", "Robot",
                "AuditLog", "RevokedToken", "RefreshToken", "StaffProfile", "Account"}) {
            jdbc.execute("TRUNCATE TABLE `" + table + "`");
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
    }
}
