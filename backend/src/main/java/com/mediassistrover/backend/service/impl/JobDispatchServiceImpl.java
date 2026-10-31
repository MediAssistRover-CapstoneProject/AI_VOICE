package com.mediassistrover.backend.service.impl;

import com.mediassistrover.backend.entity.FacilityMap;
import com.mediassistrover.backend.entity.Job;
import com.mediassistrover.backend.entity.Robot;
import com.mediassistrover.backend.enums.JobStatus;
import com.mediassistrover.backend.enums.RobotStatus;
import com.mediassistrover.backend.repository.JobRepository;
import com.mediassistrover.backend.repository.RobotRepository;
import com.mediassistrover.backend.service.JobDispatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JobDispatchServiceImpl implements JobDispatchService {
    private static final EnumSet<JobStatus> OCCUPYING = EnumSet.of(
            JobStatus.QUEUED, JobStatus.ACCEPTED, JobStatus.RUNNING, JobStatus.RECOVERY_REQUIRED);
    private final JobRepository jobRepository;
    private final RobotRepository robotRepository;
    private final Clock clock;
    private final com.mediassistrover.backend.service.OrchestrationWorkService wakeups;
    private final com.mediassistrover.backend.validation.WorkflowExecutionReadiness readiness;

    @Override
    @Transactional
    public int dispatchQueuedJobs(int limit) {
        if (limit < 1) return 0;
        List<Job> queued = jobRepository.findByStatusAndRobotIsNullOrderByPriorityDescQueuedAtAsc(
                JobStatus.QUEUED, PageRequest.of(0, limit));
        int assigned = 0;
        for (Job candidate : queued) {
            Job job=jobRepository.lockById(candidate.getJobId()).orElseThrow();
            if (job.getStatus()!=JobStatus.QUEUED || job.getRobot()!=null) continue;
            Robot robot = chooseRobot(job);
            if (robot == null) continue;
            job.setRobot(robot);
            if (!job.isBeOrchestrated()) {job.setDispatchCommandId(UUID.randomUUID());job.setDispatchExpiresAt(Instant.now(clock).plusSeconds(60));}
            wakeups.request(job);
            assigned++;
        }
        return assigned;
    }

    private Robot chooseRobot(Job job) {
        FacilityMap map = job.getMap();
        List<Robot> candidates = map == null
                ? robotRepository.findByStatusOrderByRobotCodeAsc(RobotStatus.IDLE)
                : robotRepository.findByStatusAndCurrentMapMapIdOrderByRobotCodeAsc(RobotStatus.IDLE, map.getMapId());
        return candidates.stream()
                .map(candidate -> robotRepository.findByIdForUpdate(candidate.getRobotId()).orElseThrow())
                .filter(robot -> robot.getStatus()==RobotStatus.IDLE)
                .filter(robot -> readiness.acceptsRobot(job.getWorkflow(),robot))
                .filter(robot -> jobRepository.findByRobotRobotIdAndStatusIn(robot.getRobotId(), OCCUPYING).isEmpty())
                .findFirst().orElse(null);
    }
}
