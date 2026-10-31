package com.mediassistrover.backend.scheduler;

import com.mediassistrover.backend.service.RetentionService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="app.operations",name="scheduler-enabled",havingValue="true",matchIfMissing=true)
public class RetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);
    private final RetentionService retentionService;
    @Value("${app.operations.retention-dry-run:true}") private boolean dryRun;

    @Scheduled(cron="${app.operations.retention-scan-cron:0 30 2 * * *}",zone="UTC")
    @SchedulerLock(name="retentionScheduler",lockAtMostFor="PT30M",lockAtLeastFor="PT10S")
    public void run() {
        var result = retentionService.run(dryRun);
        long deleted = result.datasets().stream().mapToLong(item -> item.deleted()).sum();
        long failures = result.datasets().stream().filter(item -> item.error() != null).count();
        log.info("Retention completed: dryRun={}, deleted={}, failures={}", result.dryRun(), deleted, failures);
    }
}
