package com.mediassistrover.backend.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.entity.StepTransition;
import com.mediassistrover.backend.enums.JobStepStatus;
import com.mediassistrover.backend.enums.TransitionOutcome;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.StepTransitionRepository;
import com.mediassistrover.backend.service.WorkflowRuntimeTransitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkflowRuntimeTransitionServiceImpl implements WorkflowRuntimeTransitionService {
    private final StepTransitionRepository transitionRepository;
    private final com.mediassistrover.backend.validation.SafeConditionEvaluator evaluator = new com.mediassistrover.backend.validation.SafeConditionEvaluator();

    @Override
    public StepTransition resolve(JobStep completedStep) {
        TransitionOutcome outcome = "STEP_TIMEOUT".equals(completedStep.getInterruptionReason()) && completedStep.getJobTask().getJob().isBeOrchestrated()
                ? TransitionOutcome.TIMEOUT : switch (completedStep.getStatus()) {
            case SUCCEEDED -> TransitionOutcome.SUCCESS;
            case FAILED -> TransitionOutcome.FAILURE;
            case TIMED_OUT -> TransitionOutcome.TIMEOUT;
            default -> throw new BusinessRuleException("Step has no transition outcome in status " + completedStep.getStatus());
        };
        List<StepTransition> routes = transitionRepository
                .findByFromStepStepIdAndOutcomeOrderByPriorityAsc(completedStep.getStep().getStepId(), outcome);
        ObjectNode root = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        root.set("result", completedStep.getResultJson());
        root.set("error", completedStep.getErrorDetailsJson());
        root.set("inputs", completedStep.getResolvedInputsJson());
        for (StepTransition route : routes) {
            if (!route.isDefaultTransition() && evaluator.matches(root, route.getConditionJson())) return route;
        }
        return routes.stream().filter(StepTransition::isDefaultTransition).findFirst()
                .orElseThrow(() -> new BusinessRuleException("No matching/default transition for step " + completedStep.getStepCodeSnapshot()));
    }

}
