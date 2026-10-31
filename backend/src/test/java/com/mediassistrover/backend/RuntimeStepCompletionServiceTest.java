package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.entity.Job;
import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.entity.JobTask;
import com.mediassistrover.backend.entity.Step;
import com.mediassistrover.backend.enums.JobEventProducerType;
import com.mediassistrover.backend.enums.JobStatus;
import com.mediassistrover.backend.enums.JobStepStatus;
import com.mediassistrover.backend.enums.StepType;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.mqtt.MqttProperties;
import com.mediassistrover.backend.repository.JobEventRepository;
import com.mediassistrover.backend.repository.JobRepository;
import com.mediassistrover.backend.repository.JobStepRepository;
import com.mediassistrover.backend.service.OrchestrationWorkService;
import com.mediassistrover.backend.service.impl.RuntimeStepCompletionServiceImpl;
import com.mediassistrover.backend.validation.StepExecutionPolicy;
import com.mediassistrover.backend.validation.StepResultContractValidator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeStepCompletionServiceTest {
    @Test
    void backendCompletionCannotWinRaceAgainstMqttDurationWait() {
        ObjectMapper json = new ObjectMapper();
        UUID jobId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        Job job = Job.builder().jobId(jobId).schemaVersion(3).beOrchestrated(true)
                .status(JobStatus.RUNNING).build();
        JobTask task = JobTask.builder().job(job).build();
        Step definition = Step.builder().stepType(StepType.WAIT).build();
        JobStep step = JobStep.builder().jobStepId(stepId).jobTask(task).step(definition)
                .stepTypeSnapshot("WAIT").status(JobStepStatus.RUNNING)
                .resolvedInputsJson(json.createObjectNode()
                        .put("wait_type", "DURATION").put("duration_sec", 5)).build();
        job.setSelectedJobStep(step);

        JobRepository jobs = mock(JobRepository.class);
        JobStepRepository steps = mock(JobStepRepository.class);
        when(jobs.lockById(jobId)).thenReturn(Optional.of(job));
        when(steps.lockById(stepId)).thenReturn(Optional.of(step));
        RuntimeStepCompletionServiceImpl service = new RuntimeStepCompletionServiceImpl(
                jobs, mock(OrchestrationWorkService.class), new StepResultContractValidator(), steps,
                mock(JobEventRepository.class), Clock.systemUTC(),
                new StepExecutionPolicy(new MqttProperties()));

        var result = json.createObjectNode().put("completed", true)
                .put("wake_reason", "DURATION").put("elapsed_sec", 5);
        result.putNull("event");
        assertThatThrownBy(() -> service.complete(step, result, null,
                JobEventProducerType.SCHEDULER, "WAIT_DURATION_COMPLETED", null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("MQTT-owned JobStep");
    }
}
