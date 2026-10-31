package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.StepTransition;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface StepTransitionRepository extends JpaRepository<StepTransition,UUID> {
    List<StepTransition> findByWorkflowWorkflowIdOrderByFromStepSequenceNoAscOutcomeAscPriorityAsc(UUID workflowId);
    boolean existsByFromStepStepIdOrToStepStepId(UUID fromStepId,UUID toStepId);
    List<StepTransition> findByFromStepStepIdAndOutcomeOrderByPriorityAsc(UUID stepId,com.mediassistrover.backend.enums.TransitionOutcome outcome);
}
