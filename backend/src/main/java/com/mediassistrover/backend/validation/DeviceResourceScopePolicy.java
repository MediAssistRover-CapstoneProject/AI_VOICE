package com.mediassistrover.backend.validation;

import com.mediassistrover.backend.entity.EmergencyIncident;
import com.mediassistrover.backend.entity.Robot;
import com.mediassistrover.backend.entity.ServiceRequest;
import com.mediassistrover.backend.repository.RobotRepository;
import com.mediassistrover.backend.security.DevicePrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeviceResourceScopePolicy {
    private final RobotRepository robotRepository;
    private final Clock clock;

    public Robot requireCurrentDevice(DevicePrincipal principal) {
        if (principal == null || principal.expiresAt() == null
                || !principal.expiresAt().isAfter(Instant.now(clock))) {
            throw new AccessDeniedException("A current Device session is required");
        }
        Robot robot = robotRepository.findById(principal.robotId())
                .orElseThrow(() -> new AccessDeniedException("Robot is unavailable"));
        if (!robot.getRobotCode().equals(principal.robotCode())
                || robot.getDeviceSecretHash() == null
                || robot.getDeviceTokenVersion() != principal.tokenVersion()) {
            throw new AccessDeniedException("Device session is no longer valid");
        }
        return robot;
    }

    public void requireRequestOwner(ServiceRequest request, DevicePrincipal principal) {
        Robot robot = requireCurrentDevice(principal);
        if (request == null || request.getRequestedRobot() == null
                || !Objects.equals(request.getRequestedRobot().getRobotId(), robot.getRobotId())) {
            throw new AccessDeniedException("ServiceRequest is outside this Device scope");
        }
    }

    public void requireIncidentOwner(EmergencyIncident incident, DevicePrincipal principal) {
        Robot robot = requireCurrentDevice(principal);
        if (incident == null || incident.getRobot() == null
                || !Objects.equals(incident.getRobot().getRobotId(), robot.getRobotId())) {
            throw new AccessDeniedException("EmergencyIncident is outside this Device scope");
        }
    }
}
