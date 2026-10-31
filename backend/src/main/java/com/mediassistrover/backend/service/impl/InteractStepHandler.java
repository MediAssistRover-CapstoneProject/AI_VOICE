package com.mediassistrover.backend.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.enums.StepType;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.service.ServerStepHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InteractStepHandler implements ServerStepHandler {
    private final ObjectMapper objectMapper;
    @Override public boolean supports(JobStep step){return step.getStep().getStepType()==StepType.INTERACT;}
    @Override public ServerStepExecution execute(JobStep step,JsonNode inputs){
        String promptKey=inputs.path("prompt_key").asText();
        if(promptKey.isBlank())throw new BusinessRuleException("INTERACT requires prompt_key");
        ObjectNode instruction=objectMapper.createObjectNode().put("promptKey",promptKey).put("action",inputs.path("action").asText("DISPLAY"));
        instruction.set("data",inputs.deepCopy());
        return new ServerStepExecution(instruction,false);
    }
}
