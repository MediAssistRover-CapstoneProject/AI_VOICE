package com.mediassistrover.backend.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.entity.Workflow;
import java.util.List;
public interface WorkflowValidationService {
    CompilationResult validateAndCompile(Workflow workflow);
    record CompilationResult(JsonNode compiledDefinition,String definitionHash,int taskCount,int stepCount,int transitionCount,List<String> warnings) {}
}
