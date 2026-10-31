package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.config.Schema3DraftCompilationInitializer;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Transactional
class Schema3MigrationIntegrationTest extends MySqlIntegrationTest {
    @Autowired WorkflowRepository workflows;
    @Autowired StepRepository definitions;
    @Autowired TaskRepository tasks;
    @Autowired JobRepository jobs;
    @Autowired JobTaskRepository jobTasks;
    @Autowired JobStepRepository jobSteps;
    @Autowired WorkflowService workflowService;
    @Autowired WorkflowValidationService validation;
    @Autowired Schema3DraftCompilationInitializer initializer;
    @Autowired JobStepRuntimeGuard runtimeGuard;
    @Autowired ObjectMapper json;
    @Autowired com.mediassistrover.backend.validation.WorkflowExecutionReadiness readiness;

    @Test void clonesCompileWithNewIdsAndContractsWhileLegacyDefinitionsRemainIntact() {
        var drafts = workflows.findAll().stream().filter(w -> w.getSchemaVersion() == 3 && !"WF_MAPPING".equals(w.getWorkflowCode())).toList();
        assertThat(drafts).hasSize(11);
        for (Workflow draft : drafts) {
            Workflow legacy = workflows.findAll().stream().filter(w -> w.getWorkflowCode().equals(draft.getWorkflowCode()) && w.getSchemaVersion() == 2)
                    .max(Comparator.comparingInt(Workflow::getVersionNo)).orElseThrow();
            assertThat(draft.getVersionNo()).isEqualTo(legacy.getVersionNo() + 1);
            assertThat(draft.getWorkflowId()).isNotEqualTo(legacy.getWorkflowId());
            assertThat(draft.isAcceptsNewJobs()).isFalse();
            assertThat(draft.getStartStep().getStepCode()).isEqualTo(legacy.getStartStep().getStepCode());
            var allModern = definitions.findByWorkflowWorkflowIdOrderBySequenceNoAsc(draft.getWorkflowId());
            var modern = allModern.stream().filter(s->!s.getStepCode().startsWith("GROUP_")).toList();
            if("WF_SECURE_MEDICAL_DELIVERY".equals(draft.getWorkflowCode())) assertThat(allModern.size()-modern.size()).isEqualTo(7);
            var old = definitions.findByWorkflowWorkflowIdOrderBySequenceNoAsc(legacy.getWorkflowId());
            assertThat(modern).hasSize(old.size());
            for (int i = 0; i < modern.size(); i++) {
                assertThat(modern.get(i).getStepId()).isNotEqualTo(old.get(i).getStepId());
                assertThat(modern.get(i).getStepType()).isEqualTo(old.get(i).getStepType());
                if (modern.get(i).getStepType() == StepType.INTERACT || modern.get(i).getStepType() == StepType.NOTIFY)
                    assertThat(modern.get(i).getInputMappingJson().has("data")).isTrue();
                if(modern.get(i).getStepCode().startsWith("UNLOCK_") && "WF_SECURE_MEDICAL_DELIVERY".equals(draft.getWorkflowCode())) {
                    String auth=modern.get(i).getStepCode().endsWith("SENDER")?"AUTH_SENDER":"AUTH_RECEIVER";
                    assertThat(modern.get(i).getInputMappingJson().path("authorization").path("verification_id").path("$ref").asText())
                            .isEqualTo("/outputs/"+auth+"/verification_id");
                    assertThat(old.get(i).getInputMappingJson().has("authorization")).isFalse();
                    assertThat(modern.get(i).getMaxAttempts()).isEqualTo(1);
                }
            }
            draft.setStatus(WorkflowStatus.DRAFT); draft.setCompiledDefinitionJson(null);
            draft.setDescription("[MAR-STEP-2 DRAFT] Migration compilation test");
            var compiled = validation.validateAndCompile(draft);
            assertThat(compiled.compiledDefinition().path("schemaVersion").asInt()).isEqualTo(3);
            assertThat(compiled.definitionHash()).hasSize(64);
        }
        initializer.run(new DefaultApplicationArguments());
        for (Workflow draft : drafts) {
            assertThat(draft.getStatus()).isEqualTo(WorkflowStatus.DRAFT);
            assertThat(draft.getCompiledDefinitionJson()).isNotNull();
            assertThat(draft.getDefinitionHash()).hasSize(64);
            draft.setStatus(WorkflowStatus.VALIDATED);
            if(java.util.Set.of("WF_EMERGENCY_SOS_RESPONSE","WF_FALL_DETECTION_RESPONSE","WF_INFORMATION_ASSISTANCE").contains(draft.getWorkflowCode()))
                assertThatCode(()->readiness.requireReady(draft)).doesNotThrowAnyException(); // NOTIFY now joins the integrated server subset.
            else assertThatThrownBy(() -> workflowService.publish(draft.getWorkflowId(), null, null)).hasMessageContaining("keep workflow DRAFT");
            draft.setStatus(WorkflowStatus.DRAFT);
        }
        Workflow edited = drafts.getFirst();
        edited.setCompiledDefinitionJson(null);
        initializer.run(new DefaultApplicationArguments());
        assertThat(edited.getCompiledDefinitionJson()).isNull();
    }

    @Test void recoveryStatusPersistsAndPreventsFurtherExecution() {
        Workflow w = workflows.findAll().stream().filter(x -> x.getSchemaVersion() == 2).findFirst().orElseThrow();
        Step definition = w.getStartStep();
        Job job = jobs.saveAndFlush(Job.builder().workflow(w).jobCode("RECOVERY-" + UUID.randomUUID()).status(JobStatus.RUNNING)
                .priority(1).inputJson(json.createObjectNode()).contextJson(json.createObjectNode()).workflowHash("a".repeat(64))
                .protocolVersion(1).schemaVersion(2).graphVersion(3).queuedAt(Instant.now()).build());
        JobTask task = jobTasks.saveAndFlush(JobTask.builder().job(job).task(definition.getTask()).sequenceNo(1)
                .taskNameSnapshot("Recovery test").configSnapshotJson(json.createObjectNode()).status(JobTaskStatus.RUNNING).build());
        JobStep step = jobSteps.saveAndFlush(JobStep.builder().jobTask(task).step(definition).visitNo(1).executionSequenceNo(1)
                .stepCodeSnapshot(definition.getStepCode()).stepTypeSnapshot(definition.getStepType().name())
                .resolvedInputsJson(json.createObjectNode()).status(JobStepStatus.RECOVERY_REQUIRED).attemptNo(1)
                .operationKey(UUID.randomUUID().toString()).timeoutSecSnapshot(30).build());
        assertThat(jobSteps.findById(step.getJobStepId()).orElseThrow().getStatus()).isEqualTo(JobStepStatus.RECOVERY_REQUIRED);
        assertThatThrownBy(() -> runtimeGuard.requireStartAllowed(step)).hasMessageContaining("requiring recovery");
        assertThatThrownBy(() -> runtimeGuard.requireSuccessfulTerminal(job)).hasMessageContaining("unfinished");
    }
}
