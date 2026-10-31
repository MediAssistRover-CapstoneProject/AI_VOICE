package com.mediassistrover.backend.service.impl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.enums.StepType;
import com.mediassistrover.backend.service.ServerStepHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class HandlePayloadStepHandler implements ServerStepHandler {
    private final ObjectMapper objectMapper;
    @Override public boolean supports(JobStep step){return StepType.HANDLE_PAYLOAD.name().equals(step.getStepTypeSnapshot());}
    @Override public ServerStepExecution execute(JobStep step,JsonNode inputs){ObjectNode result=objectMapper.createObjectNode().put("action","AWAIT_STAFF_PAYLOAD_CONFIRMATION").put("job_step_id",step.getJobStepId().toString()).set("resolved_inputs",inputs);return new ServerStepExecution(result,false);}
}
