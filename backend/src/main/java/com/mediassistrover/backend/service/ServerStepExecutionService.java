package com.mediassistrover.backend.service;

import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.validation.StepExecutionPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.List;

@Service @RequiredArgsConstructor
public class ServerStepExecutionService {
    private final List<ServerStepHandler> handlers;
    private final RuntimeStepCompletionService completion;
    private final Clock clock;
    private final StepExecutionPolicy executionPolicy;

    @Transactional
    public void execute(JobStep step) {
        var job=step.getJobTask().getJob();
        if (!job.isBeOrchestrated() || job.getStatus()!=JobStatus.RUNNING
                || job.getSelectedJobStep()==null || !job.getSelectedJobStep().getJobStepId().equals(step.getJobStepId()))
            throw new BusinessRuleException("Server execution requires the selected BE JobStep");
        if (!executionPolicy.isServer(step,step.getResolvedInputsJson())) throw new BusinessRuleException("Step requires MQTT execution");
        if (step.getStatus()!=JobStepStatus.PENDING) throw new BusinessRuleException("Server step must be PENDING");
        step.setStatus(JobStepStatus.RUNNING); step.setStartedAt(Instant.now(clock));
        var execution=executePrepared(step);
        // WAIT/EVENT can complete itself while consuming an early event.
        if (step.getStatus()==JobStepStatus.SUCCEEDED) return;
        if (execution.completed()) completion.complete(step,execution.result(),null,JobEventProducerType.SERVER,"SERVER_STEP_COMPLETED",null);
        else step.setResultJson(execution.result().deepCopy());
    }

    public ServerStepHandler.ServerStepExecution executePrepared(JobStep step) {
        return handlers.stream().filter(handler->handler.supports(step)).findFirst()
                .orElseThrow(()->new BusinessRuleException("No Server handler for "+step.getStepTypeSnapshot()))
                .execute(step,step.getResolvedInputsJson());
    }
}
