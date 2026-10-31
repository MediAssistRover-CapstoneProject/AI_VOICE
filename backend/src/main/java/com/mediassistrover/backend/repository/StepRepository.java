package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.Step;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface StepRepository extends JpaRepository<Step,UUID> {
    List<Step> findByWorkflowWorkflowIdOrderBySequenceNoAsc(UUID workflowId);
    boolean existsByWorkflowWorkflowIdAndStepCodeIgnoreCase(UUID workflowId,String code);
    boolean existsByWorkflowWorkflowIdAndSequenceNo(UUID workflowId,int sequenceNo);
}
