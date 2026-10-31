package com.mediassistrover.backend.service;

import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;

/** Job lock is the serialization point for both completion and durable wake-up. */
@Service @RequiredArgsConstructor
public class OrchestrationWorkService {
    private final JobRepository jobs;
    private final WorkflowOrchestrationWorkRepository work;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional
    public void request(Job supplied) {
        if (!supplied.isBeOrchestrated()) return;
        Job job=jobs.lockById(supplied.getJobId()).orElseThrow();
        // Insert first to avoid locking an empty PK gap while unrelated Jobs create their work rows.
        work.ensureRow(job.getJobId(),Instant.now(clock));
        WorkflowOrchestrationWork row=work.findForUpdate(job.getJobId()).orElseThrow();
        row.setRequestedGeneration(row.getRequestedGeneration()+1);
        row.setRequestedAt(Instant.now(clock)); work.save(row);
        events.publishEvent(new RuntimeChanged(job.getJobId()));
    }
}
