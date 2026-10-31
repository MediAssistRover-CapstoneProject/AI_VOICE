package com.mediassistrover.backend.validation;

import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.AccountStatus;
import com.mediassistrover.backend.enums.Role;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StaffOperationalScopePolicy {
    private final AccountRepository accountRepository;
    private final ShiftRepository shiftRepository;
    private final JobRepository jobRepository;
    private final AlertRepository alertRepository;
    private final AlertRecipientRepository alertRecipientRepository;
    private final ServiceRequestRepository serviceRequestRepository;
    private final NavigationRequestRepository navigationRequestRepository;
    private final EscortRequestRepository escortRequestRepository;
    private final DeliveryRequestRepository deliveryRequestRepository;
    private final EndpointRepository endpointRepository;
    private final Clock clock;

    public Account requireCurrentStaffOrAdmin(AccountPrincipal principal) {
        if (principal == null || (principal.role() != Role.STAFF && principal.role() != Role.ADMIN)) {
            throw new AccessDeniedException("A current Staff/Admin session is required");
        }
        Account account = accountRepository.findById(principal.accountId())
                .orElseThrow(() -> new AccessDeniedException("Account is unavailable"));
        if (account.getStatus() != AccountStatus.ACTIVE
                || account.getRole() != principal.role()
                || account.isMustChangePassword()
                || account.getTokenVersion() != principal.tokenVersion()) {
            throw new AccessDeniedException("A current, unrestricted Staff/Admin session is required");
        }
        return account;
    }

    public void requireRobotRead(AccountPrincipal principal) {
        requireCurrentStaffOrAdmin(principal);
    }

    public void requireServiceRequestAccess(ServiceRequest request, AccountPrincipal principal) {
        Account account = requireCurrentStaffOrAdmin(principal);
        if (account.getRole() != Role.ADMIN && !isServiceRequestInScope(request, account.getAccountId(), Instant.now(clock))) {
            throw new AccessDeniedException("ServiceRequest is outside this Staff operational scope");
        }
    }

    public boolean canAccessServiceRequest(ServiceRequest request, AccountPrincipal principal) {
        Account account = requireCurrentStaffOrAdmin(principal);
        return account.getRole() == Role.ADMIN
                || isServiceRequestInScope(request, account.getAccountId(), Instant.now(clock));
    }

    public void requireJobAccess(Job job, AccountPrincipal principal) {
        Account account = requireCurrentStaffOrAdmin(principal);
        if (account.getRole() == Role.ADMIN) return;
        if (!isJobInScope(job, account.getAccountId(), Instant.now(clock))) {
            throw new AccessDeniedException("Job is outside this Staff operational scope");
        }
    }

    public boolean canAccessJob(Job job, AccountPrincipal principal) {
        Account account = requireCurrentStaffOrAdmin(principal);
        return account.getRole() == Role.ADMIN || isJobInScope(job, account.getAccountId(), Instant.now(clock));
    }

    public void requireRobotRecoveryAccess(UUID robotId, AccountPrincipal principal) {
        Account account = requireCurrentStaffOrAdmin(principal);
        if (account.getRole() == Role.ADMIN) return;
        boolean allowed = jobRepository.findByRobotRobotIdAndStatusIn(robotId, List.of(com.mediassistrover.backend.enums.JobStatus.RECOVERY_REQUIRED))
                .stream().anyMatch(job -> isJobInScope(job, account.getAccountId(), Instant.now(clock)));
        if (!allowed) throw new AccessDeniedException("Robot recovery is outside this Staff operational scope");
    }

    private boolean isJobInScope(Job job, UUID accountId, Instant now) {
        boolean assigned = job != null && job.getAssignedStaffAccount() != null
                && Objects.equals(job.getAssignedStaffAccount().getAccountId(), accountId);
        boolean scheduleOwner = job != null && job.getSchedule() != null
                && job.getSchedule().getCreatedByAccount() != null
                && Objects.equals(job.getSchedule().getCreatedByAccount().getAccountId(), accountId);
        boolean requestScope = job != null && job.getServiceRequest() != null
                && isServiceRequestInScope(job.getServiceRequest(), accountId, now);
        return assigned || scheduleOwner || requestScope;
    }

    public void requireScheduleAccess(Schedule schedule, AccountPrincipal principal) {
        Account account = requireCurrentStaffOrAdmin(principal);
        if (account.getRole() != Role.ADMIN && (schedule == null || schedule.getCreatedByAccount() == null
                || !Objects.equals(schedule.getCreatedByAccount().getAccountId(), account.getAccountId()))) {
            throw new AccessDeniedException("Schedule is outside this Staff operational scope");
        }
    }

    public void requireAlertAccess(Alert alert, AccountPrincipal principal) {
        Account account = requireCurrentStaffOrAdmin(principal);
        if (account.getRole() != Role.ADMIN
                && !isAlertInScope(alert, account.getAccountId(), Instant.now(clock))) {
            throw new AccessDeniedException("Alert is outside this Staff operational scope");
        }
    }

    public boolean canAccessAlert(Alert alert, AccountPrincipal principal) {
        Account account = requireCurrentStaffOrAdmin(principal);
        return account.getRole() == Role.ADMIN
                || isAlertInScope(alert, account.getAccountId(), Instant.now(clock));
    }

    private boolean isAlertInScope(Alert alert, UUID accountId, Instant now) {
        boolean recipient = alert != null
                && alertRecipientRepository.existsByAlertAlertIdAndAccountAccountId(alert.getAlertId(), accountId);
        boolean requestScope = alert != null && alert.getServiceRequest() != null
                && isServiceRequestInScope(alert.getServiceRequest(), accountId, now);
        boolean jobScope = alert != null && alert.getJobStep() != null
                && isJobInScope(alert.getJobStep().getJobTask().getJob(), accountId, now);
        boolean incidentScope = alert != null && alert.getIncident() != null
                && isIncidentInScope(alert.getIncident(), accountId, now);
        boolean zoneScope = alert != null && alert.getZone() != null
                && endpointRepository.findByZoneZoneId(alert.getZone().getZoneId()).stream()
                .filter(endpoint -> endpoint.getRoom() != null)
                .anyMatch(endpoint -> hasEffectiveShift(accountId, endpoint.getRoom().getRoomId(), now));
        return recipient || requestScope || jobScope || incidentScope || zoneScope;
    }

    public void requireIncidentAccess(EmergencyIncident incident, AccountPrincipal principal) {
        Account account = requireCurrentStaffOrAdmin(principal);
        if (account.getRole() != Role.ADMIN
                && !isIncidentInScope(incident, account.getAccountId(), Instant.now(clock))) {
            throw new AccessDeniedException("EmergencyIncident is outside this Staff operational scope");
        }
    }

    public boolean canAccessIncident(EmergencyIncident incident, AccountPrincipal principal) {
        Account account = requireCurrentStaffOrAdmin(principal);
        return account.getRole() == Role.ADMIN
                || isIncidentInScope(incident, account.getAccountId(), Instant.now(clock));
    }

    public boolean hasEffectiveShiftInRoom(AccountPrincipal principal, UUID roomId) {
        Account account = requireCurrentStaffOrAdmin(principal);
        return account.getRole() == Role.ADMIN || roomId != null
                && shiftRepository.existsEffectiveActiveInRoom(account.getAccountId(), roomId, Instant.now(clock));
    }

    private boolean isIncidentInScope(EmergencyIncident incident, UUID accountId, Instant now) {
        if (incident == null) return false;
        if (incident.getAcknowledgedByAccount() != null
                && Objects.equals(incident.getAcknowledgedByAccount().getAccountId(), accountId)) return true;
        if (incident.getResolvedByAccount() != null
                && Objects.equals(incident.getResolvedByAccount().getAccountId(), accountId)) return true;
        if (incident.getEndpoint() != null && incident.getEndpoint().getRoom() != null
                && hasEffectiveShift(accountId, incident.getEndpoint().getRoom().getRoomId(), now)) return true;
        if (incident.getDetectionEvent() != null && incident.getDetectionEvent().getZone() != null
                && endpointRepository.findByZoneZoneId(incident.getDetectionEvent().getZone().getZoneId()).stream()
                .filter(endpoint -> endpoint.getRoom() != null)
                .anyMatch(endpoint -> hasEffectiveShift(accountId, endpoint.getRoom().getRoomId(), now))) return true;
        ServiceRequest related = serviceRequestRepository.findByRelatedIncidentId(incident.getIncidentId()).orElse(null);
        if (related != null && isServiceRequestInScope(related, accountId, now)) return true;
        return alertRepository.findByIncidentIncidentId(incident.getIncidentId()).stream()
                .anyMatch(alert -> alertRecipientRepository.existsByAlertAlertIdAndAccountAccountId(
                        alert.getAlertId(), accountId));
    }

    private boolean isServiceRequestInScope(ServiceRequest request, UUID accountId, Instant now) {
        if (request == null) return false;
        if (request.getRequestedByAccount() != null
                && Objects.equals(request.getRequestedByAccount().getAccountId(), accountId)) return true;
        if (jobRepository.existsByServiceRequestServiceRequestIdAndAssignedStaffAccountAccountId(
                request.getServiceRequestId(), accountId)) return true;

        Set<UUID> roomIds = requestRoomIds(request.getServiceRequestId());
        return roomIds.stream().anyMatch(roomId -> hasEffectiveShift(accountId, roomId, now));
    }

    private Set<UUID> requestRoomIds(UUID requestId) {
        Set<UUID> roomIds = new HashSet<>();
        navigationRequestRepository.findById(requestId).ifPresent(request -> {
            addRoom(roomIds, request.getRoom());
            addEndpointRoom(roomIds, request.getDestinationEndpoint());
            addEndpointRoom(roomIds, request.getResolvedEndpoint());
        });
        escortRequestRepository.findById(requestId).ifPresent(request -> {
            addRoom(roomIds, request.getOriginRoom());
            addRoom(roomIds, request.getDestinationRoom());
            addEndpointRoom(roomIds, request.getOriginEndpoint());
            addEndpointRoom(roomIds, request.getDestinationEndpoint());
            addEndpointRoom(roomIds, request.getResolvedEndpoint());
        });
        deliveryRequestRepository.findById(requestId).ifPresent(request -> {
            addRoom(roomIds, request.getPickupRoom());
            addRoom(roomIds, request.getDeliveryRoom());
            addEndpointRoom(roomIds, request.getPickupEndpoint());
            addEndpointRoom(roomIds, request.getDeliveryEndpoint());
            addEndpointRoom(roomIds, request.getResolvedDeliveryEndpoint());
        });
        return roomIds;
    }

    private boolean hasEffectiveShift(UUID accountId, UUID roomId, Instant now) {
        return shiftRepository.existsEffectiveActiveInRoom(accountId, roomId, now);
    }

    private void addEndpointRoom(Set<UUID> roomIds, Endpoint endpoint) {
        if (endpoint != null) addRoom(roomIds, endpoint.getRoom());
    }

    private void addRoom(Set<UUID> roomIds, Room room) {
        if (room != null) roomIds.add(room.getRoomId());
    }
}
