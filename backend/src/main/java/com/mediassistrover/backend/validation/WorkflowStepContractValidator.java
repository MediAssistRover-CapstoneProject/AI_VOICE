package com.mediassistrover.backend.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.Step;
import com.mediassistrover.backend.entity.Workflow;
import com.mediassistrover.backend.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class WorkflowStepContractValidator {
    private final StepInputContractValidator inputValidator;
    private final StepInputScopeValidator scopeValidator;

    public void validate(Workflow workflow, List<Step> steps) {
        Set<String> codes = new HashSet<>();
        steps.forEach(s -> codes.add(s.getStepCode()));
        Set<String> inputs = new HashSet<>();
        JsonNode schema = workflow.getInputSchemaJson();
        if (schema != null && schema.path("properties").isObject()) schema.path("properties").fieldNames().forEachRemaining(inputs::add);
        JsonNode defaults = workflow.getDefaultInputsJson();
        if (defaults != null && defaults.isObject()) defaults.fieldNames().forEachRemaining(inputs::add);
        if (workflow.getSchemaVersion()==3) {
            new WorkflowJsonValidator().references(workflow.getOutputMappingJson());
            if (!steps.isEmpty()) references(steps.getFirst(),workflow.getOutputMappingJson(),codes,inputs);
        }
        for (Step step : steps) {
            inputValidator.validateTemplate(step);
            references(step, step.getParamsJson(), codes, inputs);
            references(step, step.getInputMappingJson(), codes, inputs);
            ObjectNode merged = ((ObjectNode) step.getParamsJson()).deepCopy();
            merged.setAll((ObjectNode) step.getInputMappingJson());
            JsonNode effective = withDefaults(merged, defaults);
            inputValidator.validateEffectiveTemplate(step, effective);
            scopeValidator.validate(step, effective, null);
        }
    }

    private JsonNode withDefaults(JsonNode node, JsonNode defaults) {
        if (node.isObject() && node.size() == 1 && node.has("$ref")) {
            String pointer = node.path("$ref").asText();
            if (defaults != null && pointer.startsWith("/inputs/")) {
                JsonNode value = defaults.at(pointer.substring("/inputs".length()));
                if (!value.isMissingNode()) return value.deepCopy();
            }
            return node.deepCopy();
        }
        JsonNode copy = node.deepCopy();
        if (copy.isObject()) ((ObjectNode) copy).properties().forEach(e -> ((ObjectNode) copy).set(e.getKey(), withDefaults(e.getValue(), defaults)));
        else if (copy.isArray()) for (int i = 0; i < copy.size(); i++) ((com.fasterxml.jackson.databind.node.ArrayNode) copy).set(i, withDefaults(copy.get(i), defaults));
        return copy;
    }

    private void references(Step step, JsonNode node, Set<String> codes, Set<String> inputs) {
        if (node.isObject() && node.has("$ref")) {
            String ref = node.path("$ref").asText();
            String[] parts = ref.split("/", 4);
            if (parts.length >= 3 && "inputs".equals(parts[1]) && !inputs.contains(unescape(parts[2])))
                throw invalid(step, "undeclared workflow input reference: " + ref);
            if (parts.length >= 3 && "outputs".equals(parts[1]) && !codes.contains(unescape(parts[2])))
                throw invalid(step, "unknown step output reference: " + ref);
        }
        if (node.isContainerNode()) node.forEach(child -> references(step, child, codes, inputs));
    }
    private String unescape(String s) { return s.replace("~1", "/").replace("~0", "~"); }
    private BusinessRuleException invalid(Step step, String reason) {
        return new BusinessRuleException("Invalid contract for step " + step.getStepCode() + ": " + reason);
    }
}
