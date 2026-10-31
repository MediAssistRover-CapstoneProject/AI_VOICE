package com.mediassistrover.backend.config;

import com.mediassistrover.backend.service.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.enums.JobStatus;
import com.mediassistrover.backend.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name="app.operations.scheduler-enabled",havingValue="true",matchIfMissing=true)
public class WorkflowOrchestrationScheduler {
    private final WorkflowOrchestrationWorkRepository work;
    private final WorkflowOrchestratorService orchestrator;
    private final JobRepository jobs;
    private final OrchestrationWorkService wakeups;
    private final JobUiEventStream uiEvents;
    private final RobotRealtimeEventStream robotEvents;
    @Scheduled(fixedDelayString="${app.workflow.orchestration-delay-ms:500}")
    public void runPending() {
        for (var id:work.findPending(PageRequest.of(0,32))) {
            try { orchestrator.advance(id); }
            catch (BusinessRuleException ex) { orchestrator.reject(id,ex.getMessage()); }
            catch (RuntimeException ex) { log.error("Orchestration transaction failed for Job {}; durable work will retry",id,ex); }
        }
    }
    @Scheduled(fixedDelayString="${app.workflow.deadline-check-ms:1000}")
    public void checkDeadlines() {
        for (var id:jobs.findReadyBeJobIds(PageRequest.of(0,100))) wakeups.request(jobs.findById(id).orElseThrow());
        // A restart restores waiting server steps from DB; their deadlines do not depend on an in-memory timer.
        int page=0;
        org.springframework.data.domain.Page<com.mediassistrover.backend.entity.Job> batch;
        do {
            batch=jobs.findByBeOrchestratedTrueAndStatus(JobStatus.RUNNING,PageRequest.of(page++,100,org.springframework.data.domain.Sort.by("jobId")));
            for (var job:batch) wakeups.request(job);
        } while (batch.hasNext());
    }
    @Scheduled(fixedDelay=20_000)
    public void heartbeatUi() { uiEvents.heartbeat(); robotEvents.heartbeat(); }
}
