package com.mediassistrover.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.entity.JobStep;

public interface JobStepInputResolver {
    JsonNode resolve(JobStep jobStep);
    JsonNode runtimeContext(JobStep jobStep);
}
