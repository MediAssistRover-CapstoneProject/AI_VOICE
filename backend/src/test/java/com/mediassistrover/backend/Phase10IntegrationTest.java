package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.dto.shift.request.ShiftRequests.*;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.exception.ConflictException;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.JwtTokenService;
import com.mediassistrover.backend.service.RequestMetadata;
import com.mediassistrover.backend.service.ShiftService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class Phase10IntegrationTest extends MySqlIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MockMvc mockMvc;
    @Autowired AccountRepository accountRepository;
    @Autowired StaffProfileRepository staffRepository;
    @Autowired HospitalInformationRepository hospitalRepository;
    @Autowired FloorRepository floorRepository;
    @Autowired RoomRepository roomRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired ShiftService shiftService;
    @Autowired JwtTokenService jwtTokenService;

    private Account admin;
    private Account staffAccount;
    private Account otherStaffAccount;
    private StaffProfile staff;
    private Room room;

    @BeforeEach
    void setup() {
        clean();
        admin = account("P10-ADMIN", "phase10.admin", "phase10.admin@example.test", Role.ADMIN);
        staffAccount = account("P10-STAFF", "phase10.staff", "phase10.staff@example.test", Role.STAFF);
        otherStaffAccount = account("P10-OTHER", "phase10.other", "phase10.other@example.test", Role.STAFF);
        staff = staffRepository.save(StaffProfile.builder().account(staffAccount).staffCode("P10-S001")
                .departmentCode("ER").jobTitle("Nurse").active(true).build());
        staffRepository.save(StaffProfile.builder().account(otherStaffAccount).staffCode("P10-S002")
                .departmentCode("ER").jobTitle("Doctor").active(true).build());
        HospitalInformation hospital = hospitalRepository.save(HospitalInformation.builder().updatedBy(admin)
                .infoCategory("PROFILE").title("Hospital").content("Info").languageCode("vi-VN")
                .keywordsJson(objectMapper.createArrayNode()).status(HospitalInformationStatus.PUBLISHED)
                .publicVisible(true).build());
        Floor floor = floorRepository.save(Floor.builder().hospitalInformation(hospital).floorCode("P10-F1")
                .floorName("Floor 1").displayOrder(1).active(true).build());
        room = roomRepository.save(Room.builder().floor(floor).roomCode("P10-R101").roomName("Emergency Room")
                .roomType("CLINIC").active(true).publicVisible(false).build());
    }

    @AfterEach void cleanup() { clean(); }

    @Test
    void createsAggregateAndRejectsOverlappingStaffShiftAndSlot() {
        Instant start = Instant.parse("2026-10-07T01:00:00Z");
        var created = shiftService.create(create("SHIFT-P10-01", start), admin.getAccountId(), metadata());

        assertThat(created.status()).isEqualTo(ShiftStatus.PLANNED);
        assertThat(created.slots()).extracting(s -> s.sequenceNo()).containsExactly(1, 2);
        assertThat(jdbc.queryForObject("SELECT DATA_TYPE FROM information_schema.columns WHERE table_schema=DATABASE() " +
                "AND table_name='Shift' AND column_name='shift_id'", String.class)).isEqualTo("binary");

        Create overlapping = new Create(staff.getStaffProfileId(), room.getRoomId(), "SHIFT-P10-02", "Overlap",
                start.plusSeconds(1800), start.plusSeconds(10800), null, List.of());
        assertThatThrownBy(() -> shiftService.create(overlapping, admin.getAccountId(), metadata()))
                .isInstanceOf(ConflictException.class).hasMessageContaining("overlapping Shift");

        SlotCreate overlappingSlot = new SlotCreate(3, start.plusSeconds(1800), start.plusSeconds(5400),
                SlotStatus.AVAILABLE, null);
        assertThatThrownBy(() -> shiftService.addSlot(created.shiftId(), overlappingSlot, admin.getAccountId(), metadata()))
                .isInstanceOf(ConflictException.class).hasMessageContaining("cannot overlap");
    }

    @Test
    void enforcesShiftAndSlotStateMachinesAndKeepsHistory() {
        Instant start = Instant.parse("2026-10-07T05:00:00Z");
        var created = shiftService.create(create("SHIFT-P10-03", start), admin.getAccountId(), metadata());
        var active = shiftService.transition(created.shiftId(), new Transition(ShiftStatus.ACTIVE, null),
                admin.getAccountId(), metadata());
        assertThat(active.status()).isEqualTo(ShiftStatus.ACTIVE);

        assertThatThrownBy(() -> shiftService.update(created.shiftId(), update(start), admin.getAccountId(), metadata()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("PLANNED");
        var closedSlot = shiftService.transitionSlot(created.shiftId(), created.slots().getFirst().slotId(),
                new SlotTransition(SlotStatus.CLOSED, "Finished"), admin.getAccountId(), metadata());
        assertThat(closedSlot.status()).isEqualTo(SlotStatus.CLOSED);
        assertThatThrownBy(() -> shiftService.transitionSlot(created.shiftId(), closedSlot.slotId(),
                new SlotTransition(SlotStatus.AVAILABLE, null), admin.getAccountId(), metadata()))
                .isInstanceOf(BusinessRuleException.class);

        shiftService.transition(created.shiftId(), new Transition(ShiftStatus.COMPLETED, null),
                admin.getAccountId(), metadata());
        assertThatThrownBy(() -> shiftService.delete(created.shiftId(), admin.getAccountId(), metadata()))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(shiftRepository.existsById(created.shiftId())).isTrue();
    }

    @Test
    void staffReadsOnlyOwnShiftsAndCannotUseAdminRoute() throws Exception {
        var created = shiftService.create(create("SHIFT-P10-04", Instant.parse("2026-10-08T01:00:00Z")),
                admin.getAccountId(), metadata());
        assertThat(shiftService.listOwn(staffAccount.getAccountId(), null, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(1);
        assertThatThrownBy(() -> shiftService.getOwn(created.shiftId(), otherStaffAccount.getAccountId()))
                .isInstanceOf(AccessDeniedException.class);

        String staffToken = jwtTokenService.issueAccessToken(staffAccount).value();
        mockMvc.perform(get("/api/v1/staff/shifts").header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].shiftCode").value("SHIFT-P10-04"));
        String otherStaffToken = jwtTokenService.issueAccessToken(otherStaffAccount).value();
        mockMvc.perform(get("/api/v1/staff/shifts/{id}", created.shiftId())
                        .header("Authorization", "Bearer " + otherStaffToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/shifts").header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden());
    }

    private Create create(String code, Instant start) {
        return new Create(staff.getStaffProfileId(), room.getRoomId(), code, "Morning shift", start,
                start.plusSeconds(7200), "Coverage", List.of(
                new SlotInput(1, start, start.plusSeconds(3600), SlotStatus.AVAILABLE, null),
                new SlotInput(2, start.plusSeconds(3600), start.plusSeconds(7200), SlotStatus.AVAILABLE, null)));
    }

    private Update update(Instant start) {
        return new Update(staff.getStaffProfileId(), room.getRoomId(), "Updated shift", start,
                start.plusSeconds(7200), null, List.of());
    }

    private Account account(String code, String username, String email, Role role) {
        return accountRepository.save(Account.builder().accountCode(code).username(username).fullName(username)
                .email(email).role(role).authProvider(AuthProvider.LOCAL).passwordHash("unused")
                .status(AccountStatus.ACTIVE).passwordChangedAt(Instant.now()).build());
    }

    private RequestMetadata metadata() { return new RequestMetadata("127.0.0.1", "test", "phase10-test"); }

    private void clean() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : new String[]{"Slot", "Shift", "Room", "Floor", "HospitalInformation", "AuditLog",
                "RevokedToken", "RefreshToken", "StaffProfile", "Account"}) {
            jdbc.execute("TRUNCATE TABLE `" + table + "`");
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
    }
}
