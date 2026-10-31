package com.mediassistrover.backend.scheduler;

import com.mediassistrover.backend.service.JobDispatchService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.operations", name = "scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class QueuedJobDispatchScheduler {
    private static final Logger log = LoggerFactory.getLogger(QueuedJobDispatchScheduler.class);
    private final JobDispatchService dispatchService;

    @Scheduled(fixedDelayString = "${app.operations.job-dispatch-scan-ms:2000}")
    @SchedulerLock(name = "queuedJobDispatchScheduler", lockAtMostFor = "PT2M", lockAtLeastFor = "PT1S")
    public void dispatch() {
        int count = dispatchService.dispatchQueuedJobs(50);
        if (count > 0) log.info("Assigned {} queued job(s) to available robots", count);
    }
}
