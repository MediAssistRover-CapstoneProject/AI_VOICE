package com.mediassistrover.backend.scheduler;
import com.mediassistrover.backend.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
@Component @RequiredArgsConstructor @ConditionalOnProperty(prefix="app.operations",name="scheduler-enabled",havingValue="true",matchIfMissing=true)
public class DueScheduleRunner {
    private static final Logger log=LoggerFactory.getLogger(DueScheduleRunner.class); private final ScheduleService service; private final Clock clock;
    @Scheduled(fixedDelayString="${app.operations.schedule-scan-ms:10000}")
    @SchedulerLock(name="dueScheduleRunner",lockAtMostFor="PT2M",lockAtLeastFor="PT1S")
    public void run(){int count=service.runDue(Instant.now(clock),50);if(count>0)log.info("Created {} scheduled job(s)",count);}
}
