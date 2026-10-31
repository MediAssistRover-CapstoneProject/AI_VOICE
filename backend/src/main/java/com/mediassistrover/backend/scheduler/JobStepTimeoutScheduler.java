package com.mediassistrover.backend.scheduler;
import com.mediassistrover.backend.service.OperationsMaintenanceService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
@Component @RequiredArgsConstructor @ConditionalOnProperty(prefix="app.operations",name="scheduler-enabled",havingValue="true",matchIfMissing=true)
public class JobStepTimeoutScheduler {
    private final OperationsMaintenanceService service; private final Clock clock;
    @Scheduled(fixedDelayString="${app.operations.step-timeout-scan-ms:5000}")
    @SchedulerLock(name="jobStepTimeoutScheduler",lockAtMostFor="PT2M",lockAtLeastFor="PT1S")
    public void run(){service.markTimedOutSteps(Instant.now(clock));}
}
