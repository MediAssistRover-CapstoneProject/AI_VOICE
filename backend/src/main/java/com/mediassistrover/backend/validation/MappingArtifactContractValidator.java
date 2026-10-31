package com.mediassistrover.backend.validation;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
@Component
public class MappingArtifactContractValidator {
    private final FixedContractSchemaValidator schemas=new FixedContractSchemaValidator();
    public void manifest(JsonNode manifest) {schemas.validate("common.schema.json","artifact",manifest);}
}
