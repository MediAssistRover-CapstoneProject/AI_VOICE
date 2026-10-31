package com.mediassistrover.backend.service.impl;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.enums.StepType;
import com.mediassistrover.backend.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class WaitStepHandler implements ServerStepHandler {
    private final WorkflowEventService eventService; private final ObjectMapper objectMapper;
    @Override public boolean supports(JobStep step){return StepType.WAIT.name().equals(step.getStepTypeSnapshot());}
    @Override public ServerStepExecution execute(JobStep step,JsonNode inputs){
        if("EVENT".equals(inputs.path("wait_type").asText())&&eventService.consumeAvailable(step))return new ServerStepExecution(step.getResultJson(),false);
        ObjectNode pending=objectMapper.createObjectNode().put("waiting",true).put("wait_type",inputs.path("wait_type").asText());return new ServerStepExecution(pending,false);
    }
}
