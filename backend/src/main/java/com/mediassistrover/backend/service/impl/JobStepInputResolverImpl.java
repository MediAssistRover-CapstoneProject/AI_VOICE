package com.mediassistrover.backend.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.Job;
import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.enums.JobStepStatus;
import com.mediassistrover.backend.enums.StepType;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.JobStepRepository;
import com.mediassistrover.backend.service.JobStepInputResolver;
import com.mediassistrover.backend.validation.StepInputContractValidator;
import com.mediassistrover.backend.validation.StepInputScopeValidator;
import com.mediassistrover.backend.validation.WorkflowJsonValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class JobStepInputResolverImpl implements JobStepInputResolver {
    private final JobStepRepository jobStepRepository;
    private final ObjectMapper objectMapper;
    private final StepInputContractValidator inputValidator;
    private final StepInputScopeValidator scopeValidator;
    private final com.mediassistrover.backend.service.OperationAuthorizationService operationAuthorization;
    private final com.mediassistrover.backend.service.NavigationTargetService navigation;
    private final WorkflowJsonValidator references = new WorkflowJsonValidator();

    @Override
    public JsonNode resolve(JobStep current) {
        ObjectNode root = (ObjectNode) runtimeContext(current);

        JsonNode params = resolveNode(current.getStep().getParamsJson(), root);
        JsonNode mapping = resolveNode(current.getStep().getInputMappingJson(), root);
        if (!params.isObject() || !mapping.isObject()) {
            throw new BusinessRuleException("Step params and inputMapping must resolve to JSON objects");
        }
        ObjectNode resolved = ((ObjectNode) params).deepCopy();
        resolved.setAll((ObjectNode) mapping);
        Job job = current.getJobTask().getJob();
        if (current.getStep().getStepType() == StepType.MOVE && job.getSchemaVersion() == 2) {
            // Explicit BE defaults for schema 2 workflows which predate speed configuration.
            if (!resolved.has("mode")) resolved.put("mode", "NORMAL");
            if (!resolved.has("max_speed_mps")) resolved.put("max_speed_mps", 0.05);
        }
        if (current.getStep().getStepType() == StepType.HANDLE_PAYLOAD
                && !resolved.has("items") && job.getInputJson().has("items"))
            resolved.set("items", job.getInputJson().get("items").deepCopy());
        if(job.getSchemaVersion()==3 && current.getStep().getStepType()==StepType.CONTROL_HARDWARE) {
            if("LOCK".equals(resolved.path("action").asText())) resolved.put("action","LOCK_COMPARTMENT");
            if("UNLOCK".equals(resolved.path("action").asText())) resolved.put("action","UNLOCK_COMPARTMENT");
        }
        inputValidator.validateResolved(current.getStep(), resolved);
        if(job.getSchemaVersion()==3 && current.getStep().getStepType()==StepType.CONTROL_HARDWARE
                && "UNLOCK_COMPARTMENT".equals(resolved.path("action").asText())) {
            ObjectNode business=resolved.deepCopy(); business.remove("authorization");
            scopeValidator.validate(current.getStep(),business,job);
            resolved.set("authorization",operationAuthorization.bind(current,resolved.get("authorization")));
        } else scopeValidator.validate(current.getStep(), resolved, job);
        if(current.getStep().getStepType()==StepType.MOVE) navigation.bindMove(current,resolved);
        return resolved;
    }

    @Override
    public JsonNode runtimeContext(JobStep current) {
        Job job = current.getJobTask().getJob();
        ObjectNode root = objectMapper.createObjectNode();
        root.set("inputs", copy(job.getInputJson()));
        root.set("vars", copy(job.getContextJson()));
        ObjectNode outputs = root.putObject("outputs");

        Map<String, JobStep> latest = new LinkedHashMap<>();
        jobStepRepository.findByJobId(job.getJobId()).stream()
                .filter(step -> step.getStatus() == JobStepStatus.SUCCEEDED && step.getResultJson() != null)
                .sorted(Comparator.comparingLong(JobStep::getExecutionSequenceNo))
                .forEach(step -> latest.put(step.getStepCodeSnapshot(), step));
        latest.forEach((code, step) -> outputs.set(code, step.getResultJson().deepCopy()));
        return root;
    }

    private JsonNode resolveNode(JsonNode node, ObjectNode root) {
        if (node == null || node.isNull()) return objectMapper.nullNode();
        if (node.isObject() && node.size() == 1 && node.has("$ref")) {
            String pointer = node.path("$ref").asText();
            references.references(node);
            JsonNode value = root.at(pointer);
            if (value.isMissingNode()) {
                throw new BusinessRuleException("Cannot resolve Step input reference: " + pointer);
            }
            return value.deepCopy();
        }
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> result.set(entry.getKey(), resolveNode(entry.getValue(), root)));
            return result;
        }
        if (node.isArray()) {
            var result = objectMapper.createArrayNode();
            node.forEach(item -> result.add(resolveNode(item, root)));
            return result;
        }
        return node.deepCopy();
    }

    private JsonNode copy(JsonNode node) {
        return node == null ? objectMapper.createObjectNode() : node.deepCopy();
    }
}
