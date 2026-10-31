package com.mediassistrover.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.entity.JobStep;

public interface ServerStepHandler {
    boolean supports(JobStep step);
    ServerStepExecution execute(JobStep step,JsonNode resolvedInputs);

    record ServerStepExecution(JsonNode result,boolean completed) { }
}
