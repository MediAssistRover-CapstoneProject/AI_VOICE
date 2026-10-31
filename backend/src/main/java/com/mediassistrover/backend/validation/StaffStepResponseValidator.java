package com.mediassistrover.backend.validation;
import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.exception.BusinessRuleException;
import org.springframework.stereotype.Component;

@Component
public class StaffStepResponseValidator {
    private final FixedContractSchemaValidator schemas=new FixedContractSchemaValidator();
    public void validate(JsonNode body) {
        if (body==null) throw new BusinessRuleException("Response body is required");
        schemas.validateRoot("staff-step-response.schema.json",body);
        if (body.path("note").asText().length()>2000) throw new BusinessRuleException("note exceeds 2000 characters");
    }
}
