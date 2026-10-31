package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.entity.Step;
import com.mediassistrover.backend.entity.StepTransition;
import com.mediassistrover.backend.enums.JobStepStatus;
import com.mediassistrover.backend.enums.TerminalStatus;
import com.mediassistrover.backend.enums.TransitionOutcome;
import com.mediassistrover.backend.repository.StepTransitionRepository;
import com.mediassistrover.backend.service.impl.WorkflowRuntimeTransitionServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowRuntimeTransitionServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    private final StepTransitionRepository repository=mock(StepTransitionRepository.class);
    private final WorkflowRuntimeTransitionServiceImpl service=new WorkflowRuntimeTransitionServiceImpl(repository);

    @Test void choosesConditionBeforeDefaultAndFallsBackWhenConditionIsFalse(){
        Step from=Step.builder().stepId(UUID.randomUUID()).stepCode("CHECK").build();
        Step target=Step.builder().stepId(UUID.randomUUID()).stepCode("NEXT").build();
        StepTransition conditional=StepTransition.builder().fromStep(from).toStep(target).outcome(TransitionOutcome.SUCCESS).priority(1).conditionJson(json.createObjectNode().put("path","result.value").put("operator","EQ").put("value",true)).build();
        StepTransition fallback=StepTransition.builder().fromStep(from).outcome(TransitionOutcome.SUCCESS).priority(2).defaultTransition(true).terminalStatus(TerminalStatus.CANCELLED).build();
        when(repository.findByFromStepStepIdAndOutcomeOrderByPriorityAsc(from.getStepId(),TransitionOutcome.SUCCESS)).thenReturn(List.of(conditional,fallback));

        JobStep visit=JobStep.builder().step(from).stepCodeSnapshot("CHECK").status(JobStepStatus.SUCCEEDED).resultJson(json.createObjectNode().put("value",true)).build();
        assertThat(service.resolve(visit)).isSameAs(conditional);
        visit.setResultJson(json.createObjectNode().put("value",false));
        assertThat(service.resolve(visit)).isSameAs(fallback);
    }
}
