package com.mediassistrover.backend.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.JobEventProducerType;
import java.util.UUID;
public interface RuntimeStepCompletionService {
    boolean complete(JobStep step,JsonNode result,Account actor,JobEventProducerType producer,String eventType,UUID eventId);
}
