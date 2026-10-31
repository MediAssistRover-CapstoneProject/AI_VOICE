package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface TaskRepository extends JpaRepository<Task,UUID> {
    List<Task> findByWorkflowWorkflowIdOrderBySequenceNoAsc(UUID workflowId);
    boolean existsByWorkflowWorkflowIdAndTaskCodeIgnoreCase(UUID workflowId,String code);
    boolean existsByWorkflowWorkflowIdAndSequenceNo(UUID workflowId,int sequenceNo);
}
