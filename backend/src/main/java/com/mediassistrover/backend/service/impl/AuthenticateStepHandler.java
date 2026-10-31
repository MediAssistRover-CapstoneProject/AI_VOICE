package com.mediassistrover.backend.service.impl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.enums.StepType;
import com.mediassistrover.backend.service.ServerStepHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class AuthenticateStepHandler implements ServerStepHandler {
    private final ObjectMapper objectMapper;
    @Override public boolean supports(JobStep step){return StepType.AUTHENTICATE.name().equals(step.getStepTypeSnapshot());}
    @Override public ServerStepExecution execute(JobStep step,JsonNode inputs){ObjectNode result=objectMapper.createObjectNode().put("action","AWAIT_STAFF_VERIFICATION").put("job_step_id",step.getJobStepId().toString()).put("purpose",com.mediassistrover.backend.validation.RuntimeAuthorizationPolicy.purpose(step).name());return new ServerStepExecution(result,false);}
}
