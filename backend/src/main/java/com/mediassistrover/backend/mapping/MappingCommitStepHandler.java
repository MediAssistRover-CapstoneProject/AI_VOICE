package com.mediassistrover.backend.mapping;
import com.fasterxml.jackson.databind.*;
import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.enums.StepType;
import com.mediassistrover.backend.service.ServerStepHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class MappingCommitStepHandler implements ServerStepHandler {
    private final MappingArtifactService artifacts;
    private final ObjectMapper json;
    public boolean supports(JobStep step){return step.getStep().getStepType()==StepType.DATA_SERVICE && "COMMIT_MAP_ARTIFACT".equals(step.getResolvedInputsJson().path("service_key").asText());}
    public ServerStepExecution execute(JobStep step,JsonNode input){var result=json.createObjectNode().put("request_id",step.getOperationKey());result.set("data",artifacts.commit(step,input));return new ServerStepExecution(result,true);}
}
