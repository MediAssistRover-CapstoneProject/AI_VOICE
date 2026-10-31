package com.mediassistrover.backend.service.impl;

import com.fasterxml.jackson.databind.*;
import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.service.*;
import com.mediassistrover.backend.validation.SafeConditionEvaluator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class CheckConditionStepHandler implements ServerStepHandler {
    private final SafeConditionEvaluator evaluator;
    private final JobStepInputResolver resolver;
    private final ObjectMapper json;
    @Override public boolean supports(JobStep step) { return "CHECK_CONDITION".equals(step.getStepTypeSnapshot()); }
    @Override public ServerStepExecution execute(JobStep step,JsonNode inputs) {
        return new ServerStepExecution(json.createObjectNode().put("value",evaluator.matches(resolver.runtimeContext(step),inputs.path("expression"))),true);
    }
}
