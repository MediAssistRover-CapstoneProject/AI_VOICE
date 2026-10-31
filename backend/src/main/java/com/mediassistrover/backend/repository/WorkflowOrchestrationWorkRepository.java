package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.WorkflowOrchestrationWork;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import java.util.*;

public interface WorkflowOrchestrationWorkRepository extends JpaRepository<WorkflowOrchestrationWork,UUID> {
    @Modifying(flushAutomatically=true)
    @Query(value="INSERT INTO WorkflowOrchestrationWork(job_id,requested_generation,processed_generation,requested_at) VALUES (:id,0,0,:now) ON DUPLICATE KEY UPDATE job_id=job_id",nativeQuery=true)
    void ensureRow(@org.springframework.data.repository.query.Param("id") UUID id,
                   @org.springframework.data.repository.query.Param("now") java.time.Instant now);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkflowOrchestrationWork w where w.jobId=:id")
    Optional<WorkflowOrchestrationWork> findForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
    @Query("select w.jobId from WorkflowOrchestrationWork w where w.requestedGeneration > w.processedGeneration order by w.requestedAt")
    List<UUID> findPending(Pageable page);
}
