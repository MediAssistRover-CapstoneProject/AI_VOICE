package com.mediassistrover.backend;

import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.AccountStatus;
import com.mediassistrover.backend.enums.Role;
import com.mediassistrover.backend.enums.JobStatus;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.security.DevicePrincipal;
import com.mediassistrover.backend.validation.DeviceResourceScopePolicy;
import com.mediassistrover.backend.validation.StaffOperationalScopePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Phase1ScopePolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-15T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void deviceCanOnlyUseResourcesOwnedByItsCurrentRobotSession() {
        RobotRepository robots = mock(RobotRepository.class);
        UUID robotId = UUID.randomUUID();
        Robot robot = Robot.builder().robotId(robotId).robotCode("MAR-01")
                .deviceSecretHash("configured").deviceTokenVersion(3).build();
        when(robots.findById(robotId)).thenReturn(Optional.of(robot));
        DeviceResourceScopePolicy policy = new DeviceResourceScopePolicy(robots, CLOCK);
        DevicePrincipal principal = new DevicePrincipal(robotId, "MAR-01", 3, "jti", NOW.plusSeconds(60));

        ServiceRequest owned = ServiceRequest.builder().requestedRobot(robot).build();
        EmergencyIncident incident = EmergencyIncident.builder().robot(robot).build();
        assertThatCode(() -> policy.requireRequestOwner(owned, principal)).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireIncidentOwner(incident, principal)).doesNotThrowAnyException();

        Robot other = Robot.builder().robotId(UUID.randomUUID()).build();
        assertThatThrownBy(() -> policy.requireRequestOwner(
                ServiceRequest.builder().requestedRobot(other).build(), principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.requireCurrentDevice(
                new DevicePrincipal(robotId, "MAR-01", 2, "old", NOW.plusSeconds(60))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staffRequestScopeAcceptsCreatorAssignmentOrEffectiveShiftRoom() {
        AccountRepository accounts = mock(AccountRepository.class);
        ShiftRepository shifts = mock(ShiftRepository.class);
        JobRepository jobs = mock(JobRepository.class);
        AlertRepository alerts = mock(AlertRepository.class);
        AlertRecipientRepository recipients = mock(AlertRecipientRepository.class);
        ServiceRequestRepository requests = mock(ServiceRequestRepository.class);
        NavigationRequestRepository navigation = mock(NavigationRequestRepository.class);
        EscortRequestRepository escorts = mock(EscortRequestRepository.class);
        DeliveryRequestRepository deliveries = mock(DeliveryRequestRepository.class);
        EndpointRepository endpoints = mock(EndpointRepository.class);
        StaffOperationalScopePolicy policy = new StaffOperationalScopePolicy(
                accounts, shifts, jobs, alerts, recipients, requests, navigation, escorts, deliveries, endpoints, CLOCK);

        UUID accountId = UUID.randomUUID();
        Account account = Account.builder().accountId(accountId).role(Role.STAFF)
                .status(AccountStatus.ACTIVE).tokenVersion(5).mustChangePassword(false).build();
        AccountPrincipal principal = new AccountPrincipal(accountId, "staff", "", Role.STAFF,
                AccountStatus.ACTIVE, 5, "jti", NOW.plusSeconds(60), null);
        when(accounts.findById(accountId)).thenReturn(Optional.of(account));

        ServiceRequest created = ServiceRequest.builder().serviceRequestId(UUID.randomUUID())
                .requestedByAccount(account).build();
        assertThatCode(() -> policy.requireServiceRequestAccess(created, principal)).doesNotThrowAnyException();

        UUID assignedRequestId = UUID.randomUUID();
        ServiceRequest assigned = ServiceRequest.builder().serviceRequestId(assignedRequestId).build();
        when(jobs.existsByServiceRequestServiceRequestIdAndAssignedStaffAccountAccountId(
                assignedRequestId, accountId)).thenReturn(true);
        assertThatCode(() -> policy.requireServiceRequestAccess(assigned, principal)).doesNotThrowAnyException();

        UUID roomRequestId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        ServiceRequest roomScoped = ServiceRequest.builder().serviceRequestId(roomRequestId).build();
        Room room = Room.builder().roomId(roomId).build();
        when(navigation.findById(roomRequestId)).thenReturn(Optional.of(
                NavigationRequest.builder().serviceRequestId(roomRequestId).room(room).build()));
        when(escorts.findById(roomRequestId)).thenReturn(Optional.empty());
        when(deliveries.findById(roomRequestId)).thenReturn(Optional.empty());
        when(shifts.existsEffectiveActiveInRoom(accountId, roomId, NOW)).thenReturn(true);
        assertThatCode(() -> policy.requireServiceRequestAccess(roomScoped, principal)).doesNotThrowAnyException();

        Job shiftScopedJob = Job.builder().jobId(UUID.randomUUID()).serviceRequest(roomScoped).build();
        assertThatCode(() -> policy.requireJobAccess(shiftScopedJob, principal)).doesNotThrowAnyException();
        Schedule ownedSchedule = Schedule.builder().scheduleId(UUID.randomUUID()).createdByAccount(account).build();
        assertThatCode(() -> policy.requireJobAccess(
                Job.builder().jobId(UUID.randomUUID()).schedule(ownedSchedule).build(), principal))
                .doesNotThrowAnyException();
        UUID robotId = UUID.randomUUID();
        when(jobs.findByRobotRobotIdAndStatusIn(robotId, List.of(JobStatus.RECOVERY_REQUIRED)))
                .thenReturn(List.of(shiftScopedJob));
        assertThatCode(() -> policy.requireRobotRecoveryAccess(robotId, principal)).doesNotThrowAnyException();

        EmergencyIncident roomIncident = EmergencyIncident.builder().incidentId(UUID.randomUUID())
                .endpoint(Endpoint.builder().room(room).build()).build();
        assertThatCode(() -> policy.requireIncidentAccess(roomIncident, principal)).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireAlertAccess(
                Alert.builder().alertId(UUID.randomUUID()).incident(roomIncident).build(), principal))
                .doesNotThrowAnyException();
        Zone zone = Zone.builder().zoneId(UUID.randomUUID()).build();
        when(endpoints.findByZoneZoneId(zone.getZoneId())).thenReturn(List.of(Endpoint.builder().room(room).build()));
        assertThatCode(() -> policy.requireAlertAccess(
                Alert.builder().alertId(UUID.randomUUID()).zone(zone).build(), principal))
                .doesNotThrowAnyException();

        UUID recipientAlertId = UUID.randomUUID();
        when(recipients.existsByAlertAlertIdAndAccountAccountId(recipientAlertId, accountId)).thenReturn(true);
        assertThatCode(() -> policy.requireAlertAccess(
                Alert.builder().alertId(recipientAlertId).build(), principal)).doesNotThrowAnyException();

        UUID foreignId = UUID.randomUUID();
        ServiceRequest foreign = ServiceRequest.builder().serviceRequestId(foreignId).build();
        when(navigation.findById(foreignId)).thenReturn(Optional.empty());
        when(escorts.findById(foreignId)).thenReturn(Optional.empty());
        when(deliveries.findById(foreignId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> policy.requireServiceRequestAccess(foreign, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.requireJobAccess(
                Job.builder().jobId(UUID.randomUUID()).serviceRequest(foreign).build(), principal))
                .isInstanceOf(AccessDeniedException.class);
        EmergencyIncident foreignIncident = EmergencyIncident.builder().incidentId(UUID.randomUUID()).build();
        when(requests.findByRelatedIncidentId(foreignIncident.getIncidentId())).thenReturn(Optional.empty());
        when(alerts.findByIncidentIncidentId(foreignIncident.getIncidentId())).thenReturn(List.of());
        assertThatThrownBy(() -> policy.requireIncidentAccess(foreignIncident, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.requireAlertAccess(
                Alert.builder().alertId(UUID.randomUUID()).incident(foreignIncident).build(), principal))
                .isInstanceOf(AccessDeniedException.class);
    }
}
