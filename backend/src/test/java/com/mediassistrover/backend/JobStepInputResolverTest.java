package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.Job;
import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.entity.JobTask;
import com.mediassistrover.backend.entity.Step;
import com.mediassistrover.backend.enums.JobStepStatus;
import com.mediassistrover.backend.repository.JobStepRepository;
import com.mediassistrover.backend.service.impl.JobStepInputResolverImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobStepInputResolverTest {
    private final ObjectMapper json = new ObjectMapper();
    private final JobStepRepository repository = mock(JobStepRepository.class);
    private final JobStepInputResolverImpl resolver = new JobStepInputResolverImpl(repository, json,
            mock(com.mediassistrover.backend.validation.StepInputContractValidator.class),
            mock(com.mediassistrover.backend.validation.StepInputScopeValidator.class),
            mock(com.mediassistrover.backend.service.OperationAuthorizationService.class),
            mock(com.mediassistrover.backend.service.NavigationTargetService.class));

    @Test void rejectsInvalidResolvedValuesAndNestedReferenceInjection() {
        var checked = new JobStepInputResolverImpl(repository, json,
                new com.mediassistrover.backend.validation.StepInputContractValidator(new com.mediassistrover.backend.validation.WorkflowJsonValidator()),
                mock(com.mediassistrover.backend.validation.StepInputScopeValidator.class),
                mock(com.mediassistrover.backend.service.OperationAuthorizationService.class),
            mock(com.mediassistrover.backend.service.NavigationTargetService.class));
        Job job=Job.builder().jobId(UUID.randomUUID()).inputJson(json.createObjectNode().put("duration",0))
                .contextJson(json.createObjectNode()).build();
        Step definition=Step.builder().stepCode("PAUSE").stepType(com.mediassistrover.backend.enums.StepType.WAIT)
                .timeoutSec(5).maxAttempts(1).paramsJson(json.createObjectNode().put("wait_type","DURATION").put("duration_sec",2))
                .inputMappingJson(json.createObjectNode().set("duration_sec",ref("/inputs/duration"))).build();
        JobStep current=JobStep.builder().jobTask(JobTask.builder().job(job).build()).step(definition).build();
        when(repository.findByJobId(job.getJobId())).thenReturn(List.of(current));
        assertThatThrownBy(()->checked.resolve(current)).hasMessageContaining("PAUSE").hasMessageContaining("duration_sec");
        ((ObjectNode)job.getInputJson()).set("duration",ref("/vars/value"));
        assertThatThrownBy(()->checked.resolve(current)).hasMessageContaining("$ref");
        ((ObjectNode)job.getInputJson()).put("duration",2);
        var resolved=checked.resolve(current);
        ((ObjectNode)job.getInputJson()).put("duration",3);
        assertThat(resolved.path("duration_sec").asInt()).isEqualTo(2);
    }

    @Test
    void resolvesInputsOutputsAndVarsWithMappingPrecedence() {
        UUID jobId = UUID.randomUUID();
        Job job = Job.builder().jobId(jobId)
                .inputJson(object("destination", "ROOM_A101"))
                .contextJson(object("loop", 2))
                .build();
        JobTask task = JobTask.builder().jobTaskId(UUID.randomUUID()).job(job).build();
        Step previousDefinition = Step.builder().stepCode("LOOKUP").build();
        JobStep previous = JobStep.builder().jobTask(task).step(previousDefinition)
                .stepCodeSnapshot("LOOKUP").executionSequenceNo(1).status(JobStepStatus.SUCCEEDED)
                .resultJson(object("answer", "Radiology is on floor 2")).build();

        ObjectNode params = json.createObjectNode().put("speed", 0.5).put("destination", "DEFAULT");
        ObjectNode mapping = json.createObjectNode();
        mapping.set("destination", ref("/inputs/destination"));
        mapping.set("answer", ref("/outputs/LOOKUP/answer"));
        mapping.set("loop", ref("/vars/loop"));
        Step currentDefinition = Step.builder().stepCode("MOVE").paramsJson(params).inputMappingJson(mapping).build();
        JobStep current = JobStep.builder().jobTask(task).step(currentDefinition)
                .stepCodeSnapshot("MOVE").executionSequenceNo(2).status(JobStepStatus.PENDING).build();
        when(repository.findByJobId(jobId)).thenReturn(List.of(previous, current));

        var resolved = resolver.resolve(current);

        assertThat(resolved.path("destination").asText()).isEqualTo("ROOM_A101");
        assertThat(resolved.path("answer").asText()).isEqualTo("Radiology is on floor 2");
        assertThat(resolved.path("loop").asInt()).isEqualTo(2);
        assertThat(resolved.path("speed").asDouble()).isEqualTo(0.5);
    }

    @Test
    void rejectsReferenceWhoseRuntimeValueDoesNotExist() {
        UUID jobId = UUID.randomUUID();
        Job job = Job.builder().jobId(jobId).inputJson(json.createObjectNode())
                .contextJson(json.createObjectNode()).build();
        JobTask task = JobTask.builder().job(job).build();
        Step definition = Step.builder().paramsJson(json.createObjectNode())
                .inputMappingJson(json.createObjectNode().set("value", ref("/outputs/MISSING/value"))).build();
        JobStep current = JobStep.builder().jobTask(task).step(definition).build();
        when(repository.findByJobId(jobId)).thenReturn(List.of(current));

        assertThatThrownBy(() -> resolver.resolve(current))
                .hasMessageContaining("/outputs/MISSING/value");
    }

    private ObjectNode ref(String pointer) {
        return json.createObjectNode().put("$ref", pointer);
    }

    private ObjectNode object(String field, String value) {
        return json.createObjectNode().put(field, value);
    }

    private ObjectNode object(String field, int value) {
        return json.createObjectNode().put(field, value);
    }
}
