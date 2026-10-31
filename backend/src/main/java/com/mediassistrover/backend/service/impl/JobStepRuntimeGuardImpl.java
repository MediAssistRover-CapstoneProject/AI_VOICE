package com.mediassistrover.backend.service.impl;

import com.mediassistrover.backend.entity.Job;
import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.entity.StepTransition;
import com.mediassistrover.backend.enums.JobStepStatus;
import com.mediassistrover.backend.enums.TerminalStatus;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.JobStepRepository;
import com.mediassistrover.backend.service.JobStepRuntimeGuard;
import com.mediassistrover.backend.service.WorkflowRuntimeTransitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class JobStepRuntimeGuardImpl implements JobStepRuntimeGuard {
    private static final EnumSet<JobStepStatus> COMPLETED=EnumSet.of(JobStepStatus.SUCCEEDED,JobStepStatus.FAILED,JobStepStatus.TIMED_OUT);
    private final JobStepRepository stepRepository;
    private final WorkflowRuntimeTransitionService transitionService;

    @Override public void requireStartAllowed(JobStep candidate){
        Job job=candidate.getJobTask().getJob();List<JobStep> all=stepRepository.findByJobId(job.getJobId());
        if(all.stream().anyMatch(s->s.getStatus()==JobStepStatus.RECOVERY_REQUIRED))
            throw new BusinessRuleException("Job has a Step requiring recovery; reconcile before continuing");
        List<JobStep> completed=all.stream().filter(s->completed(s,job)).sorted(order(job)).toList();
        if(completed.isEmpty()){
            if(job.getWorkflow().getStartStep()==null||!job.getWorkflow().getStartStep().getStepId().equals(candidate.getStep().getStepId()))throw new BusinessRuleException("Only the Workflow start Step may run first");
            return;
        }
        JobStep previous=completed.getLast();StepTransition route=transitionService.resolve(previous);
        if(route.getToStep()==null)throw new BusinessRuleException("Workflow already reached terminal status "+route.getTerminalStatus());
        if(!route.getToStep().getStepId().equals(candidate.getStep().getStepId()))throw new BusinessRuleException("Step is not the selected transition target");
    }

    @Override public void requireSuccessfulTerminal(Job job){
        List<JobStep> all=stepRepository.findByJobId(job.getJobId());
        if(all.stream().anyMatch(s->s.getStatus()==JobStepStatus.RUNNING||s.getStatus()==JobStepStatus.RETRYING||s.getStatus()==JobStepStatus.RECOVERY_REQUIRED))throw new BusinessRuleException("Job has an unfinished active Step");
        List<JobStep> completed=all.stream().filter(s->completed(s,job)).sorted(order(job)).toList();
        if(completed.isEmpty())throw new BusinessRuleException("Job has no completed Step");
        StepTransition route=transitionService.resolve(completed.getLast());
        if(route.getToStep()!=null||route.getTerminalStatus()!=TerminalStatus.SUCCEEDED)throw new BusinessRuleException("Job cannot succeed before Workflow reaches terminal SUCCEEDED");
    }

    private Comparator<JobStep> order(Job job){return job.isBeOrchestrated()?Comparator.comparingLong(JobStep::getExecutionSequenceNo):Comparator.comparing(JobStep::getEndedAt,Comparator.nullsFirst(Comparator.naturalOrder())).thenComparingLong(JobStep::getExecutionSequenceNo);}
    private boolean completed(JobStep step,Job job){return COMPLETED.contains(step.getStatus())
            || job.isBeOrchestrated() && step.getStatus()==JobStepStatus.CANCELLED && "STEP_TIMEOUT".equals(step.getInterruptionReason());}
}
