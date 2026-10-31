package com.mediassistrover.backend.config;

import com.mediassistrover.backend.enums.WorkflowStatus;
import com.mediassistrover.backend.repository.WorkflowRepository;
import com.mediassistrover.backend.service.WorkflowValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Compile only migration-created drafts; never alter authored graphs or publish a workflow. */
@Component
@RequiredArgsConstructor
public class Schema3DraftCompilationInitializer implements ApplicationRunner {
    private final WorkflowRepository workflows;
    private final WorkflowValidationService validation;

    @Override @Transactional
    public void run(ApplicationArguments args) {
        for (var workflow : workflows.findAll()) {
            if (workflow.getSchemaVersion() != 3 || workflow.getStatus() != WorkflowStatus.DRAFT
                    || workflow.getCompiledDefinitionJson() != null || workflow.getDescription() == null
                    || !workflow.getDescription().startsWith("[MAR-STEP-2 DRAFT]")) continue;
            var compiled = validation.validateAndCompile(workflow);
            workflow.setCompiledDefinitionJson(compiled.compiledDefinition());
            workflow.setDefinitionHash(compiled.definitionHash());
            // Consume the migration marker once; later admin draft edits must not be compiled at startup.
            workflow.setDescription(workflow.getDescription().replaceFirst("\\[MAR-STEP-2 DRAFT\\]", "[MAR-STEP-2 COMPILED DRAFT]"));
        }
    }
}
