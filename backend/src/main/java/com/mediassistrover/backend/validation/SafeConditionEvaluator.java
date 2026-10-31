package com.mediassistrover.backend.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.exception.BusinessRuleException;
import org.springframework.stereotype.Component;
import java.util.stream.StreamSupport;

/** Fixed JSON data comparisons; never invokes a script/expression engine. */
@Component
public class SafeConditionEvaluator {
    private final WorkflowJsonValidator validator=new WorkflowJsonValidator();
    public boolean matches(JsonNode root,JsonNode condition) {
        validator.condition(condition);
        String path=condition.path("path").asText();
        JsonNode actual=root;
        if (path.startsWith("/")) actual=root.at(path);
        else for (String segment:path.split("\\.")) actual=actual.path(segment);
        JsonNode expected=condition.get("value");
        return switch (condition.path("operator").asText()) {
            case "EXISTS" -> !actual.isMissingNode() && !actual.isNull();
            case "EQ" -> equivalent(actual,expected);
            case "NE" -> !equivalent(actual,expected);
            case "GT" -> compare(actual,expected)>0;
            case "GTE" -> compare(actual,expected)>=0;
            case "LT" -> compare(actual,expected)<0;
            case "LTE" -> compare(actual,expected)<=0;
            case "IN" -> {
                if (expected==null || !expected.isArray()) throw new BusinessRuleException("IN requires an array");
                JsonNode selected=actual;
                yield StreamSupport.stream(expected.spliterator(),false).anyMatch(v->equivalent(selected,v));
            }
            default -> throw new BusinessRuleException("Unsupported SAFE_EXPR operator");
        };
    }
    private boolean equivalent(JsonNode actual,JsonNode expected) {
        if (actual==null || actual.isMissingNode()) return expected==null || expected.isNull();
        return actual.isNumber() && expected!=null && expected.isNumber()
                ? actual.decimalValue().compareTo(expected.decimalValue())==0 : actual.equals(expected);
    }
    private int compare(JsonNode actual,JsonNode expected) {
        if (actual==null || expected==null || !actual.isNumber() || !expected.isNumber())
            throw new BusinessRuleException("Ordered comparison requires numeric values");
        return actual.decimalValue().compareTo(expected.decimalValue());
    }
}
