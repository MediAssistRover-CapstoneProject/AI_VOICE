package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.entity.Job;
import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.entity.JobTask;
import com.mediassistrover.backend.entity.Robot;
import com.mediassistrover.backend.entity.ServiceRequest;
import com.mediassistrover.backend.enums.JobStatus;
import com.mediassistrover.backend.enums.JobStepStatus;
import com.mediassistrover.backend.enums.RobotStatus;
import com.mediassistrover.backend.enums.ServiceRequestStatus;
import com.mediassistrover.backend.repository.JobEventRepository;
import com.mediassistrover.backend.repository.JobRepository;
import com.mediassistrover.backend.repository.JobStepRepository;
import com.mediassistrover.backend.repository.RefreshTokenRepository;
import com.mediassistrover.backend.repository.RevokedTokenRepository;
import com.mediassistrover.backend.service.impl.OperationsMaintenanceServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationsMaintenanceServiceTest {
    @Mock JobRepository jobRepository;
    @Mock JobStepRepository stepRepository;
    @Mock JobEventRepository eventRepository;
    @Mock RevokedTokenRepository revokedTokenRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;

    @Test
    void overdueDispatchMovesJobAndRequestToRecoveryAndRobotToError() {
        Instant now = Instant.parse("2026-10-06T03:00:00Z");
        Robot robot = Robot.builder().status(RobotStatus.IDLE).build();
        ServiceRequest request = ServiceRequest.builder().status(ServiceRequestStatus.QUEUED).build();
        Job job = Job.builder().status(JobStatus.QUEUED).robot(robot).serviceRequest(request).build();
        when(jobRepository.lockOverdue(List.of(JobStatus.QUEUED), now)).thenReturn(List.of(job));

        int changed = service().markOverdueJobs(now);

        assertThat(changed).isEqualTo(1);
        assertThat(job.getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);
        assertThat(job.getErrorCode()).isEqualTo("DISPATCH_ACK_TIMEOUT");
        assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.RECOVERY_REQUIRED);
        assertThat(robot.getStatus()).isEqualTo(RobotStatus.ERROR);
        verify(eventRepository).save(org.mockito.ArgumentMatchers.argThat(event ->
                event.getOccurredAt().equals(now) && "DISPATCH_ACK_TIMEOUT".equals(event.getEventType())));
    }

    @Test
    void stepAtItsExactDeadlineTimesOutOnlyOnce() {
        Instant now = Instant.parse("2026-10-06T03:00:00Z");
        Job job = Job.builder().status(JobStatus.RUNNING).build();
        JobTask task = JobTask.builder().job(job).build();
        JobStep step = JobStep.builder().jobTask(task).status(JobStepStatus.RUNNING)
                .startedAt(now.minusSeconds(30)).timeoutSecSnapshot(30).build();
        when(stepRepository.lockPotentialTimeouts(now.minusSeconds(1))).thenReturn(List.of(step));

        assertThat(service().markTimedOutSteps(now)).isEqualTo(1);
        assertThat(step.getStatus()).isEqualTo(JobStepStatus.TIMED_OUT);
        assertThat(step.getEndedAt()).isEqualTo(now);
        assertThat(step.getErrorCode()).isEqualTo("STEP_TIMEOUT");
        verify(eventRepository).save(org.mockito.ArgumentMatchers.argThat(event ->
                "STEP_TIMEOUT".equals(event.getEventType())));
    }

    @Test
    void schema3MqttDurationWaitIsNeverCompletedOrTimedOutByLegacyScheduler() {
        Instant now = Instant.parse("2026-10-06T03:00:00Z");
        Job job = Job.builder().status(JobStatus.RUNNING).beOrchestrated(true).schemaVersion(3).build();
        JobTask task = JobTask.builder().job(job).build();
        JobStep step = JobStep.builder().jobTask(task).stepTypeSnapshot("WAIT")
                .resolvedInputsJson(new ObjectMapper().createObjectNode()
                        .put("wait_type", "DURATION").put("duration_sec", 5))
                .status(JobStepStatus.RUNNING).startedAt(now.minusSeconds(30))
                .timeoutSecSnapshot(10).build();
        when(stepRepository.lockPotentialTimeouts(now.minusSeconds(1))).thenReturn(List.of(step));

        assertThat(service().markTimedOutSteps(now)).isZero();
        assertThat(step.getStatus()).isEqualTo(JobStepStatus.RUNNING);
    }

    @Test
    void tokenCleanupCombinesBothStoresAtInclusiveBoundary() {
        Instant now = Instant.parse("2026-10-06T03:00:00Z");
        when(revokedTokenRepository.deleteByExpiresAtLessThanEqual(now)).thenReturn(2L);
        when(refreshTokenRepository.deleteByExpiresAtLessThanEqual(now)).thenReturn(3L);

        assertThat(service().cleanupExpiredTokens(now)).isEqualTo(5L);
    }

    private OperationsMaintenanceServiceImpl service() {
        return new OperationsMaintenanceServiceImpl(jobRepository, stepRepository, eventRepository,
                revokedTokenRepository, refreshTokenRepository, new ObjectMapper());
    }
}
