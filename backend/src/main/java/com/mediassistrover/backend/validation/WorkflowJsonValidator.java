package com.mediassistrover.backend.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.exception.BusinessRuleException;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Structural validation only; no workflow-specific ordering rules. */
@Component
public class WorkflowJsonValidator {
    public void object(JsonNode node,String field){
        if(node==null||!node.isObject())throw new BusinessRuleException(field+" must be a JSON object");
        references(node);
    }
    public void array(JsonNode node,String field){
        if(node==null||!node.isArray())throw new BusinessRuleException(field+" must be a JSON array");
        references(node);
    }
    public void references(JsonNode node){
        if(node==null)return;
        if(node.isObject()&&node.has("$ref")){
            JsonNode ref=node.get("$ref");
            if(node.size()!=1||!ref.isTextual()||!ref.asText().matches("^/(inputs|outputs|vars)(/.*)?$"))
                throw new BusinessRuleException("$ref must use /inputs, /outputs/STEP_CODE or /vars");
            String path=ref.asText();
            if(path.matches(".*~(?![01]).*"))throw new BusinessRuleException("Invalid JSON Pointer escape");
        }
        if(node.isContainerNode())node.forEach(this::references);
    }
    public void condition(JsonNode node){
        if(node==null||!node.isObject())throw new BusinessRuleException("Condition must be a JSON object");
        Set<String> allowed=Set.of("path","operator","value");
        node.fieldNames().forEachRemaining(field->{if(!allowed.contains(field))throw new BusinessRuleException("Unknown condition field: "+field);});
        if(!node.path("path").isTextual()||node.path("path").asText().isBlank()
                ||!node.path("operator").isTextual()
                ||!Set.of("EQ","NE","GT","GTE","LT","LTE","IN","EXISTS").contains(node.path("operator").asText()))
            throw new BusinessRuleException("Condition requires path and a supported SAFE_EXPR operator");
        if(!"EXISTS".equals(node.path("operator").asText())&&!node.has("value"))throw new BusinessRuleException("Condition value is required");
        if(node.path("path").asText().length()>500)throw new BusinessRuleException("Condition path is too long");
        AtomicInteger count=new AtomicInteger();count(node,count);if(count.get()>128)throw new BusinessRuleException("Condition exceeds 128 nodes");
    }
    private void count(JsonNode node,AtomicInteger count){count.incrementAndGet();if(node!=null&&node.isContainerNode())node.forEach(child->count(child,count));}
}
